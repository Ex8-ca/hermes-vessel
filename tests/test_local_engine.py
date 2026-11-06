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

    def test_question_in_a_noisy_room_is_heard(self):
        # The first detector missed ordinary speech over steady background noise.
        import random
        rnd, v, seg = random.Random(1), le.Utterances(), []
        noise = lambda n: array("h", [int(rnd.gauss(0, 200)) for _ in range(n)])
        speech = le.Resampler().feed(tone(2.0, amp=2500))
        mixed = array("h", [a + b for a, b in zip(speech, noise(len(speech)))])
        for chunk in (noise(32000), mixed, noise(32000)):
            seg += v.feed(chunk)
        self.assertEqual(len(seg), 1)
        self.assertFalse(v.in_speech)

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
        self.assertLessEqual(s.http.sent["max_tokens"], 8)  # decision only

    async def test_context_only_for_short_follow_ups_and_never_after_a_holding_line(self):
        s = self.session({"content": "Hi"})
        s.last_user, s.last_reply = "what's the weather tomorrow", "Sure thing, I'll check that for you. One sec,"
        await s.route("and the day after?")
        self.assertEqual(len(s.http.sent["messages"]), 2)  # after a holding line: no context
        s.last_reply = "It'll be sunny and 15 degrees."
        await s.route("and the day after?")
        self.assertEqual(len(s.http.sent["messages"]), 4)  # short follow-up: context
        await s.route("why is the sky blue keep it short")
        self.assertEqual(len(s.http.sent["messages"]), 2)  # a new question stands alone

    async def test_chat_returns_none(self):
        s = self.session({"content": "Hi there!"})
        self.assertIsNone(await s.route("hey"))

    async def test_truncated_call_hands_off_users_words(self):
        s = self.session({"content": '<function name="'})
        self.assertEqual(await s.route("how much space is left on my drive"), "how much space is left on my drive")


