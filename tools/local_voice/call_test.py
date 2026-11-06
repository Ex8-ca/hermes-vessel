"""End-to-end call on the local engine, like a phone: stream recorded speech into Vessel's /ws?voice=local,
keep the mic open between turns, and log everything the phone would see. Saves the voice that came back.

Usage: python tools/local_voice/call_test.py [ws-url] wav1 [wav2 ...]   (16 kHz or 24 kHz mono WAVs)
"""
import asyncio, json, os, struct, sys, time, wave
from pathlib import Path

import aiohttp
import numpy as np

DATA = Path(os.environ.get("HERMES_HOME", Path.home() / ".hermes")) / "plugin-data" / "vessel"
TOKEN = (DATA / "pair_token").read_text().strip()
ARGS = [a for a in sys.argv[1:]]
URL = ARGS.pop(0) if ARGS and ARGS[0].startswith("ws") else "ws://127.0.0.1:8796/ws"
WAVS = ARGS
GAP = float(os.environ.get("GAP", "14"))


def load24(path):
    with wave.open(path) as w:
        rate, x = w.getframerate(), np.frombuffer(w.readframes(w.getnframes()), dtype="<i2").astype(np.float32)
    if rate != 24000:
        n = int(len(x) * 24000 / rate)
        x = np.interp(np.arange(n) * (rate / 24000), np.arange(len(x)), x)
    return x.astype("<i2").tobytes()


async def main():
    t0 = time.monotonic()
    got = bytearray()
    log = []

    def note(kind, text=""):
        line = f"{time.monotonic() - t0:6.2f}s {kind} {text}".rstrip()
        log.append(line)
        print(line, flush=True)

    async with aiohttp.ClientSession() as http:
        async with http.ws_connect(f"{URL}?t={TOKEN}&voice=local", max_msg_size=0) as ws:
            async def reader():
                async for msg in ws:
                    if msg.type == aiohttp.WSMsgType.BINARY:
                        if not got:
                            note("first audio")
                        got.extend(msg.data)
                    elif msg.type == aiohttp.WSMsgType.TEXT:
                        d = json.loads(msg.data)
                        if d["type"] == "caption":
                            continue
                        note(d["type"], d.get("state") or d.get("role", "") + (": " + d.get("text", "") if d.get("text") else "")
                             + (d.get("detail") and f" ({d['detail']})" or "") + (d.get("message") or ""))
            rd = asyncio.create_task(reader())
            silence = b"\x00\x00" * 2400  # 100 ms

            async def stream(pcm):
                for i in range(0, len(pcm), 4800):
                    await ws.send_bytes(pcm[i:i + 4800].ljust(4800, b"\x00"))
                    await asyncio.sleep(0.1)

            await stream(silence * 15)
            for w in WAVS:
                note("USER SAYS", Path(w).stem)
                await stream(load24(w))
                await stream(silence * int(GAP * 10))
            await ws.send_str(json.dumps({"type": "stop"}))
            await asyncio.sleep(0.5)
            rd.cancel()
    out = Path("/home/marc/.hermes/cache/scratch/minicpmo/call_test_out.wav")
    with wave.open(str(out), "wb") as w:
        w.setnchannels(1); w.setsampwidth(2); w.setframerate(24000); w.writeframes(bytes(got))
    print(f"\nreceived {len(got) / 48000:.1f}s of voice -> {out}")


asyncio.run(main())
