"""Screenshots on the local voice, end to end, like the phone: share one from the call button, then ask about the
screen (typed) and approve the screenshot request. Prints the call's transcript.

    python tools/local_voice/screen_test.py ws://127.0.0.1:8796/ws [image.jpg]

Without an image it draws a phone-style error page whose details can only be known by reading it.
"""
import asyncio
import base64
import io
import json
import os
import sys
import time
from pathlib import Path

import aiohttp

URL = sys.argv[1] if len(sys.argv) > 1 else "ws://127.0.0.1:8796/ws"
TOKEN = (Path(os.environ.get("HERMES_HOME", Path.home() / ".hermes")) / "plugin-data/vessel/pair_token").read_text().strip()


def screenshot() -> str:
    if len(sys.argv) > 2:
        raw = Path(sys.argv[2]).read_bytes()
    else:
        from PIL import Image, ImageDraw, ImageFont

        img = Image.new("RGB", (540, 1170), (250, 250, 250))
        d = ImageDraw.Draw(img)
        try:
            big = ImageFont.truetype("DejaVuSans-Bold.ttf", 40)
            mid = ImageFont.truetype("DejaVuSans.ttf", 28)
        except OSError:
            big = mid = ImageFont.load_default()
        d.rectangle([0, 0, 540, 90], fill=(240, 240, 240))
        d.text((24, 30), "example.com", font=mid, fill=(60, 60, 60))
        d.text((24, 220), "Error 1016", font=big, fill=(30, 30, 30))
        d.text((24, 290), "Origin DNS error", font=mid, fill=(30, 30, 30))
        d.text((24, 380), "Ray ID: 8f2c41d7e9a0b3c5", font=mid, fill=(90, 90, 90))
        d.text((24, 440), "Cloudflare", font=mid, fill=(243, 128, 32))
        buf = io.BytesIO()
        img.save(buf, "JPEG", quality=80)
        raw = buf.getvalue()
    return "data:image/jpeg;base64," + base64.b64encode(raw).decode()


async def main():
    image = screenshot()
    t0 = time.time()
    stamp = lambda: f"{time.time() - t0:6.2f}s"
    async with aiohttp.ClientSession() as http:
        async with http.ws_connect(f"{URL}?t={TOKEN}&voice=local", max_msg_size=0) as ws:
            await ws.send_json({"type": "capabilities", "screenshot": True})

            async def mic():  # a silent, open mic: the phone streams 24 kHz PCM16 all call long
                while not ws.closed:
                    await ws.send_bytes(b"\x00" * 4800)
                    await asyncio.sleep(0.1)

            async def read():
                async for msg in ws:
                    if msg.type != aiohttp.WSMsgType.TEXT:
                        continue
                    d = json.loads(msg.data)
                    kind = d.get("type")
                    if kind == "transcript":
                        print(stamp(), "transcript", d.get("role") + ":", d.get("text"), flush=True)
                    elif kind == "device_request":
                        print(stamp(), "phone asked to approve a screenshot:", d.get("reason"), flush=True)
                        await asyncio.sleep(2)  # the user taps approve
                        await ws.send_json({"type": "device_result", "id": d["id"], "image": image})
                    elif kind in ("error",) or (kind == "state" and d.get("state") == "error"):
                        print(stamp(), "ERROR", d, flush=True)

            tasks = [asyncio.create_task(mic()), asyncio.create_task(read())]
            await asyncio.sleep(4)
            print(stamp(), "USER taps Show screen", flush=True)
            await ws.send_json({"type": "user_screenshot", "image": image})
            await asyncio.sleep(40)
            print(stamp(), "USER types: Can you look at my screen and tell me what the Ray ID is?", flush=True)
            await ws.send_json({"type": "text", "text": "Can you look at my screen and tell me what the Ray ID is?"})
            await asyncio.sleep(45)
            for t in tasks:
                t.cancel()


asyncio.run(main())
