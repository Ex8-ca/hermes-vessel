# Local voice: how it was built and measured

Setup instructions: [`tools/local_voice/README.md`](../tools/local_voice/README.md).

## Why two models

The goal was OpenAI-Realtime-style conversation (listening while talking, cutting in) that runs on your own
hardware and can still use Hermes' tools and memory.

**MiniCPM-o 4.5** (9B, Apache-2.0) listens and speaks at the same time. On an RTX 3090 in bf16 it uses ~21 GB,
processes each 1 s of audio in 0.1-0.6 s (max 0.86 s) and starts answering 0.3-1.4 s after you stop. But it has no
usable tool calling:

- Told to write a `<hermes>request</hermes>` marker when it needs tools: **0/12**. It echoed the question instead.
- Left alone, it invents answers with confidence (18 °C and sunny on a November day in the Canadian prairies; "I've turned off the
  lights").
- Told to say a holding line and wait: does so ~85% of the time.
- A result pushed into the live session: spoken correctly 2/8 as plain text; **6/8** formatted as a system turn
  with a 3 s nudge toward speaking. Failures were silence (not invention), so the worker re-injects once.

So the decision "does this need Hermes?" moved to a small text model reading a Whisper transcript.

## The hand-off model

**MiniCPM5-2B** (2.6B, Apache-2.0) with thinking **off** is fast (~0.1 s) but refused instead of calling tools; with
thinking **on** it called tools but rewrote requests, sometimes into the wrong task ("remind me to call Sam"
became "check memory for any information about Sam").

A LoRA (r16, all projections, 3 epochs, 6 min on an RTX 3090, 11.6 GB peak) fixed both:

- Data: ~560 spoken-style examples. Tool asks from slot-filled templates (weather, machines and services, calendar,
  smart home, personal memory, web, files and messages) with transcript noise (fillers, lower case, no punctuation);
  the target request is **the user's own words, cleaned, never expanded**. Chat, knowledge, opinions, feelings and
  hard negatives ("what's a parlay?" vs "what did Alex and I bet on?") with replies taken from the base model's
  own thinking-mode answers, so training changes only *when* it calls, not how it talks.
- Held out: whole slot values (a city, a host, a person, a store, a league) and the 16 benchmark sentences.

| Test | Base, thinking off | Base, thinking on | **Fine-tuned, thinking off** |
|---|---|---|---|
| Held-out tool asks called | 4/57 | 54/57 | **57/57** |
| Held-out chat left as chat | 40/40 | 40/40 | **40/40** |
| Requests with words the model added | — | 52/54 | **0/57** |
| Fresh phrasings (not from any template) | 14/24 | — | **24/24** |
| 16-sentence benchmark | 10/16 | 15/16 | **16/16** |
| Median decision time | 0.2 s | 0.6 s | **0.1-0.2 s** |

The fresh-phrasing test matters most: held-out slots still share templates with training. All measured on
written transcripts, not yet on a wide range of real voices.

## A local call, end to end

Recorded speech streamed into `/ws?voice=local` (`tools/local_voice/call_test.py`):

- "Hey, how's it going today?" -> answered directly.
- "How much free space is left on my main drive?" -> "One sec, let me check." -> hand-off with the user's exact
  words -> Hermes answered in ~5 s -> "Your main drive has 19 GiB free and is 96% full."
- "Why is the sky blue?" -> answered directly.
- "Turn off the living room lights" -> holding line -> Hermes found no such lights in Home Assistant -> the voice
  said exactly that.

## On a 16 GB card

The installer's default on cards under 24 GB, measured on an RTX 5080 (16 GB) with Whisper on the same card:

- **Voice model in 4-bit**: bitsandbytes NF4 on the language model only; hearing, speech and the projections stay
  bf16. Weights 6.9 GiB, 12.6 GiB peak; its memory is capped (`--max-gpu-gib`), otherwise PyTorch's cache grows into
  Whisper's room and transcriptions fail. Real time: median 0.16-0.28 s per 1 s of audio. 4-bit on the output layer
  too saved 0.9 GB but went slower than real time, so it isn't used.
- **Whisper turbo in int8**: 1.3 GB. Card peak with everything: 14.8 GB.
- **Hand-off model on the CPU** (it doesn't fit on the card too): on the same cores as the voice model it doubled
  the voice's time per chunk, so the installer gives it half the threads; on 8 efficiency cores a full decision took
  ~5 s. The engine therefore asks for **4 tokens** only: a reply starting `<function` means hand off, and the
  user's own words go to Hermes (what the model was trained to pass on anyway). Same scores (57/57, 40/40, 24/24),
  ~1.5-2 s. Q6_K kept every score; Q4_K_M lost a chat and a fresh case.

### What the 4-bit voice gets wrong, and what the plugin does

The plugin holds each second of the voice's audio until its text arrives (they come together, so this costs
nothing) and checks it first:

| Habit | Seen | What happens |
|---|---|---|
| Replies in Chinese (it's bilingual) | the first greeting in ~3 of 4 calls | cut; asked again in English, quoting the user |
| "I can't check / I don't have access" | most tool questions, before the hand-off lands | cut; the words go to Hermes |
| Answers on its own while Hermes works | sometimes | cut to a holding line |
| Says a number Hermes didn't (32 GB for 23 GB) | once | cut and re-read |
| Drops a reply's last words | 4 of 12 replies | fixed in the worker: 18 of 18 complete |

The last one is in the model's streaming code: each 1 s step writes up to 20 text tokens but voices exactly ~1 s
of speech, so speech falls behind (about 0.4 s per second), and when the turn ends the unvoiced rest is dropped.
The worker now lets the final step finish, and requires it to when the reply is clearly behind (fewer than 6.5
speech tokens per text token so far; complete replies measured 6.6-7.8, the cut ones less). Letting every step run
longer instead made no difference to cut endings and added garbled speech, so it isn't used.

### Speech detection

The first real phone call missed a question: the plugin's speech detector never passed it to Whisper. Its noise
estimate rose as fast as it fell, so with any room noise normal speech didn't count. It now falls fast and rises
slowly (~6 s), starts on 3 of 5 frames above 3x the noise, and ends below 2x. Simulated phone conditions
(`tools/local_voice/vad_bench.py`): the old detector missed 5 of 7 scenes, the new one 0 of 7, all the way through
Whisper and the hand-off model. As a backstop, when the voice says "let me check" (or "I can't...") with no new
utterance since its last reply, the plugin transcribes the last seconds of microphone audio and routes them.

## Transmission

Nothing leaves your machines: the phone talks to your Vessel server; audio goes to your GPU box and your Whisper
server; requests go to your Hermes. Use a private network (e.g. your tailnet) between them; the worker requires a
token.
