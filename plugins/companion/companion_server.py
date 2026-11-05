"""Phone link server for the companion plugin.

Architecture (one phone conversation):

    phone app  --WS (PCM16 24 kHz + JSON control)-->  this server  --WS-->  OpenAI Realtime
                                                           |
                                                           +--HTTP-->  Hermes API server (/v1/runs)

* The OpenAI session is minted with the user's ChatGPT/Codex sign-in through
  Hermes's ``credentialed_provider_request``; this module never sees that token,
  only the short-lived client secret the mint returns.
* The realtime model is the ears and mouth. Anything that needs tools, memory,
  files or current facts goes to Hermes through the ``ask_hermes`` function.
"""

from __future__ import annotations

import asyncio
import base64
import json
import logging
import os
import secrets
import time
import wave
from datetime import datetime
from pathlib import Path
from typing import Any, Optional

log = logging.getLogger("companion")

MODEL = os.environ.get("COMPANION_MODEL", "gpt-realtime-2.1")
VOICE = os.environ.get("COMPANION_VOICE", "cedar")
CLIENT_SECRETS_URL = "https://api.openai.com/v1/realtime/client_secrets"
REALTIME_WS_URL = "wss://api.openai.com/v1/realtime"
SAMPLE_RATE = 24000
API_SERVER_URL = os.environ.get("COMPANION_API_SERVER_URL", "http://127.0.0.1:6789").rstrip("/")
HERMES_TIMEOUT_S = float(os.environ.get("COMPANION_HERMES_TIMEOUT_S", "240"))

ASK_HERMES_TOOL = {
    "type": "function",
    "name": "ask_hermes",
    "description": (
        "Hand a request to Hermes, the user's full agent. Use it for anything that needs tools, "
        "files, the web, memory of past conversations, the calendar, smart-home devices, code, "
        "or facts you are not sure of. Write the request as a complete, self-contained instruction. "
        "It can take from a few seconds to a few minutes."
    ),
    "parameters": {
        "type": "object",
        "properties": {
            "request": {"type": "string", "description": "What Hermes should do or answer, with all needed context."}
        },
        "required": ["request"],
    },
}


# --------------------------------------------------------------------------- storage


def _data_dir() -> Path:
    try:
        from plugins.plugin_storage import plugin_data_dir

        path = Path(plugin_data_dir("companion"))
    except Exception:  # pragma: no cover - outside Hermes
        path = Path.home() / ".hermes" / "plugin-data" / "companion"
    path.mkdir(parents=True, exist_ok=True)
    return path


def pair_token() -> str:
    path = _data_dir() / "pair_token"
    if path.exists():
        token = path.read_text().strip()
        if token:
            return token
    token = secrets.token_urlsafe(24)
    path.write_text(token)
    os.chmod(path, 0o600)
    return token


def _apk_path() -> Optional[Path]:
    path = _data_dir() / "app.apk"
    return path if path.is_file() else None


# --------------------------------------------------------------------------- persona


def _read_head(path: Path, limit: int) -> str:
    try:
        return path.read_text(encoding="utf-8", errors="replace").strip()[:limit]
    except OSError:
        return ""


def build_instructions() -> str:
    try:
        from hermes_constants import get_hermes_home

        home = Path(get_hermes_home())
    except Exception:  # pragma: no cover
        home = Path.home() / ".hermes"
    soul = _read_head(home / "SOUL.md", 3500)
    user = ""
    for candidate in (home / "memories" / "USER.md", home / "USER.md"):
        user = _read_head(candidate, 1500)
        if user:
            break
    now = datetime.now().astimezone().strftime("%A %B %d, %Y, %I:%M %p %Z")
    parts = [
        "You are Hermes, the user's personal AI, talking with them out loud through a phone app.",
        f"It is now {now}.",
        "This is a spoken conversation: answer in one to three short, natural sentences unless they ask for more. "
        "Be warm and direct. No lists, no markdown, no reading out URLs.",
        "For anything that needs tools, files, the web, past conversations, the calendar, smart-home devices, code, "
        "or facts you are not sure of, call ask_hermes with a complete, self-contained request. Before calling it, say "
        "a very short line like 'One sec.' When the answer comes back, tell them the gist conversationally. Never make "
        "up results. If Hermes fails, say so plainly.",
        "If they interrupt you, stop and listen.",
    ]
    if soul:
        parts.append("Your personality and values (from your SOUL file):\n" + soul)
    if user:
        parts.append("What you know about the user:\n" + user)
    return "\n\n".join(parts)


