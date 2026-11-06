# Local voice: Hermes on your own GPU

An optional, fully private alternative to OpenAI Realtime. Pick **Local** in the app's voice list.

```
phone mic --> Vessel plugin --> MiniCPM-o 4.5 duplex worker (GPU box) --> its own voice back to the phone
                  |              listens while it talks; you can cut in
                  +--> Whisper (your words as text)
                         +--> MiniCPM5-2B hand-off model: "does this need Hermes?" (~0.1 s)
                                +--> yes: a Hermes agent turn; the answer goes back to the voice model to say
```

The voice model can't use tools and will guess if left alone, so it says a short holding line ("One sec, let me
check") while a small model fine-tuned for exactly this decision passes your own words to Hermes.

## What runs where

All three parts fit on **one 16 GB NVIDIA card** (tested: RTX 5080, card peak 14.8 GB):

| Piece | Runs on | Memory | Port |
|---|---|---|---|
| `duplex_worker.py`: MiniCPM-o 4.5, 4-bit (NF4) language model; hearing and speech stay bf16 | GPU | 12.6 GB peak | 8797 |
| `stt_server.py`: faster-whisper turbo, int8 | GPU | 1.3 GB | 8798 |
| Hand-off model: [BurtleLife/vessel-handoff-MiniCPM5-2B](https://huggingface.co/BurtleLife/vessel-handoff-MiniCPM5-2B) (Q6_K) in llama.cpp | CPU | 2 GB RAM | 8799 |

On a 24 GB card the voice model runs at full precision (~21 GB) instead. Both base models are Apache-2.0.

## Install (on the GPU box)

```bash
git clone https://github.com/Ex8-ca/hermes-vessel && cd hermes-vessel
tools/local_voice/install.sh                    # same machine as Hermes
tools/local_voice/install.sh --listen 0.0.0.0   # or a separate GPU box (use a private network, e.g. a tailnet)
```

It needs Linux x86-64, the NVIDIA driver, [uv](https://docs.astral.sh/uv/) and ~30 GB of disk. It builds a Python
environment, downloads pinned model revisions and a checksum-verified llama.cpp build, creates one random token, and
starts three systemd user services (`vessel-local-voice`, `vessel-local-stt`, `vessel-local-router`). Every request
to them needs the token. Then, on the Hermes machine:

```bash
hermes vessel local --url ws://<gpu-box>:8797/ws --token-file <copy of the worker_token file>
```

It saves the settings, checks all three parts answer, and tells you to restart the Vessel server; **Local** then
appears in the app's voice list. `hermes vessel local` alone re-checks; `--off` removes it.

To try it without a phone: `python tools/local_voice/call_test.py ws://127.0.0.1:8796/ws question.wav ...`
(16 kHz WAVs) and `python tools/local_voice/screen_test.py ws://127.0.0.1:8796/ws` for screenshots.

### Manual setup

The settings live in `<hermes home>/plugin-data/vessel/local.json` (`duplex_url`, `stt_url`, `router_url`,
optional `voice`, each also settable as `VESSEL_LOCAL_<KEY>`), with the token in `local_duplex_token` (mode 600).
Any OpenAI-compatible transcription server works for `stt_url`, and any llama.cpp `llama-server --jinja` serving
the hand-off GGUF for `router_url`.

## Debugging a call

The server log (`<hermes home>/plugin-data/vessel/server.log`) records each utterance heard, what the user said
when the voice answered by itself, every hand-off, and every time the plugin corrected the voice. If a question is
missed, set `"debug_audio": "1"` in `local.json`: each call's microphone audio is then saved, on your server only,
to `<hermes home>/plugin-data/vessel/local-debug/`. Turn it off and delete the files when done.
`vad_bench.py` replays simulated phone conditions through the speech detector.

## What the plugin corrects

The 4-bit voice model has habits; the plugin checks each second of its speech before the phone plays it:

- **Another language** (it is bilingual): cut, and asked again in English with the user's words.
- **"I can't check / I don't have access"**: cut; the question goes to Hermes, whose tools are the voice's tools.
- **Answering on its own** when the hand-off model has sent the question to Hermes: cut to a holding line.
- **A number Hermes didn't say** when relaying an answer: cut and re-read once.
- **Screenshots**: "Show screen" on the call button, or asking about your screen out loud ("what's on my screen?"),
  sends one approved screenshot to Hermes with your words; the phone asks you to approve every one.
- **Last words cut off**: the voice model's speech lags its text and it used to drop whatever it hadn't voiced
  yet when it ended a turn (4 of 12 replies lost their ending). The worker now makes it finish (18 of 18 complete).
- **A reply to a question the speech detector missed** (it says "let me check" with no new utterance): the last
  seconds of microphone audio are transcribed and routed after all.

## Limits (honest)

- English only (the model is bilingual; it is told to stay in English).
- Screenshots go to Hermes, not to the voice model (it can't see images): Hermes answers and the voice reads it.
  The exact answer is left on screen, because the voice paraphrases and can drop codes.
- The model answers in 1-second units, so replies start 0.3-1.5 s after you stop; a Hermes job adds its own time.
- It sometimes says "One sec, let me check" before ordinary chat too.
- One conversation at a time per worker; a second caller is told the voice is busy.
- The 4-bit voice sometimes slips into Chinese (caught and redone), and you may hear about a second of
  "I can't..." before it is cut.
