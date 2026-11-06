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
# 25 speech tokens per second: up to 8 s to finish a reply's last words (0 turns the fix off, for comparison)
FINAL_SPEECH_TOKENS = int(os.environ.get("DUPLEX_FINAL_SPEECH_TOKENS", "200"))
# Up to this many speech tokens per normal step (the model's own is exactly 26 = ~1 s); 0 = the model's default
STEP_SPEECH_TOKENS = 0  # tried 40 (up to 1.6 s per step): no fewer cut endings, more babble, and slower
# Speech tokens per text token, to work out what the last step still owes (measured on this model: see log lines
# "turn speech: ..." from replies that ended naturally)
# 12 replies on MiniCPM-o 4.5 (NF4): complete ones 6.6-7.8, the one that lost its ending 5.7. 6.5 only pushes a
# reply that is clearly behind. 0 = just let the last step run until the model's own end-of-speech.
SPEECH_PER_TEXT_TOKEN = float(os.environ.get("DUPLEX_SPEECH_PER_TEXT_TOKEN", "6.5"))
NUDGE_CHUNKS, NUDGE_SCALE, RETRY_AFTER = 3, 0.5, 3


class Duplex:
    def __init__(self, model_dir: str, quant: str = "none", max_gpu_gib: float = 0.0):
        import torch
        from transformers import AutoModel

        if max_gpu_gib:
            # PyTorch keeps freed memory cached; on a shared card that starves Whisper. Cap what this process may hold.
            total = torch.cuda.get_device_properties(0).total_memory
            torch.cuda.set_per_process_memory_fraction(min(1.0, max_gpu_gib * 2**30 / total))

        t = time.time()
        kw: dict = {}
        if quant in ("nf4", "nf4-all"):
            # 4-bit only for the language model (Qwen3-8B, most of the weights). Hearing (apm), speech (tts) and the
            # projections stay bf16: they are small and decide how it sounds.
            from transformers import BitsAndBytesConfig

            kw = {"device_map": "cuda", "quantization_config": BitsAndBytesConfig(
                load_in_4bit=True, bnb_4bit_quant_type="nf4", bnb_4bit_compute_dtype=torch.bfloat16,
                bnb_4bit_use_double_quant=True,
                llm_int8_skip_modules=["apm", "tts", "audio_projection_layer", "audio_avg_pooler", "vpm",
                                       "resampler"] + (["lm_head"] if quant == "nf4" else []))}
        model = AutoModel.from_pretrained(model_dir, trust_remote_code=True, attn_implementation="sdpa",
                                          torch_dtype=torch.bfloat16, init_vision=False, local_files_only=True, **kw)
        model = model.eval() if quant != "none" else model.eval().cuda()
        self.duplex = model.as_duplex()
        self._finish_speech_at_end_of_turn()
        log.info("GPU memory after load: %.1f GiB (quant=%s)", torch.cuda.memory_allocated() / 2**30, quant)
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
        self.spoken = 0
        self.costs: list = []

    def inject(self, text: str, retry: bool = True):
        turn = "<|im_start|>system\n" + text.strip() + "<|im_end|>"
        # Queue, don't replace: a hint arriving right after a RESULT must not erase the RESULT.
        self.pending = (self.pending or "") + turn
        if retry:  # only results are re-sent if the voice stays quiet; hints never displace them
            self.last_inject, self.retries = turn, 0

    def step(self, chunk: np.ndarray) -> dict:
        t = time.time()
        if self.pending:
            self.duplex.streaming_prefill(audio_waveform=chunk, text_list=[self.pending])
            self.pending, self.nudge, self.spoke_since, self.spoken = None, NUDGE_CHUNKS, False, 0
        else:
            self.duplex.streaming_prefill(audio_waveform=chunk)
        r = self.duplex.streaming_generate(prompt_wav_path=self.ref, max_new_speak_tokens_per_chunk=20,
                                           decode_mode="sampling",
                                           listen_prob_scale=NUDGE_SCALE if self.nudge > 0 else 1.0)
        listen = bool(r.get("is_listen"))
        if not listen and (r.get("text") or "").strip():
            self.spoke_since = True
            self.spoken += 1
            if self.spoken >= 2:
                # It has been talking since the last result arrived: that result is delivered. Never re-send it
                # later (a hint for the NEXT question once made it repeat the previous answer).
                self.last_inject = None
        if self.nudge > 0:
            self.nudge -= 1
            # Silent through the whole nudge window: hand the result over once more.
            if self.nudge == 0 and not self.spoke_since and self.retries < 1 and self.last_inject:
                self.retries += 1
                self.pending = self.last_inject
        audio = None
        if not listen and r.get("audio_waveform") is not None:
            audio = (np.clip(np.asarray(r["audio_waveform"], dtype=np.float32), -1, 1) * 32767).astype("<i2").tobytes()
        cost = round(time.time() - t, 3)
        self.costs.append(cost)
        return {"listen": listen, "text": r.get("text") or "", "eot": bool(r.get("end_of_turn")),
                "cost": cost, "audio": audio}

    def _finish_speech_at_end_of_turn(self) -> None:
        """MiniCPM-o's speech lags its text: each 1 s step writes up to 20 text tokens but voices exactly ~1 s
        (26 speech tokens). At the end of a turn it makes one last capped step and resets, so whatever it hasn't voiced
        yet (often the last word or two, sometimes a sentence) is dropped; it also tends to stop at the end-of-text
        even when behind. So on that last step: allow up to FINAL_SPEECH_TOKENS, and if the reply is clearly behind
        (fewer than SPEECH_PER_TEXT_TOKEN speech tokens per text token so far), require most of what it owes.
        Not on a one-step reply: there it has no backlog, and given room it babbles."""
        d = self.duplex
        tts = getattr(getattr(d, "model", None), "tts", None)
        if FINAL_SPEECH_TOKENS <= 0:
            log.info("end-of-turn speech fix is off")
            return
        if tts is None or not hasattr(tts, "generate_chunk") or not hasattr(d, "current_turn_ended"):
            log.warning("could not patch end-of-turn speech (model code changed?); replies may lose their last words")
            return
        generate = tts.generate_chunk
        convert = d._convert_results_to_tts_input
        turn = {"text": 0, "speech": 0, "steps": 0}

        def convert_and_count(results):
            turn["text"] += len(results)  # text tokens this step (the TTS is conditioned on each)
            return convert(results)

        def generate_rest(*args, **kwargs):
            start = int(kwargs.get("text_start_pos") or 0) == 0
            if start:
                turn["speech"], turn["steps"] = 0, 0
                turn["text"] = turn.get("pending", 0)
            last = bool(d.current_turn_ended) and not start
            owed = 0
            if last:
                # It tends to stop at the end-of-text even when it hasn't voiced it all: make it voice what it owes.
                if SPEECH_PER_TEXT_TOKEN > 0:
                    owed = int(SPEECH_PER_TEXT_TOKEN * turn["text"]) - turn["speech"]
                    kwargs["min_new_tokens"] = max(0, min(int(owed * 0.85), FINAL_SPEECH_TOKENS - 50))
                    kwargs["max_new_token"] = max(26, min(FINAL_SPEECH_TOKENS, kwargs["min_new_tokens"] + 50))
                else:
                    kwargs["max_new_token"] = max(int(kwargs.get("max_new_token") or 0), FINAL_SPEECH_TOKENS)
            tokens, kv = generate(*args, **kwargs)
            turn["speech"] += int(tokens.shape[1])
            turn["steps"] += 1
            if last or start and d.current_turn_ended:
                log.info("turn speech: %d text tokens, %d speech tokens in %d steps (%.1f per text token)%s",
                         turn["text"], turn["speech"], turn["steps"], turn["speech"] / max(1, turn["text"]),
                         f"; last step owed {owed}, made {int(tokens.shape[1])}" if last else "")
            return tokens, kv

        # convert runs before generate in each step; count the step's text first, then generate uses it
        def convert_hook(results):
            if int(getattr(d, "tts_text_start_pos", 1) or 0) == 0:
                turn["pending"] = len(results)
                return convert(results)
            return convert_and_count(results)

        d._convert_results_to_tts_input = convert_hook
        tts.generate_chunk = generate_rest

    def stats(self) -> str:
        import torch

        c = sorted(self.costs) or [0.0]
        late = sum(1 for x in c if x > 1.0)
        return (f"{len(c)} chunks, per 1 s of audio: median {c[len(c) // 2]:.2f}s, p95 {c[int(len(c) * 0.95)]:.2f}s, "
                f"max {c[-1]:.2f}s, slower than real time {late}; peak GPU {torch.cuda.max_memory_allocated() / 2**30:.1f} GiB")


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
            if started:
                log.info("session done: %s", duplex.stats())
                import torch

                torch.cuda.empty_cache()
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
    ap.add_argument("--quant", choices=["none", "nf4", "nf4-all"], default="none",
                    help="nf4: 4-bit language model (bitsandbytes), for 16 GB cards; nf4-all also quantizes the output "
                         "layer (0.9 GB less, but slower than real time on an RTX 5080)")
    ap.add_argument("--max-gpu-gib", type=float, default=0.0,
                    help="cap this process's GPU memory so Whisper can share the card (13 on a 16 GB card)")
    args = ap.parse_args()
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s: %(message)s")
    tf = Path(args.token_file)
    if not tf.exists():
        tf.write_text(secrets.token_urlsafe(24))
        tf.chmod(0o600)
    web.run_app(make_app(Duplex(args.model, args.quant, args.max_gpu_gib), tf.read_text().strip()), host=args.host, port=args.port, print=None)


if __name__ == "__main__":
    main()