def turn_detection() -> dict:
    """Server VAD by default: replies ~1 s after you stop. semantic_vad waits up to 4 s on pauses."""
    mode = os.environ.get("COMPANION_TURN_DETECTION", "server_vad").strip().lower()
    if mode == "semantic_vad":
        return {
            "type": "semantic_vad",
            "eagerness": os.environ.get("COMPANION_EAGERNESS", "auto").strip().lower(),
            "create_response": True,
            "interrupt_response": True,
        }
    return {
        "type": "server_vad",
        "threshold": float(os.environ.get("COMPANION_VAD_THRESHOLD", "0.5")),
        "prefix_padding_ms": 300,
        "silence_duration_ms": int(os.environ.get("COMPANION_SILENCE_MS", "550")),
        "create_response": True,
        "interrupt_response": True,
    }


def build_session() -> dict:
    return {
        "type": "realtime",
        "model": MODEL,
        "instructions": build_instructions(),
        "audio": {
            "input": {
                "format": {"type": "audio/pcm", "rate": SAMPLE_RATE},
                "noise_reduction": {"type": "near_field"},
                "turn_detection": turn_detection(),
                "transcription": {"model": "gpt-4o-mini-transcribe"},
            },
            "output": {"format": {"type": "audio/pcm", "rate": SAMPLE_RATE}, "voice": VOICE},
        },
        "tools": [ASK_HERMES_TOOL],
        "tool_choice": "auto",
    }


# --------------------------------------------------------------------------- mint


class CompanionError(RuntimeError):
    pass


def mint_client_secret() -> str:
    """Mint a short-lived OpenAI Realtime secret with the user's Codex sign-in.

    Called from this plugin's own frame (credentialed_provider_request identifies
    the caller by stack location), so always invoke it directly or through
    asyncio.to_thread(mint_client_secret), never via a bare executor hand-off of
    the core helper.
    """
    from hermes_cli.plugin_provider_requests import ProviderNotSignedIn, credentialed_provider_request

    try:
        response = credentialed_provider_request(
            "openai-codex", "POST", CLIENT_SECRETS_URL, json={"session": build_session()}, timeout=30.0
        )
    except ProviderNotSignedIn as exc:
        raise CompanionError(str(exc)) from None
    if response.status != 200:
        raise CompanionError(f"OpenAI refused the voice session ({response.status}): {response.text[:300]}")
    payload = response.json()
    value = payload.get("value")
    if not value and isinstance(payload.get("client_secret"), dict):
        value = payload["client_secret"].get("value")
    if not isinstance(value, str) or not value:
        raise CompanionError("OpenAI returned no session secret")
    return value


# --------------------------------------------------------------------------- Hermes bridge


def _hermes_home() -> Path:
    try:
        from hermes_constants import get_hermes_home

        return Path(get_hermes_home())
    except Exception:  # pragma: no cover
        return Path.home() / ".hermes"


def _api_server_key() -> str:
    """The gateway API key, read through Hermes (process scope first, then the profile's own secrets)."""
    names = ("COMPANION_API_SERVER_KEY", "API_SERVER_KEY")
    try:
        from agent.secret_scope import get_secret

        for name in names:
            value = (get_secret(name) or "").strip()
            if value:
                return value
    except Exception:  # noqa: BLE001 - fall through to the profile scope
        pass
    try:
        from agent.secret_scope import build_profile_secret_scope

        scope = build_profile_secret_scope(_hermes_home())
        for name in names:
            value = (scope.get(name) or "").strip()
            if value:
                return value
    except Exception:  # noqa: BLE001
        pass
    return ""


