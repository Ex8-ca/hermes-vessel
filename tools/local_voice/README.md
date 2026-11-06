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

| Piece | Where | Memory | Notes |
|---|---|---|---|
| `duplex_worker.py` (MiniCPM-o 4.5, bf16) | any CUDA GPU with 24 GB (tested RTX 3090) | ~21 GB | Apache-2.0 weights |
| Whisper (OpenAI-compatible `/v1/audio/transcriptions`) | anywhere | ~2.5 GB | any server; tested faster-whisper turbo |
| Hand-off model (MiniCPM5-2B + LoRA, GGUF Q8) | anywhere with llama.cpp | ~3 GB | `llama-server --jinja`, see below |

## 1. Duplex worker (GPU box)

```bash
uv venv --python 3.10 ~/minicpm-o/.venv && . ~/minicpm-o/.venv/bin/activate
uv pip install "transformers==4.51.0" accelerate "torch>=2.3.0,<=2.8.0" "torchaudio<=2.8.0" \
  "minicpmo-utils[all]>=1.0.5,<=1.0.6" librosa soundfile aiohttp "setuptools<81"
# Pinned revision: the model's code is read from this snapshot, never fetched at run time.
huggingface-cli download openbmb/MiniCPM-o-4_5 --revision eb4141de24550a3a1a4082f718be58f46b761728 \
  --local-dir ~/minicpm-o/MiniCPM-o-4_5
python duplex_worker.py --model ~/minicpm-o/MiniCPM-o-4_5 --port 8797
```

It writes a random access token to `~/minicpm-o/worker_token` on first start. Put the worker on a private network
(e.g. your tailnet: traffic is then encrypted by WireGuard); every connection needs the token.

## 2. Hand-off model

Weights: the LoRA in this repo's release assets, merged onto `openbmb/MiniCPM5-2B`, converted with llama.cpp's
`convert_hf_to_gguf.py --outtype q8_0`. Needs a llama.cpp recent enough to know the `minicpm5` tokenizer.

```bash
llama-server -m MiniCPM5-2B-handoff-Q8_0.gguf --host 127.0.0.1 --port 18081 -ngl 99 -c 8192 --jinja --min-p 0.0
```

How it was trained and measured: `docs/local-voice.md`.

## 3. Tell the plugin

`<hermes home>/plugin-data/vessel/local.json`:

```json
{"duplex_url": "ws://gpu-box.your-tailnet.ts.net:8797/ws"}
```

and the worker's token in `<hermes home>/plugin-data/vessel/local_duplex_token` (mode 600). Optional keys:
`stt_url` (default `http://127.0.0.1:9001/v1/audio/transcriptions`), `router_url` (default
`http://127.0.0.1:18081/v1/chat/completions`), `voice` (a reference clip in the model's `assets/`). Each can also
be set as `VESSEL_LOCAL_<KEY>` in the environment.

Restart the Vessel server; **Local** appears in the app's voice list. To try it without a phone:

```bash
python tools/local_voice/call_test.py ws://127.0.0.1:8796/ws question1.wav question2.wav
```

## Limits (honest)

- English only (the model is bilingual; it is told to stay in English).
- No screenshots on this engine yet.
- The model answers in 1-second units, so replies start 0.3-1.5 s after you stop; a Hermes job adds its own time.
- It sometimes says "One sec, let me check" before ordinary chat too.
- One conversation at a time per worker.
