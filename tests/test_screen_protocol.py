"""Unit tests for screenshot protocol (no real provider calls)."""
import asyncio
import base64
import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location('vessel_server', Path(__file__).parents[1] / 'plugins/vessel/vessel_server.py')
assert spec is not None and spec.loader is not None
server = importlib.util.module_from_spec(spec)
spec.loader.exec_module(server)


class ScreenProtocolTests(unittest.IsolatedAsyncioTestCase):
    def session(self):
        class Socket:
            closed = False
        s = server.PhoneSession(Socket())
        s.sent = []
        s.images = []
        async def send(obj): s.sent.append(obj)
        async def oa(obj): s.images.append(obj)
        s.send_json, s.oa_send = send, oa
        return s

    async def test_default_denied(self):
        s = self.session()
        self.assertIn('disabled', await s.request_screenshot('help'))
        self.assertEqual(s.sent, [])

    async def exercise(self, reply, enabled=True):
        s = self.session()
        s.screen_enabled = True
        task = asyncio.create_task(s.request_screenshot('Explain this'))
        await asyncio.sleep(0)
        self.assertEqual(s.sent[0]['capability'], 'screenshot')
        self.assertIn('already', await s.request_screenshot('duplicate'))
        s.screen_enabled = enabled
        next(iter(s.device_waiters.values())).set_result(reply)
        result = await task
        self.assertEqual(s.device_waiters, {})
        return s, result

    async def test_decline(self):
        s, result = await self.exercise({'error': 'Screenshot declined'})
        self.assertEqual(result, 'Screenshot declined')
        self.assertEqual(s.images, [])

    async def test_revoked(self):
        s, result = await self.exercise({'image': 'data:image/jpeg;base64,xxxx'}, False)
        self.assertIn('revoked', result)
        self.assertEqual(s.images, [])

    async def test_bad_format(self):
        for image in ['http://external/image', 'data:image/jpeg;base64,@@@@', 'data:image/jpeg;base64,YWJj']:
            s, result = await self.exercise({'image': image})
            self.assertEqual(s.images, [])

    async def test_image_forwarded_once(self):
        # Protocol fixture with JPEG markers, not a claim of a real screenshot.
        image = 'data:image/jpeg;base64,' + base64.b64encode(b'\xff\xd8\xfffixture\xff\xd9').decode()
        s, result = await self.exercise({'image': image})
        self.assertIn('approved', result)
        self.assertEqual(len(s.images), 1)
        self.assertEqual(s.images[0]['item']['content'][1]['image_url'], image)

    async def test_task_cancel_cleans_waiter(self):
        s = self.session(); s.screen_enabled = True
        task = asyncio.create_task(s.request_screenshot('help'))
        await asyncio.sleep(0)
        task.cancel()
        with self.assertRaises(asyncio.CancelledError): await task
        self.assertEqual(s.device_waiters, {})
        self.assertEqual(s.images, [])

    async def test_user_screenshot_forwarded_and_answered(self):
        s = self.session()  # Not gated by screen_enabled: the user chose to share, Android asked them.
        s.oa_calls = []
        image = 'data:image/jpeg;base64,' + base64.b64encode(b'\xff\xd8\xffuser\xff\xd9').decode()
        self.assertTrue(await s.share_user_screenshot(image))
        self.assertEqual(s.images[0]['item']['content'][1]['image_url'], image)
        self.assertEqual(s.images[-1], {'type': 'response.create'})
        self.assertEqual(s.sent[-1]['role'], 'user')

    async def test_user_screenshot_rejects_bad_images(self):
        for image in [None, 'http://external/x.jpg', 'data:image/jpeg;base64,@@', 'data:image/jpeg;base64,YWJj']:
            s = self.session()
            self.assertFalse(await s.share_user_screenshot(image))
            self.assertEqual(s.images, [])


if __name__ == '__main__': unittest.main()
