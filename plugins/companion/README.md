# companion — Hermes plugin

The Hermes side of [Hermes Companion](../../README.md): a phone link server that bridges the Android app to OpenAI Realtime (voice, on your ChatGPT sign-in in Hermes) and to your Hermes API server (real work).

```bash
hermes plugins enable companion
hermes auth add openai-codex --type oauth --no-browser   # once
hermes companion selftest
hermes companion serve
hermes companion pair
```

Requires the Hermes API server (`API_SERVER_KEY`). See the top-level README for setup, settings and the full disclosure of what runs on your machine (microphone streaming during calls, a background server on 127.0.0.1:8796, the pairing token).