class GuardTests(unittest.IsolatedAsyncioTestCase):
    """The plugin-side fixes for the 4-bit voice: no other languages, no "I can't check", no guessing past a hand-off."""

    def session(self):
        s = le.LocalSession.__new__(le.LocalSession)
        s.sent, s.to_worker, s.spawned = [], [], []
        s.said, s.held, s.after_quiet = "", b"", ""
        s.speaking, s.drop_until_listen = True, False
        s.turn = s.routed = s.handed_off = s.refused = s.english_retry = 0
        s.last_user, s.heard, s.heard_turn = "", "", 0
        s.inflight, s.last_note, s.recovered = 0, 0.0, -1
        s.result_turn, s.result_text, s.fixed_numbers, s.result_shown = 0, "", 0, 0
        s.mic, s.mic_end, s.reply_start, s.last_reply_end, s.turn_at_reply_end = array("h"), 0, 0, 0, -1

        async def send_json(obj):
            s.sent.append(obj)

        class Worker:
            closed = False

            async def send_json(self, obj):
                s.to_worker.append(obj)

        s.send_json, s.worker = send_json, Worker()
        s.spawn = lambda coro: (s.spawned.append(coro), coro.close())
        return s

    async def test_foreign_reply_is_cut_and_redone_once(self):
        s = self.session()
        s.turn = 1
        await s.check_reply("嗯，如果说想")
        self.assertTrue(s.drop_until_listen)
        self.assertIn({"type": "clear"}, s.sent)
        self.assertEqual(s.after_quiet, le.english_note(""))
        s.drop_until_listen = False
        await s.check_reply("还是")
        self.assertEqual(s.after_quiet, "")  # no second retry in the same turn

    async def test_refusal_after_chat_decision_hands_off_users_words(self):
        s = self.session()
        s.turn = s.routed = 2
        s.last_user = "how much space is on my drive"
        s.said = "I can't"
        await s.check_reply("I can't check your drive directly.")
        self.assertTrue(s.drop_until_listen)
        self.assertEqual(s.after_quiet, le.HOLD_NOTE)
        self.assertEqual(len(s.spawned), 1)
        self.assertEqual(s.sent[-1]["text"], "I can't…")

    async def test_refusal_before_decision_waits_for_it(self):
        s = self.session()
        s.turn, s.routed = 3, 2
        await s.check_reply("I don't have access to your lights.")
        self.assertEqual((s.refused, s.spawned), (3, []))

    async def test_english_redo_names_what_the_user_said(self):
        s = self.session()
        s.turn = s.heard_turn = 4
        s.heard = "Hey, how's it going today?"
        await s.check_reply("嗯")
        self.assertIn("Hey, how's it going today?", s.after_quiet)

    async def test_refusal_during_hand_off_is_cut_without_a_second_hand_off(self):
        s = self.session()
        s.turn = s.routed = s.handed_off = 5
        s.last_user = "what did Alex and I bet on"
        await s.check_reply("I don't have access to past conversations.")
        self.assertTrue(s.drop_until_listen)
        self.assertEqual(s.spawned, [])

    async def test_result_cancels_a_pending_hold_line(self):
        s = self.session()
        s.after_quiet = le.HOLD_NOTE
        s.speaking, s.said = False, ""

        class Bridge:
            async def ask(self, request, **_):
                return "Done."

        async def state(*a, **k):
            pass

        s.bridge, s.state = Bridge(), state
        await s.hand_off("disk space", 1, hold_sent=True)
        self.assertEqual(s.after_quiet, "")

    async def test_reply_to_a_question_we_never_caught_recovers_it_from_the_mic(self):
        s = self.session()
        s.turn = s.turn_at_reply_end = 3             # nothing new transcribed since the last reply
        s.mic = array("h", [1000] * (16000 * 8))     # 8 s of mic audio
        s.mic_end, s.last_reply_end, s.reply_start = 16000 * 8, 16000 * 2, 16000 * 7
        await s.check_reply("One sec, let me check the weather database.")
        self.assertEqual(len(s.spawned), 1)          # the missed question goes to Whisper and the router
        self.assertEqual(s.recovered, s.reply_start)
        await s.check_reply("One sec, let me check the weather database. Still")
        self.assertEqual(len(s.spawned), 1)          # only once per reply

    async def test_holding_line_after_a_caught_question_is_left_alone(self):
        s = self.session()
        s.turn, s.turn_at_reply_end = 4, 3
        s.mic_end, s.reply_start, s.last_reply_end = 16000 * 8, 16000 * 7, 16000 * 2
        await s.check_reply("One sec, let me check.")
        self.assertEqual((s.spawned, s.drop_until_listen), ([], False))

    async def test_relaying_a_negative_result_is_not_a_refusal(self):
        s = self.session()
        s.turn = s.routed = s.handed_off = s.result_turn = 6
        s.result_text = "I couldn't find any living room lights in Home Assistant, so I couldn't turn them off."
        await s.check_reply("I couldn't find any living room lights, so I can't turn them off.")
        self.assertFalse(s.drop_until_listen)

    async def test_misread_number_is_reread_once(self):
        s = self.session()
        s.turn = s.result_turn = 7
        s.result_text = "Your main drive has 23 GB free, and it's 95% full."
        await s.check_reply("Your main drive has 23 GB free.")       # right
        self.assertFalse(s.drop_until_listen)
        await s.check_reply("Your main drive has 32 GB free space")  # wrong
        self.assertTrue(s.drop_until_listen)
        self.assertIn("23 GB", s.after_quiet)
        s.drop_until_listen = False
        await s.check_reply("It has 32 GB free space")               # only one retry
        self.assertFalse(s.drop_until_listen)

    async def test_number_still_being_spoken_is_not_judged(self):
        s = self.session()
        s.turn = s.result_turn = 8
        s.result_text = "It's 15 degrees."
        await s.check_reply("It's 1")  # "1" of "15": the next second may finish it
        self.assertFalse(s.drop_until_listen)

    def test_reading_result_is_recognised(self):
        s = self.session()
        s.turn = s.result_turn = 9
        s.result_text = "I couldn't find any living room lights in Home Assistant."
        self.assertTrue(s.reading_result("I couldn't find any living"))
        self.assertFalse(s.reading_result("Sorry, I don't have access"))
        s.result_turn = 8
        self.assertFalse(s.reading_result("I couldn't find any living"))

    async def test_cant_turn_off_before_the_result_is_a_refusal(self):
        s = self.session()
        s.turn, s.routed = 10, 9
        await s.check_reply("I can't turn off lights since I can't control physical devices.")
        self.assertTrue(s.drop_until_listen)

    async def test_guessing_while_hermes_works_is_cut(self):
        s = self.session()
        s.turn = s.routed = s.handed_off = 11
        await s.check_reply("One sec, let me check.")
        self.assertFalse(s.drop_until_listen)            # a holding line is fine
        await s.check_reply("One sec, let me check. RESULT: Tomorrow will be partly cloudy")
        self.assertTrue(s.drop_until_listen)             # an invented answer is not
        self.assertEqual(s.after_quiet, "")

    async def test_plain_chat_is_untouched(self):
        s = self.session()
        s.turn = 1
        await s.check_reply("I can't feel emotions, but I like talking with you.")
        self.assertFalse(s.drop_until_listen)

    async def test_hand_off_cuts_a_guess_but_not_a_holding_line(self):
        for said, cut in (("It will be sunny and 18 degrees", True), ("One sec, let me check", False)):
            s = self.session()
            s.said = said

            class Bridge:
                async def ask(self, request, **_):
                    return "Done."

            s.bridge = Bridge()

            async def state(*a, **k):
                pass

            s.state = state
            await s.hand_off("weather tomorrow", 1)
            self.assertEqual(s.drop_until_listen, cut, said)
            self.assertTrue(s.to_worker[-1]["text"].startswith("RESULT:"))


class WorkerBusyTests(unittest.IsolatedAsyncioTestCase):
    async def test_busy_worker_is_a_friendly_message_and_the_token_is_never_logged(self):
        import aiohttp

        s = le.LocalSession.__new__(le.LocalSession)
        s.cfg = {"duplex_url": "ws://worker/ws", "duplex_token": "SECRET-TOKEN", "voice": "v",
                 "router_url": "http://router", "debug_audio": ""}
        s.sent, s.http, s.tasks = [], None, set()

        async def send_json(obj):
            s.sent.append(obj)

        async def state(*a, **k):
            pass

        async def warm():
            pass

        s.send_json, s.state, s.warm_router = send_json, state, warm
        err = aiohttp.WSServerHandshakeError(None, (), status=409, message="Invalid response status")

        class Http:
            async def ws_connect(self, url, **k):
                raise err

            async def close(self):
                pass

        orig = aiohttp.ClientSession
        aiohttp.ClientSession = lambda **k: Http()
        try:
            with self.assertLogs("vessel", "WARNING") as logs:
                await s.run()
        finally:
            aiohttp.ClientSession = orig
        self.assertIn("busy", s.sent[-1]["message"])
        self.assertNotIn("SECRET-TOKEN", " ".join(logs.output) + json.dumps(s.sent))


if __name__ == "__main__":
    unittest.main()
