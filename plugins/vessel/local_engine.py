"""Vessel's local engine: a private, on-your-own-GPU alternative to OpenAI Realtime.

    phone mic --> this plugin --> MiniCPM-o 4.5 duplex worker (listens and speaks at once) --> phone speaker
                      |
                      +--> Whisper (your words as text) --> MiniCPM5-2B hand-off model ("does this need Hermes?")
                                                                  |
                                                                  +--> yes: a Hermes agent turn; its answer is
                                                                       handed to the voice model to say

The voice model cannot use tools and will happily invent answers, so it is told to say a short holding line for
anything personal or current, and the hand-off model (fine-tuned for exactly this decision, ~0.1 s) sends the
user's own words to Hermes. Selected on the phone as the voice called "Local".

Configuration (env vars, or <plugin data>/local.json with the same keys in lower case without the prefix):
  VESSEL_LOCAL_DUPLEX_URL    ws://gpu-host:8797/ws        the duplex worker (tools/local_voice/duplex_worker.py)
  VESSEL_LOCAL_DUPLEX_TOKEN  token for the worker (or <plugin data>/local_duplex_token)
  VESSEL_LOCAL_STT_URL       Whisper, OpenAI-compatible   default http://127.0.0.1:9001/v1/audio/transcriptions
  VESSEL_LOCAL_ROUTER_URL    hand-off model, OpenAI chat  default http://127.0.0.1:18081/v1/chat/completions
  VESSEL_LOCAL_VOICE         reference voice clip name on the worker (default HT_ref_audio.wav)
"""
from __future__ import annotations

import asyncio
import io
import json
import logging
import math
import os
import re
import sys
import wave
from array import array
from typing import Any, Optional

log = logging.getLogger("vessel")

SR_PHONE, SR_MODEL = 24000, 16000

# What the voice model is told. Tested on MiniCPM-o 4.5: the holding line comes out ~85% of the time and injected
# results are spoken back ~75% first try (the worker re-injects once if it stays silent).
VOICE_PROMPT = (
    "You are Hermes, the user's personal AI, talking with them out loud. Always speak English. "
    "Be warm, brief and natural. Never say you don't have access to something: your tools check it for you. "
    "You cannot see the user's files, calendar, devices, memory or the internet yourself, and you must never guess "
    "about them. When the user asks for something like that, only say a short holding line such as 'One sec, let me "
    "check.' and then wait quietly. The answer will arrive as a message starting with RESULT:. When it arrives, tell "
    "the user the answer naturally in one or two sentences."
)

# Exactly the system prompt and tool the hand-off model was fine-tuned with.
ROUTER_SYSTEM = (
    "You are Hermes, the user's personal AI, talking with them out loud through a phone app. "
    "This is a spoken conversation: answer in one to three short, natural sentences. No lists, no markdown. "
    "You have your full set of tools: files, the web, your memory of past conversations, the calendar, "
    "smart-home devices and code. For anything that needs them, or facts you are not sure of (anything current, "
    "personal to the user, or on their devices), call use_tools with a complete, self-contained request. "
    "The work is yours: never talk about Hermes in the third person. For ordinary chat, opinions, general knowledge "
    "and small talk, just answer; do not call tools."
)
ROUTER_TOOLS = [{"type": "function", "function": {
    "name": "use_tools",
    "description": ("Use your own tools: files, the web, your memory of past conversations, the calendar, smart-home "
                    "devices, code. Describe the task as one complete, self-contained instruction."),
    "parameters": {"type": "object", "properties": {
        "request": {"type": "string", "description": "The task, with all the context needed to do it."}},
        "required": ["request"]}}}]


_VS: Any = None  # set by vessel_server when it loads this file


def _vs():
    """The loaded vessel_server module (handed over at load time; imports are a fallback for direct use)."""
    if _VS is not None:
        return _VS
    try:
        from . import vessel_server as vs  # noqa: PLC0415
    except ImportError:
        import vessel_server as vs  # noqa: PLC0415
    return vs


