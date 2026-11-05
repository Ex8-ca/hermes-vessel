# Hermes Companion

Give your Hermes Agent a phone as its body. Tap one button on an Android phone and you are in a live voice conversation with your own Hermes — screen off, phone in your pocket, Bluetooth headset in your ear.

- **The voice** runs on OpenAI Realtime (`gpt-realtime-2.1`) through **your ChatGPT sign-in** in Hermes — no API key.
- **The brain** is your Hermes, with its memory, skills and tools. Anything that needs real work (files, the web, your calendar, smart home, code) is handed to Hermes mid-conversation, and the answer is spoken back.
- **The phone** only moves audio. No keys or server addresses are baked into the app; it pairs with your Hermes from a link.

> Status: early build for the Nous Research plugin hackathon (November 2026). Voice and hand-off to Hermes are verified end to end with a simulated phone over a tailnet; real-phone testing is under way. Camera "eyes", proactive speech and an inner life between conversations are next.

## How it works

```
Android app ──WSS (PCM16 24 kHz + small JSON control)──▶ companion plugin server (on your Hermes host)
                                                             │  ├─▶ OpenAI Realtime   (voice; session minted with your ChatGPT sign-in)
                                                             │  └─▶ Hermes API server (/v1/runs) for real work: ask_hermes(...)
```

Measured over a Tailscale tailnet: a reply starts about **1 second** after you stop talking. A hand-off to Hermes takes as long as the agent run (a few seconds to a minute); the voice says so instead of going silent.

## Requirements

- Hermes Agent with the **API server** enabled (`API_SERVER_KEY` set) — `ask_hermes` runs real agent turns through it.
- A ChatGPT subscription, signed in **inside Hermes**: `hermes auth add openai-codex --type oauth --no-browser` (a Codex CLI login does not count).
- An Android phone (8.0+) and a way for it to reach the Hermes host over HTTPS. The tested setup is [Tailscale](https://tailscale.com) on both, with `tailscale serve`.

## Install

Until the catalog entry lands:

```bash
git clone https://github.com/Ex8-ca/hermes-companion
cp -r hermes-companion/plugins/companion ~/.hermes/plugins/companion
hermes plugins enable companion
```

## Use

```bash
hermes companion selftest      # mints a voice session, speaks one line, saves it as a WAV — no phone needed
hermes companion serve         # the phone link server on 127.0.0.1:8796
tailscale serve --bg --set-path /companion http://127.0.0.1:8796   # tailnet-only HTTPS for the phone
hermes companion pair          # prints the pairing link
```

Open the pairing link **on the phone**: download the app, install it, tap **Pair this phone**, then tap **Talk**.

Settings (environment): `COMPANION_MODEL` (default `gpt-realtime-2.1`), `COMPANION_VOICE` (`cedar`), `COMPANION_TURN_DETECTION` (`server_vad`, or `semantic_vad` if you pause mid-thought), `COMPANION_SILENCE_MS` (`550`), `COMPANION_API_SERVER_URL` (`http://127.0.0.1:6789`).

## The Android app

Source in [`android/`](android/) (Kotlin, Jetpack Compose, OkHttp). The APK is built by this repository's GitHub Actions ([`.github/workflows/android.yml`](.github/workflows/android.yml)); tagged releases attach the APK with a GitHub build attestation. No binaries are committed — build locally with Gradle 8.14 (`gradle :app:assembleDebug` in `android/`, JDK 17).

The call runs in a foreground service (type `microphone`) with Android's voice-call audio path, so echo cancellation works on the speaker and a connected Bluetooth or wired headset is used automatically. You can talk over it to interrupt.

## What it does on your machine (disclosure)

- **Microphone:** while a call is active the phone streams microphone audio to the plugin server, which forwards it to OpenAI Realtime. Nothing is streamed when no call is active; the call shows an ongoing notification with a Hang up button.
- **Background server:** `hermes companion serve` listens on `127.0.0.1:8796`. Expose it only on your private network (for example `tailscale serve`, not Funnel).
- **The pairing token is a key to your Hermes:** whoever holds it can start voice sessions on your ChatGPT account and ask your Hermes to do things with your tools. It lives in `~/.hermes/plugin-data/companion/pair_token` (mode 0600); delete the file and restart the server to rotate it.
- **Credentials:** the OpenAI session is minted through Hermes (`credentialed_provider_request`); the plugin never sees your ChatGPT token, only a short-lived session secret. The Hermes API key is read through Hermes's secret scope.
- **Logs:** `~/.hermes/plugin-data/companion/server.log` records connections, turn events and the first 200 characters of each request to Hermes and its answer. No audio is stored.
- **Persona:** the voice gets your `SOUL.md`, `USER.md` and the current local time as its instructions, so they are sent to OpenAI at the start of each call.

## Testing without a phone

[`tools/fake_phone.py`](tools/fake_phone.py) plays the phone against a running server: a typed turn that must go through Hermes, then real speech injected into a continuously streaming mic. Run it with Hermes's Python:

```bash
~/.hermes/hermes-agent/venv/bin/python tools/fake_phone.py wss://<your-host>/companion/ws
```

## Credits

[hermes-talk](https://github.com/TheSmokeDev/hermes-talk) by TheSmokeDev showed the way for realtime voice on the ChatGPT sign-in and the Hermes run bridge. This plugin is a separate, phone-first implementation.

## License

MIT
