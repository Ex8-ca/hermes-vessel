"""Play the phone against a running companion server, with a continuous mic stream like a real phone.

Phase 1: a typed turn that needs Hermes (exercises ask_hermes).
Phase 2: real speech (the WAV saved by `hermes companion selftest`) injected into the mic stream
         (exercises voice activity detection, transcription and the spoken reply).

Usage: python tools/fake_phone.py [ws-url] [--skip-hermes]
       default url: ws://127.0.0.1:8796/ws
"""
import asyncio
import json
import os
import random
import struct
import sys
import time
import wave
from pathlib import Path

import aiohttp

DATA = Path(os.environ.get("HERMES_HOME", Path.home() / ".hermes")) / "plugin-data" / "companion"
TOKEN = (DATA / "pair_token").read_text().strip()
ARGS = [a for a in sys.argv[1:] if not a.startswith("--")]
URL = ARGS[0] if ARGS else "ws://127.0.0.1:8796/ws"
SKIP_PHASE1 = "--skip-hermes" in sys.argv
CHUNK = 4800  # 100 ms of PCM16 mono at 24 kHz


def room_noise() -> bytes:
    return struct.pack("<2400h", *(random.randint(-40, 40) for _ in range(2400)))


async def main() -> None:
    t0 = time.monotonic()
    audio = {"bytes": 0}
    speech_queue: asyncio.Queue = asyncio.Queue()
    tool_seen = asyncio.Event()
    answered = asyncio.Event()
    spoken_reply = asyncio.Event()
    phase = {"n": 1}

    def stamp() -> str:
        return f"{time.monotonic() - t0:6.2f}s"

    async with aiohttp.ClientSession() as session:
        async with session.ws_connect(f"{URL}?t={TOKEN}", max_msg_size=0) as ws:

            async def reader() -> None:
                async for msg in ws:
                    if msg.type == aiohttp.WSMsgType.BINARY:
                        audio["bytes"] += len(msg.data)
                    elif msg.type == aiohttp.WSMsgType.TEXT:
                        event = json.loads(msg.data)
                        print(stamp(), event, flush=True)
                        if event.get("type") == "transcript" and event.get("role") == "tool":
                            tool_seen.set()
                        if event.get("type") == "transcript" and event.get("role") == "assistant":
                            if phase["n"] == 1 and tool_seen.is_set():
                                answered.set()
                            if phase["n"] == 2:
                                spoken_reply.set()
                    else:
                        break

            async def mic() -> None:
                next_at = time.monotonic()
                while not ws.closed:
                    frame = speech_queue.get_nowait() if not speech_queue.empty() else room_noise()
                    await ws.send_bytes(frame)
                    next_at += 0.1
                    await asyncio.sleep(max(0.0, next_at - time.monotonic()))

            reader_task = asyncio.create_task(reader())
            mic_task = asyncio.create_task(mic())
            await asyncio.sleep(2.5)

            if not SKIP_PHASE1:
                print(stamp(), "PHASE 1: typed turn that needs Hermes", flush=True)
                await ws.send_str(json.dumps({
                    "type": "text",
                    "text": "Ask Hermes how much free space is left on the root filesystem of this computer, then tell me.",
                }))
                try:
                    await asyncio.wait_for(answered.wait(), 150)
                except asyncio.TimeoutError:
                    print(stamp(), "phase 1 timed out", flush=True)
                await asyncio.sleep(3)
                print(stamp(), f"phase 1 audio back: {audio['bytes'] / 48000:.1f}s", flush=True)

            phase["n"] = 2
            before = audio["bytes"]
            print(stamp(), "PHASE 2: speaking into the mic stream", flush=True)
            with wave.open(str(DATA / "selftest.wav")) as w:
                pcm = w.readframes(w.getnframes())
            for i in range(0, len(pcm), CHUNK):
                frame = pcm[i:i + CHUNK]
                speech_queue.put_nowait(frame + b"\x00" * (CHUNK - len(frame)))
            await asyncio.sleep(len(pcm) / 48000)
            spoke_end = time.monotonic()
            while audio["bytes"] == before and time.monotonic() - spoke_end < 30:
                await asyncio.sleep(0.05)
            if audio["bytes"] > before:
                print(stamp(), f"reply audio began {time.monotonic() - spoke_end:.2f}s after speech ended", flush=True)
            try:
                await asyncio.wait_for(spoken_reply.wait(), 30)
            except asyncio.TimeoutError:
                print(stamp(), "phase 2 timed out", flush=True)
            await asyncio.sleep(2)
            print(stamp(), f"phase 2 audio back: {(audio['bytes'] - before) / 48000:.1f}s", flush=True)
            await ws.send_str(json.dumps({"type": "stop"}))
            await asyncio.sleep(1)
            mic_task.cancel()
            reader_task.cancel()


asyncio.run(main())
