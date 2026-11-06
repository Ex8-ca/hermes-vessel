"""Picking the Local voice must never fall back to OpenAI (no real provider calls)."""
import importlib.util
import unittest
from pathlib import Path

from aiohttp.test_utils import TestClient, TestServer

spec = importlib.util.spec_from_file_location("vessel_server_priv", Path(__file__).parents[1] / "plugins/vessel/vessel_server.py")
assert spec is not None and spec.loader is not None
server = importlib.util.module_from_spec(spec)
spec.loader.exec_module(server)


class LocalPrivacyTests(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.started = []
        setattr(server, "pair_token", lambda: "tok")

        class Broken:
            @staticmethod
            def available():
                raise SyntaxError("broken local engine")

        setattr(server, "_local", lambda: Broken)

        class Phone:
            def __init__(s, ws, voice):
                self.started.append(voice)

            async def run(s):
                pass

        setattr(server, "PhoneSession", Phone)
        self.client = TestClient(TestServer(server.make_app()))
        await self.client.start_server()

    async def asyncTearDown(self):
        await self.client.close()

    async def test_unavailable_local_refuses_instead_of_openai(self):
        ws = await self.client.ws_connect("/ws?t=tok&voice=local")
        msg = await ws.receive_json(timeout=5)
        self.assertEqual(msg["type"], "error")
        self.assertIn("local voice", msg["message"])
        self.assertEqual(self.started, [])  # no OpenAI session was opened
        await ws.close()

    async def test_other_voices_still_work(self):
        ws = await self.client.ws_connect("/ws?t=tok&voice=marin")
        await ws.receive(timeout=5)
        self.assertEqual(self.started, ["marin"])


if __name__ == "__main__":
    unittest.main()
