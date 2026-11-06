"""Unit tests for the local engine's pure parts (no GPU, no network)."""
import asyncio
import importlib.util
import json
import math
import struct
import unittest
from array import array
from pathlib import Path

spec = importlib.util.spec_from_file_location("local_engine", Path(__file__).parents[1] / "plugins/vessel/local_engine.py")
assert spec is not None and spec.loader is not None
le = importlib.util.module_from_spec(spec)
spec.loader.exec_module(le)


def tone(seconds, rate=24000, amp=8000):
    return b"".join(struct.pack("<h", int(amp * math.sin(2 * math.pi * 300 * i / rate))) for i in range(int(seconds * rate)))


class AudioTests(unittest.TestCase):
    def test_resampler_rate_and_carry(self):
        r = le.Resampler()
        data = tone(1.0)
        out = r.feed(data[:9600]) + r.feed(data[9600:])  # frames that don't divide by 3 samples
        self.assertEqual(len(out), 16000)

    def test_utterance_cut_after_speech_then_quiet(self):
        v = le.Utterances()
        quiet = array("h", bytes(2 * 16000))
        speech = le.Resampler().feed(tone(1.2))
        segs = v.feed(quiet) + v.feed(speech) + v.feed(quiet)
        self.assertEqual(len(segs), 1)
        self.assertGreater(len(segs[0]) / 16000, 1.2)

    def test_noise_is_not_an_utterance(self):
        v = le.Utterances()
        self.assertEqual(v.feed(le.Resampler().feed(tone(3.0, amp=60))), [])

    def test_wav_header(self):
        self.assertEqual(le.wav_bytes(array("h", [0] * 160))[:4], b"RIFF")


class TextTests(unittest.TestCase):
    def test_join_caption_restores_unit_spaces(self):
        acc = ""
        for piece in ["Your main drive has", "19 GiB free and is 96%", "full.", "Rayleigh", "scattering.", "I", "'m fine"]:
            acc = le.join_caption(acc, piece)
        self.assertEqual(acc, "Your main drive has 19 GiB free and is 96% full. Rayleigh scattering. I'm fine")

    def test_speakable_strips_markdown_and_caps(self):
        text = "## Result\n- **Disk**: 19 GiB free\n- see [the log](http://x)\n```\ncode\n```" + " More." * 200
        out = le.speakable(text)
        self.assertNotIn("*", out)
        self.assertNotIn("http", out)
        self.assertNotIn("code", out)
        self.assertLessEqual(len(out), 500)


class RouteTests(unittest.IsolatedAsyncioTestCase):
    def session(self, reply):
        s = le.LocalSession.__new__(le.LocalSession)
        s.cfg = {"router_url": "http://router"}
        s.last_user = s.last_reply = ""

        class Resp:
            async def __aenter__(self):
                return self

            async def __aexit__(self, *a):
                return False

            async def json(self, **_):
                return {"choices": [{"message": reply}]}

        class Http:
            def post(self, *a, **k):
                self.sent = k.get("json")
                return Resp()

        s.http = Http()
        return s

    async def test_tool_call_returns_request(self):
        s = self.session({"tool_calls": [{"function": {"name": "use_tools",
                                                        "arguments": json.dumps({"request": "Turn off the lights."})}}]})
        self.assertEqual(await s.route("turn off the lights"), "Turn off the lights.")
        self.assertFalse(s.http.sent["chat_template_kwargs"]["enable_thinking"])

    async def test_chat_returns_none(self):
        s = self.session({"content": "Hi there!"})
        self.assertIsNone(await s.route("hey"))


if __name__ == "__main__":
    unittest.main()
