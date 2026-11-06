"""Local full-duplex voice worker: MiniCPM-o 4.5 behind a small WebSocket, for Vessel's local engine.

Runs on the machine with the GPU (tested: RTX 3090, bf16, ~21 GB). One conversation at a time.

Protocol (ws://HOST:PORT/ws?t=TOKEN):
  client -> worker  JSON {"type": "start", "system": "...", "voice": "<ref wav name>"}   (first message)
                    binary: PCM16 little-endian mono 16 kHz, any frame size (the user's mic)
                    JSON {"type": "inject", "text": "RESULT: ...", "retry": true}   (a tool result for the model to
                    speak; retry=false for hints that must not be repeated)
  worker -> client  JSON {"type": "chunk", "listen": bool, "text": str, "eot": bool, "cost": float}
                    binary: PCM16 little-endian mono 24 kHz (the model's voice; only while it speaks)
                    JSON {"type": "ready"} after start, {"type": "error", "message": ...}

The model hears and speaks in 1 s units. An injected result is prefixed as a system turn and the model is nudged
toward speaking for a few seconds; if it stays silent it is re-injected once (measured: 6/8 spoken first try).

Setup: tools/local_voice/README.md. The model code is loaded from the downloaded snapshot (pinned revision),
never fetched at run time.
"""
import argparse
import asyncio
import json
import logging
import os
import secrets
import time
from pathlib import Path

import numpy as np
from aiohttp import WSMsgType, web

log = logging.getLogger("duplex")
SR_IN, SR_OUT = 16000, 24000
NUDGE_CHUNKS, NUDGE_SCALE, RETRY_AFTER = 3, 0.5, 3


class Duplex:
    def __init__(self, model_dir: str):
        import torch
        from transformers import AutoModel

        t = time.time()
        model = AutoModel.from_pretrained(model_dir, trust_remote_code=True, attn_implementation="sdpa",
                                          torch_dtype=torch.bfloat16, init_vision=False, local_files_only=True)
        self.duplex = model.eval().cuda().as_duplex()
        self.model_dir = Path(model_dir)
        log.info("model loaded in %.1fs", time.time() - t)

    def voice_path(self, name: str) -> Path:
        assets = self.model_dir / "assets"
        p = assets / Path(name or "HT_ref_audio.wav").name
        return p if p.is_file() else assets / "HT_ref_audio.wav"

    def start(self, system: str, voice: str):
        import librosa

        self.ref = str(self.voice_path(voice))
        ref_audio, _ = librosa.load(self.ref, sr=SR_IN, mono=True)
        self.duplex.prepare(prefix_system_prompt=system, ref_audio=ref_audio, prompt_wav_path=self.ref)
        self.pending, self.last_inject, self.nudge, self.retries, self.spoke_since = None, None, 0, 0, True

    def inject(self, text: str, retry: bool = True):
        self.pending = "<|im_start|>system\n" + text.strip() + "<|im_end|>"
        self.last_inject, self.retries = (self.pending if retry else None), 0

    def step(self, chunk: np.ndarray) -> dict:
        t = time.time()
        if self.pending:
            self.duplex.streaming_prefill(audio_waveform=chunk, text_list=[self.pending])
            self.pending, self.nudge, self.spoke_since = None, NUDGE_CHUNKS, False
        else:
            self.duplex.streaming_prefill(audio_waveform=chunk)
        r = self.duplex.streaming_generate(prompt_wav_path=self.ref, max_new_speak_tokens_per_chunk=20,
                                           decode_mode="sampling",
                                           listen_prob_scale=NUDGE_SCALE if self.nudge > 0 else 1.0)
        listen = bool(r.get("is_listen"))
        if not listen and (r.get("text") or "").strip():
            self.spoke_since = True
        if self.nudge > 0:
            self.nudge -= 1
            # Silent through the whole nudge window: hand the result over once more.
            if self.nudge == 0 and not self.spoke_since and self.retries < 1 and self.last_inject:
                self.retries += 1
                self.pending = self.last_inject
        audio = None
        if not listen and r.get("audio_waveform") is not None:
            audio = (np.clip(np.asarray(r["audio_waveform"], dtype=np.float32), -1, 1) * 32767).astype("<i2").tobytes()
        return {"listen": listen, "text": r.get("text") or "", "eot": bool(r.get("end_of_turn")),
                "cost": round(time.time() - t, 3), "audio": audio}


def make_app(duplex: Duplex, token: str) -> web.Application:
    busy = asyncio.Lock()

    async def health(_):
        return web.json_response({"ok": True, "busy": busy.locked()})

    async def ws_handler(request):
        if not secrets.compare_digest(request.query.get("t", ""), token):
            return web.Response(status=403)
        if busy.locked():
            return web.Response(status=409, text="A conversation is already running.")
        ws = web.WebSocketResponse(heartbeat=20.0, max_msg_size=8 * 1024 * 1024)
        await ws.prepare(request)
        async with busy:
            loop = asyncio.get_running_loop()
            buf = np.zeros(0, dtype=np.float32)
            started = False
            try:
                async for msg in ws:
                    if msg.type == WSMsgType.TEXT:
                        data = json.loads(msg.data)
                        if data.get("type") == "start":
                            await loop.run_in_executor(None, duplex.start, str(data.get("system") or ""),
                                                       str(data.get("voice") or ""))
                            started = True
                            await ws.send_json({"type": "ready"})
                        elif data.get("type") == "inject" and started:
                            duplex.inject(str(data.get("text") or "")[:1200], retry=data.get("retry", True) is not False)
                            log.info("inject: %s", str(data.get("text"))[:120])
                    elif msg.type == WSMsgType.BINARY and started:
                        pcm = np.frombuffer(msg.data, dtype="<i2").astype(np.float32) / 32768.0
                        buf = np.concatenate([buf, pcm])
                        while len(buf) >= SR_IN:
                            chunk, buf = buf[:SR_IN], buf[SR_IN:]
                            r = await loop.run_in_executor(None, duplex.step, chunk)
                            audio = r.pop("audio")
                            if audio:  # audio first, so the chunk that ends a turn arrives after its sound
                                await ws.send_bytes(audio)
                            await ws.send_json({"type": "chunk", **r})
                    elif msg.type in (WSMsgType.CLOSE, WSMsgType.ERROR):
                        break
            except Exception as exc:  # noqa: BLE001 - reported to the plugin
                log.exception("session failed")
                if not ws.closed:
                    await ws.send_json({"type": "error", "message": str(exc)[:300]})
        return ws

    app = web.Application()
    app.router.add_get("/health", health)
    app.router.add_get("/ws", ws_handler)
    return app


def main():
    ap = argparse.ArgumentParser(description=(__doc__ or "").split("\n")[0])
    ap.add_argument("--model", default=os.path.expanduser("~/minicpm-o/MiniCPM-o-4_5"))
    ap.add_argument("--host", default="0.0.0.0")
    ap.add_argument("--port", type=int, default=8797)
    ap.add_argument("--token-file", default=os.path.expanduser("~/minicpm-o/worker_token"))
    args = ap.parse_args()
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s: %(message)s")
    tf = Path(args.token_file)
    if not tf.exists():
        tf.write_text(secrets.token_urlsafe(24))
        tf.chmod(0o600)
    web.run_app(make_app(Duplex(args.model), tf.read_text().strip()), host=args.host, port=args.port, print=None)


if __name__ == "__main__":
    main()