def config() -> dict:
    _data_dir = _vs()._data_dir
    cfg: dict = {}
    path = _data_dir() / "local.json"
    if path.is_file():
        try:
            cfg = {k.lower(): v for k, v in json.loads(path.read_text()).items()}
        except ValueError:
            log.warning("local.json is not valid JSON; ignoring it")
    for key in ("duplex_url", "duplex_token", "stt_url", "router_url", "voice"):
        env = os.environ.get("VESSEL_LOCAL_" + key.upper())
        if env:
            cfg[key] = env
    if not cfg.get("duplex_token"):
        tf = _data_dir() / "local_duplex_token"
        if tf.is_file():
            cfg["duplex_token"] = tf.read_text().strip()
    cfg.setdefault("stt_url", "http://127.0.0.1:9001/v1/audio/transcriptions")
    cfg.setdefault("router_url", "http://127.0.0.1:18081/v1/chat/completions")
    cfg.setdefault("voice", "HT_ref_audio.wav")
    return cfg


def available() -> bool:
    return bool(config().get("duplex_url"))


def pcm(data: bytes) -> array:
    a = array("h")
    a.frombytes(data[: len(data) - len(data) % 2])
    if sys.byteorder != "little":
        a.byteswap()
    return a


def to_bytes(a: array) -> bytes:
    if sys.byteorder != "little":
        a = array("h", a)
        a.byteswap()
    return a.tobytes()


class Resampler:
    """24 kHz -> 16 kHz (2 out per 3 in, linear interpolation), carrying leftovers across phone frames.
    Pure Python on purpose: no numpy in the plugin's environment, and 24k samples a second is cheap."""

    def __init__(self):
        self.left = array("h")

    def feed(self, data: bytes) -> array:
        x = self.left + pcm(data)
        n = len(x) // 3
        out = array("h", bytes(4 * n))
        for i in range(n):
            a, b, c = x[3 * i], x[3 * i + 1], x[3 * i + 2]
            out[2 * i] = a
            out[2 * i + 1] = (b + c) // 2
        self.left = x[3 * n:]
        return out