def _output_text(output: Any) -> str:
    if isinstance(output, str):
        return output
    if isinstance(output, dict):
        for key in ("text", "content", "final_response", "message"):
            if isinstance(output.get(key), str):
                return output[key]
        return json.dumps(output)[:4000]
    if isinstance(output, list):
        return "\n".join(_output_text(item) for item in output)
    return "" if output is None else str(output)


class HermesBridge:
    """Runs requests on the Hermes API server and keeps one Hermes conversation per phone call."""

    def __init__(self) -> None:
        self.session_id: Optional[str] = None

    async def ask(self, request: str) -> str:
        return await asyncio.to_thread(self._ask_sync, request)

    def _ask_sync(self, request: str) -> str:
        import httpx

        headers = {"Content-Type": "application/json", "X-Hermes-Session-Key": "companion"}
        key = _api_server_key()
        if key:
            headers["Authorization"] = f"Bearer {key}"
        body: dict = {"input": request}
        if self.session_id:
            body["session_id"] = self.session_id
        try:
            started = httpx.post(f"{API_SERVER_URL}/v1/runs", json=body, headers=headers, timeout=30)
        except httpx.HTTPError as exc:
            return f"I couldn't reach Hermes ({type(exc).__name__})."
        if started.status_code // 100 != 2:
            return f"Hermes refused the request ({started.status_code}): {started.text[:200]}"
        run_id = (started.json() or {}).get("run_id")
        if not run_id:
            return "Hermes accepted the request but returned no run id."
        deadline = time.monotonic() + HERMES_TIMEOUT_S
        while time.monotonic() < deadline:
            time.sleep(1.0)
            try:
                run = httpx.get(f"{API_SERVER_URL}/v1/runs/{run_id}", headers=headers, timeout=30).json()
            except (httpx.HTTPError, ValueError):
                continue
            if isinstance(run.get("session_id"), str):
                self.session_id = run["session_id"]
            status = str(run.get("status") or "")
            if status == "completed":
                return _output_text(run.get("output")).strip() or "Hermes finished but said nothing."
            if status in ("failed", "cancelled"):
                return f"Hermes could not finish ({status}): {_output_text(run.get('error'))[:300]}"
        return "Hermes is still working on that; it's taking longer than I can wait on this call."


# --------------------------------------------------------------------------- phone session

_TURN_EVENTS = {
    "session.created", "session.updated",
    "input_audio_buffer.speech_started", "input_audio_buffer.speech_stopped",
    "input_audio_buffer.committed", "input_audio_buffer.timeout_triggered",
    "response.created", "response.done",
    "conversation.item.input_audio_transcription.completed",
    "conversation.item.input_audio_transcription.failed",
}


