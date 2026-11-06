"""Speech-to-text for the local voice: a small OpenAI-compatible Whisper server (faster-whisper).

    POST /v1/audio/transcriptions   multipart: file (WAV), model (ignored), language (optional)  -> {"text": ...}
    GET  /health

Any OpenAI-compatible transcription server works with Vessel; this one exists so the local voice needs nothing
else. If a token file is given, every request must carry `Authorization: Bearer <token>` (the plugin sends the
duplex worker's token), so it can listen on a private network.

    python stt_server.py --port 8798 [--host 127.0.0.1] [--model turbo] [--compute-type int8_float16] [--token-file F]
"""
import argparse
import asyncio
import io
import logging
import secrets
import time
from pathlib import Path

from aiohttp import web

log = logging.getLogger("stt")


def make_app(model, token: str, beam: int) -> web.Application:
    lock = asyncio.Lock()  # one transcription at a time on the GPU

    @web.middleware
    async def auth(request, handler):
        if token and request.path != "/health":
            got = request.headers.get("Authorization", "")
            if not secrets.compare_digest(got, f"Bearer {token}"):
                return web.json_response({"error": "unauthorized"}, status=401)
        return await handler(request)

    async def health(_request):
        return web.json_response({"ok": True})

    async def transcribe(request):
        form = await request.post()
        upload = form.get("file")
        if upload is None or not hasattr(upload, "file"):
            return web.json_response({"error": "missing file"}, status=400)
        audio = upload.file.read()
        if len(audio) > 25 * 1024 * 1024:
            return web.json_response({"error": "file too large"}, status=413)
        language = str(form.get("language") or "") or None
        t = time.time()

        def run():
            segments, _info = model.transcribe(io.BytesIO(audio), language=language, beam_size=beam, vad_filter=True)
            return " ".join(s.text.strip() for s in segments).strip()

        async with lock:
            text = await asyncio.get_running_loop().run_in_executor(None, run)
        log.info("transcribed %d bytes in %.2fs", len(audio), time.time() - t)
        return web.json_response({"text": text})

    app = web.Application(middlewares=[auth], client_max_size=26 * 1024 * 1024)
    app.router.add_get("/health", health)
    app.router.add_post("/v1/audio/transcriptions", transcribe)
    return app


def main() -> None:
    ap = argparse.ArgumentParser(description=(__doc__ or "").split("\n")[0])
    ap.add_argument("--host", default="127.0.0.1")
    ap.add_argument("--port", type=int, default=8798)
    ap.add_argument("--model", default="turbo", help="faster-whisper model size or path (default: turbo)")
    ap.add_argument("--device", default="cuda")
    ap.add_argument("--compute-type", default="int8_float16", help="int8_float16 halves GPU memory (~1.3 GB)")
    ap.add_argument("--beam-size", type=int, default=1)
    ap.add_argument("--token-file", default="", help="require this bearer token on every request")
    args = ap.parse_args()
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s: %(message)s")
    token = Path(args.token_file).read_text().strip() if args.token_file else ""
    from faster_whisper import WhisperModel

    model = WhisperModel(args.model, device=args.device, compute_type=args.compute_type)
    log.info("whisper %s on %s (%s) ready on %s:%d%s", args.model, args.device, args.compute_type, args.host,
             args.port, " (token required)" if token else "")
    web.run_app(make_app(model, token, args.beam_size), host=args.host, port=args.port, print=None)


if __name__ == "__main__":
    main()