def wav_bytes(samples: array, rate: int = SR_MODEL) -> bytes:
    buf = io.BytesIO()
    with wave.open(buf, "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(rate)
        w.writeframes(to_bytes(samples))
    return buf.getvalue()


def speakable(text: str, limit: int = 500) -> str:
    """Hermes answers in markdown; the voice model reads plain sentences."""
    t = re.sub(r"```.*?```", " ", text, flags=re.S)
    t = re.sub(r"\[([^\]]+)\]\([^)]+\)", r"\1", t)
    t = re.sub(r"[*_#`>|]+", " ", t)
    t = re.sub(r"^\s*[-\d.]+\s+", "", t, flags=re.M)
    t = re.sub(r"\s+", " ", t).strip()
    if len(t) > limit:
        cut = t[:limit]
        t = cut[: cut.rfind(". ") + 1] or cut
    return t


def join_caption(prev: str, nxt: str) -> str:
    """MiniCPM-o starts each 1 s unit without its leading space ("I'mdoing", "has19"). Most English words are one
    token, so a unit boundary is far more often a word boundary than a split word: add the space back."""
    if not prev or not nxt or prev[-1].isspace() or nxt[0].isspace():
        return prev + nxt
    if nxt[0].isalnum() and (prev[-1].isalnum() or prev[-1] in ".,!?;:%"):
        return prev + " " + nxt
    return prev + nxt


class Utterances:
    """Cut the mic stream into spoken utterances for Whisper (energy VAD with an adaptive noise floor)."""

    FRAME = 480  # 30 ms at 16 kHz

    def __init__(self):
        self.floor = 300.0
        self.rest = array("h")
        self.pre: list = []
        self.cur: list = []
        self.voiced = self.silent = 0
        self.in_speech = False

    def feed(self, x: array) -> list:
        out = []
        x = self.rest + x
        n = len(x) // self.FRAME
        self.rest = x[n * self.FRAME:]
        for i in range(n):
            f = x[i * self.FRAME:(i + 1) * self.FRAME]
            rms = math.sqrt(sum(v * v for v in f) / self.FRAME) + 1e-6
            loud = rms > max(450.0, self.floor * 3.0)
            if not self.in_speech:
                self.floor = 0.95 * self.floor + 0.05 * min(rms, 3000.0)
                self.pre = (self.pre + [f])[-10:]
                self.voiced = self.voiced + 1 if loud else 0
                if self.voiced >= 4:
                    self.in_speech, self.cur, self.silent = True, list(self.pre), 0
            else:
                self.cur.append(f)
                self.silent = 0 if loud else self.silent + 1
                if self.silent >= 24 or len(self.cur) > 500:  # 0.72 s of quiet, or 15 s
                    seg = array("h")
                    for frame in self.cur:
                        seg.extend(frame)
                    self.in_speech, self.cur, self.voiced, self.pre = False, [], 0, []
                    if len(seg) > SR_MODEL * 0.4:
                        out.append(seg)
        return out


class LocalSession:
    """One phone call on the local engine. Same phone protocol as PhoneSession (states, captions, transcripts)."""

    def __init__(self, ws):
        self.ws = ws
        self.cfg = config()
        self.bridge = _vs().HermesBridge()
        self.worker: Any = None
        self.vad = Utterances()
        self.resample = Resampler()
        self.tasks: set = set()
        self.speaking = False
        self.said = ""
        self.last_user = ""
        self.last_reply = ""
        self.drop_until_listen = False
        self.quiet_chunks = 0
        self.http: Any = None

    async def send_json(self, obj: dict) -> None:
        if not self.ws.closed:
            await self.ws.send_str(json.dumps(obj))

    async def state(self, value: str, detail: str = "") -> None:
        await self.send_json({"type": "state", "state": value, **({"detail": detail} if detail else {})})

    def spawn(self, coro) -> None:
        task = asyncio.create_task(coro)
        self.tasks.add(task)
        task.add_done_callback(self.tasks.discard)

    async def run(self) -> None:
        import aiohttp

        await self.state("connecting")
        url = str(self.cfg.get("duplex_url") or "")
        try:
            self.http = aiohttp.ClientSession(timeout=aiohttp.ClientTimeout(total=None, sock_connect=10))
            sep = "&" if "?" in url else "?"
            self.worker = await self.http.ws_connect(f"{url}{sep}t={self.cfg.get('duplex_token', '')}",
                                                     heartbeat=20.0, max_msg_size=0)
            await self.worker.send_json({"type": "start", "system": VOICE_PROMPT, "voice": self.cfg["voice"]})
            first = await asyncio.wait_for(self.worker.receive(), 60)
            if first.type != aiohttp.WSMsgType.TEXT or json.loads(first.data).get("type") != "ready":
                raise RuntimeError("the local voice worker did not start")
        except Exception as exc:  # noqa: BLE001 - surfaced to the phone
            log.warning("local engine unavailable: %s", exc)
            await self.send_json({"type": "error", "message": f"The local voice isn't reachable: {exc}"[:300]})
            if self.http:
                await self.http.close()
            return
        log.info("phone call connected to the local voice worker")
        await self.state("listening")
        up = asyncio.create_task(self.phone_to_worker())
        down = asyncio.create_task(self.worker_to_phone())
        try:
            _done, pending = await asyncio.wait({up, down}, return_when=asyncio.FIRST_COMPLETED)
            for task in pending:
                task.cancel()
        finally:
            for task in list(self.tasks):
                task.cancel()
            await self.worker.close()
            await self.http.close()
            log.info("phone call ended (local)")

    async def phone_to_worker(self) -> None:
        from aiohttp import WSMsgType

        async for msg in self.ws:
            if msg.type == WSMsgType.BINARY and msg.data:
                x = self.resample.feed(msg.data)
                await self.worker.send_bytes(to_bytes(x))
                for seg in self.vad.feed(x):
                    self.spawn(self.handle_utterance(seg))
            elif msg.type == WSMsgType.TEXT:
                try:
                    data = json.loads(msg.data)
                except ValueError:
                    continue
                kind = data.get("type")
                if kind == "stop":
                    return
                if kind == "interrupt":
                    self.drop_until_listen = True
                    await self.send_json({"type": "clear"})
                elif kind == "capabilities":
                    continue  # Screenshots aren't offered on the local engine yet.
                elif kind == "text" and data.get("text"):
                    self.spawn(self.handle_text(str(data["text"]), typed=True))
            elif msg.type in (WSMsgType.CLOSE, WSMsgType.CLOSING, WSMsgType.CLOSED, WSMsgType.ERROR):
                return

    async def worker_to_phone(self) -> None:
        from aiohttp import WSMsgType

        async for msg in self.worker:
            if msg.type == WSMsgType.BINARY:
                if self.drop_until_listen:
                    continue
                if not self.speaking:
                    self.speaking = True
                    await self.state("speaking")
                if not self.ws.closed:
                    await self.ws.send_bytes(msg.data)
            elif msg.type == WSMsgType.TEXT:
                data = json.loads(msg.data)
                if data.get("type") == "error":
                    await self.send_json({"type": "error", "message": str(data.get("message"))})
                    return
                if data.get("type") != "chunk":
                    continue
                text = data.get("text") or ""
                if not data.get("listen") and text and not self.drop_until_listen:
                    joined = join_caption(self.said, text)
                    await self.send_json({"type": "caption", "delta": joined[len(self.said):]})
                    self.said = joined
                self.quiet_chunks = self.quiet_chunks + 1 if data.get("listen") else 0
                # One quiet second can be a pause mid-reply; two means the turn is over.
                if self.quiet_chunks >= 2 or data.get("eot"):
                    if data.get("listen"):
                        self.drop_until_listen = False
                    if self.said.strip():
                        self.last_reply = self.said.strip()
                        await self.send_json({"type": "transcript", "role": "assistant", "text": self.last_reply})
                        self.said = ""
                    if self.speaking:
                        self.speaking = False
                        if self.vad.in_speech:
                            await self.send_json({"type": "clear"})  # The user cut in; drop queued audio.
                        await self.state("listening" if not self.tasks else "thinking")
            elif msg.type in (WSMsgType.CLOSE, WSMsgType.CLOSED, WSMsgType.ERROR):
                await self.send_json({"type": "error", "message": "The local voice worker disconnected."})
                return

    async def handle_utterance(self, seg: array) -> None:
        text = await self.transcribe(seg)
        if text:
            await self.handle_text(text)

    async def handle_text(self, text: str, typed: bool = False) -> None:
        await self.send_json({"type": "transcript", "role": "user", "text": text})
        request = await self.route(text)
        self.last_user = text
        if typed and request is None:
            # The voice model only hears audio; give it the typed words to answer.
            await self.worker.send_json({"type": "inject", "text": "The user typed: " + text})
            return
        if request is None:
            return
        log.info("local hand-off: %s", request[:200])
        # Tell the voice right away that its tools are on it, so it holds rather than guessing or refusing.
        await self.worker.send_json({"type": "inject", "retry": False, "text": (
            "NOTE: You are checking this right now with your tools. Say only a short holding line like 'One sec, "
            "let me check.' and wait for the RESULT. Do not guess and do not say you can't.")})
        await self.state("thinking", request[:120])
        await self.send_json({"type": "transcript", "role": "tool", "text": f"Working on: {request}"})
        result = await self.bridge.ask(request + "\n\nReply in one or two short, plain sentences that can be read aloud.")
        log.info("local hand-off result: %s", result[:200])
        if not self.worker.closed:
            await self.worker.send_json({"type": "inject", "text": "RESULT: " + speakable(result)})
        await self.state("speaking" if self.speaking else "listening")

    async def transcribe(self, seg: array) -> str:
        import aiohttp

        form = aiohttp.FormData()
        form.add_field("file", wav_bytes(seg), filename="utterance.wav", content_type="audio/wav")
        form.add_field("model", "whisper-1")
        form.add_field("language", "en")
        try:
            async with self.http.post(self.cfg["stt_url"], data=form, timeout=aiohttp.ClientTimeout(total=30)) as r:
                data: Any = await r.json(content_type=None)
            text = str((data or {}).get("text") or "").strip()
        except Exception as exc:  # noqa: BLE001 - a missed transcript only skips one hand-off
            log.warning("local transcription failed: %s", exc)
            return ""
        # Whisper invents these on silence and noise.
        if not text or re.fullmatch(r"[\W_]*(thanks? (you )?for watching|you|bye)?[\W_]*", text, re.I):
            return ""
        return text

    async def route(self, text: str) -> Optional[str]:
        import aiohttp

        messages = [{"role": "system", "content": ROUTER_SYSTEM}]
        if self.last_user and self.last_reply:
            messages += [{"role": "user", "content": self.last_user}, {"role": "assistant", "content": self.last_reply}]
        messages.append({"role": "user", "content": text})
        body = {"model": "handoff", "messages": messages, "tools": ROUTER_TOOLS, "temperature": 0.3, "top_p": 0.95,
                "min_p": 0.0, "max_tokens": 200, "chat_template_kwargs": {"enable_thinking": False}}
        try:
            async with self.http.post(self.cfg["router_url"], json=body, timeout=aiohttp.ClientTimeout(total=20)) as r:
                msg = (await r.json())["choices"][0]["message"]
        except Exception as exc:  # noqa: BLE001
            log.warning("local hand-off model failed: %s", exc)
            return None
        calls = msg.get("tool_calls") or []
        if not calls:
            return None
        try:
            return str(json.loads(calls[0]["function"]["arguments"]).get("request") or text)
        except (ValueError, KeyError, TypeError):
            return text