class PhoneSession:
    def __init__(self, ws) -> None:
        self.ws = ws
        self.oa = None
        self.bridge = HermesBridge()
        self.tasks: set[asyncio.Task] = set()
        self.speaking = False
        self.owed_response = False
        self.active_response = False

    async def send_json(self, obj: dict) -> None:
        if not self.ws.closed:
            await self.ws.send_str(json.dumps(obj))

    async def state(self, value: str, detail: str = "") -> None:
        msg = {"type": "state", "state": value}
        if detail:
            msg["detail"] = detail
        await self.send_json(msg)

    async def oa_send(self, obj: dict) -> None:
        if self.oa is not None and not self.oa.closed:
            await self.oa.send_str(json.dumps(obj))

    async def run(self) -> None:
        import aiohttp

        await self.state("connecting")
        try:
            secret = await asyncio.to_thread(mint_client_secret)
        except Exception as exc:  # noqa: BLE001 - surfaced to the phone
            log.warning("mint failed: %s", exc)
            await self.send_json({"type": "error", "message": f"Couldn't start the voice session: {exc}"})
            return
        try:
            async with aiohttp.ClientSession(timeout=aiohttp.ClientTimeout(total=None, sock_connect=20)) as http:
                async with http.ws_connect(
                    f"{REALTIME_WS_URL}?model={MODEL}",
                    headers={"Authorization": f"Bearer {secret}"},
                    heartbeat=20.0,
                    max_msg_size=0,
                ) as oa:
                    self.oa = oa
                    await self.state("listening")
                    log.info("phone call connected to OpenAI Realtime (%s, %s)", MODEL, VOICE)
                    up = asyncio.create_task(self.phone_to_openai())
                    down = asyncio.create_task(self.openai_to_phone())
                    _done, pending = await asyncio.wait({up, down}, return_when=asyncio.FIRST_COMPLETED)
                    for task in pending:
                        task.cancel()
        except Exception as exc:  # noqa: BLE001
            log.exception("realtime session failed")
            await self.send_json({"type": "error", "message": f"Voice session failed: {exc}"})
        finally:
            for task in list(self.tasks):
                task.cancel()
            log.info("phone call ended")

    async def phone_to_openai(self) -> None:
        from aiohttp import WSMsgType

        async for msg in self.ws:
            if msg.type == WSMsgType.BINARY:
                if msg.data:
                    await self.oa_send(
                        {"type": "input_audio_buffer.append", "audio": base64.b64encode(msg.data).decode("ascii")}
                    )
            elif msg.type == WSMsgType.TEXT:
                try:
                    data = json.loads(msg.data)
                except ValueError:
                    continue
                kind = data.get("type")
                if kind == "stop":
                    return
                if kind == "text" and data.get("text"):
                    await self.oa_send(
                        {
                            "type": "conversation.item.create",
                            "item": {"type": "message", "role": "user",
                                     "content": [{"type": "input_text", "text": str(data["text"])}]},
                        }
                    )
                    await self.request_response()
            elif msg.type in (WSMsgType.CLOSE, WSMsgType.CLOSING, WSMsgType.CLOSED, WSMsgType.ERROR):
                return

    async def request_response(self) -> None:
        if self.active_response:
            self.owed_response = True
        else:
            self.active_response = True
            await self.oa_send({"type": "response.create"})

    async def openai_to_phone(self) -> None:
        from aiohttp import WSMsgType

        oa = self.oa
        if oa is None:
            return
        async for msg in oa:
            if msg.type != WSMsgType.TEXT:
                if msg.type in (WSMsgType.CLOSE, WSMsgType.CLOSED, WSMsgType.ERROR):
                    return
                continue
            event = json.loads(msg.data)
            kind = event.get("type", "")
            if kind in _TURN_EVENTS:
                extra = (event.get("response") or {}).get("status", "") if kind == "response.done" else ""
                log.info("realtime: %s %s", kind, extra)
            if kind in ("response.output_audio.delta", "response.audio.delta"):
                if not self.speaking:
                    self.speaking = True
                    await self.state("speaking")
                if not self.ws.closed:
                    await self.ws.send_bytes(base64.b64decode(event.get("delta", "")))
            elif kind == "response.created":
                self.active_response = True
            elif kind == "input_audio_buffer.speech_started":
                await self.send_json({"type": "clear"})
                if self.speaking:
                    self.speaking = False
                await self.state("listening")
            elif kind == "conversation.item.input_audio_transcription.completed":
                await self.send_json({"type": "transcript", "role": "user", "text": event.get("transcript", "")})
            elif kind in ("response.output_audio_transcript.done", "response.audio_transcript.done"):
                await self.send_json({"type": "transcript", "role": "assistant", "text": event.get("transcript", "")})
            elif kind == "response.output_item.done":
                item = event.get("item") or {}
                if item.get("type") == "function_call":
                    task = asyncio.create_task(self.run_tool(item))
                    self.tasks.add(task)
                    task.add_done_callback(self.tasks.discard)
            elif kind == "response.done":
                self.active_response = False
                self.speaking = False
                if self.owed_response:
                    self.owed_response = False
                    await self.request_response()
                elif not self.tasks:
                    await self.state("listening")
            elif kind == "error":
                err = event.get("error") or {}
                code = str(err.get("code") or "")
                log.warning("realtime error: %s", err)
                if code == "conversation_already_has_active_response":
                    self.owed_response = True
                elif code not in ("response_cancel_not_active",):
                    await self.send_json({"type": "error", "message": str(err.get("message") or "voice error")})

    async def run_tool(self, item: dict) -> None:
        name = item.get("name")
        call_id = item.get("call_id")
        try:
            args = json.loads(item.get("arguments") or "{}")
        except ValueError:
            args = {}
        if name == "ask_hermes":
            request = str(args.get("request") or "").strip()
            await self.state("thinking", request[:120])
            await self.send_json({"type": "transcript", "role": "tool", "text": f"Asking Hermes: {request}"})
            log.info("ask_hermes: %s", request[:200])
            result = await self.bridge.ask(request)
            log.info("ask_hermes result: %s", result[:200])
        else:
            result = f"Unknown function {name}."
        await self.oa_send(
            {"type": "conversation.item.create",
             "item": {"type": "function_call_output", "call_id": call_id, "output": result[:8000]}}
        )
        await self.request_response()


# --------------------------------------------------------------------------- HTTP server

PAIR_HTML = """<!doctype html><html><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>Hermes Companion</title>
<style>
body{font-family:system-ui,sans-serif;background:#0b0b10;color:#eee;padding:24px;max-width:520px;margin:auto}
h1{font-weight:600} p{color:#aab;line-height:1.5}
a.btn{display:block;padding:18px;margin:14px 0 24px;border-radius:16px;background:#5b5bd6;color:#fff;
text-align:center;text-decoration:none;font-size:20px}
</style></head><body>
<h1>Hermes Companion</h1>
<p><b>1.</b> Install the app. If Android asks, allow installs from Chrome.</p>
<a class="btn" href="app.apk">Download the app</a>
<p><b>2.</b> After it's installed, pair it with your Hermes.</p>
<a class="btn" id="pair" href="#">Pair this phone</a>
<p>Then tap the big button in the app and talk.</p>
<script>
const t = "__TOKEN__";
const base = location.href.split('?')[0].replace(/[^/]*$/, '');
const ws = base.replace(/^http/, 'ws') + 'ws';
const q = 'url=' + encodeURIComponent(ws) + '&t=' + encodeURIComponent(t);
document.getElementById('pair').href =
  'intent://pair?' + q + '#Intent;scheme=hermescompanion;package=com.chillygeek.hermescompanion;end';
</script></body></html>"""


def make_app():
    from aiohttp import web

    token = pair_token()

    async def health(_request):
        return web.json_response({"ok": True, "model": MODEL, "voice": VOICE, "apk": bool(_apk_path())})

    async def index(request):
        if not secrets.compare_digest(request.query.get("t", ""), token):
            return web.Response(status=403, text="Open the pairing link from `hermes companion pair`.")
        return web.Response(text=PAIR_HTML.replace("__TOKEN__", token), content_type="text/html")

    async def apk(_request):
        path = _apk_path()
        if path is None:
            return web.Response(status=404, text="No app build has been published yet.")
        return web.FileResponse(
            path,
            headers={
                "Content-Type": "application/vnd.android.package-archive",
                "Content-Disposition": 'attachment; filename="hermes-companion.apk"',
            },
        )

    async def ws_handler(request):
        if not secrets.compare_digest(request.query.get("t", ""), token):
            return web.Response(status=403)
        ws = web.WebSocketResponse(heartbeat=20.0, max_msg_size=8 * 1024 * 1024)
        await ws.prepare(request)
        log.info("phone connected from %s", request.headers.get("X-Forwarded-For") or request.remote)
        await PhoneSession(ws).run()
        await ws.close()
        return ws

    app = web.Application()
    for prefix in ("", "/companion"):
        app.router.add_get(prefix + "/health", health)
        app.router.add_get(prefix + "/", index)
        app.router.add_get(prefix + "/app.apk", apk)
        app.router.add_get(prefix + "/ws", ws_handler)
    return app


def _setup_logging() -> None:
    # Hermes configures the root logger before plugin commands run, so attach our own handler.
    handler = logging.FileHandler(_data_dir() / "server.log")
    handler.setFormatter(logging.Formatter("%(asctime)s %(levelname)s %(name)s: %(message)s"))
    log.addHandler(handler)
    log.setLevel(logging.INFO)


def cli_serve(host: str, port: int) -> int:
    from aiohttp import web

    _setup_logging()
    log.info("companion server on http://%s:%d (model %s, voice %s)", host, port, MODEL, VOICE)
    web.run_app(make_app(), host=host, port=port, print=None)
    return 0


def _tailnet_name() -> str:
    import subprocess

    try:
        out = subprocess.run(["tailscale", "status", "--json"], capture_output=True, text=True, timeout=10)
        return (json.loads(out.stdout).get("Self", {}).get("DNSName") or "").rstrip(".")
    except Exception:  # noqa: BLE001
        return ""


def cli_pair() -> int:
    host = _tailnet_name()
    token = pair_token()
    if host:
        print(f"https://{host}/companion/?t={token}")
    else:
        print(f"Pairing token: {token}  (serve the companion server over HTTPS and open /?t=<token>)")
    return 0


def cli_selftest() -> int:
    """Mint a session, ask for one spoken sentence, save it as a WAV. No phone needed."""
    import aiohttp

    async def main() -> int:
        started = time.monotonic()
        secret = await asyncio.to_thread(mint_client_secret)
        print(f"mint: ok ({time.monotonic() - started:.1f}s)")
        pcm = bytearray()
        transcript = ""
        first_audio = None
        async with aiohttp.ClientSession(timeout=aiohttp.ClientTimeout(total=None, sock_connect=20)) as http:
            async with http.ws_connect(
                f"{REALTIME_WS_URL}?model={MODEL}", headers={"Authorization": f"Bearer {secret}"},
                heartbeat=20.0, max_msg_size=0,
            ) as oa:
                print(f"socket: connected ({time.monotonic() - started:.1f}s)")
                await oa.send_str(json.dumps({
                    "type": "conversation.item.create",
                    "item": {"type": "message", "role": "user", "content": [
                        {"type": "input_text", "text": "This is a connection test. Greet me in one short sentence."}]},
                }))
                asked = time.monotonic()
                await oa.send_str(json.dumps({"type": "response.create"}))
                async for msg in oa:
                    event = json.loads(msg.data)
                    kind = event.get("type", "")
                    if kind in ("response.output_audio.delta", "response.audio.delta"):
                        if first_audio is None:
                            first_audio = time.monotonic() - asked
                        pcm += base64.b64decode(event.get("delta", ""))
                    elif kind in ("response.output_audio_transcript.done", "response.audio_transcript.done"):
                        transcript = event.get("transcript", "")
                    elif kind == "error":
                        print("error:", event.get("error"))
                        return 1
                    elif kind == "response.done":
                        break
        out = _data_dir() / "selftest.wav"
        with wave.open(str(out), "wb") as wav:
            wav.setnchannels(1)
            wav.setsampwidth(2)
            wav.setframerate(SAMPLE_RATE)
            wav.writeframes(bytes(pcm))
        print(f"reply: {transcript!r}")
        print(f"first audio after {first_audio or 0:.2f}s; {len(pcm) / (2 * SAMPLE_RATE):.1f}s of speech -> {out}")
        return 0

    try:
        return asyncio.run(main())
    except CompanionError as exc:
        print(f"selftest failed: {exc}")
        return 1
