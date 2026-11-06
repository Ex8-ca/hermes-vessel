# Local voice: how it was built and measured

Setup instructions: [`tools/local_voice/README.md`](../tools/local_voice/README.md).

## Why two models

The goal was OpenAI-Realtime-style conversation (listening while talking, cutting in) that runs on your own
hardware and can still use Hermes' tools and memory.

**MiniCPM-o 4.5** (9B, Apache-2.0) listens and speaks at the same time. On an RTX 3090 in bf16 it uses ~21 GB,
processes each 1 s of audio in 0.1-0.6 s (max 0.86 s) and starts answering 0.3-1.4 s after you stop. But it has no
usable tool calling:

- Told to write a `<hermes>request</hermes>` marker when it needs tools: **0/12**. It echoed the question instead.
- Left alone, it invents answers with confidence (18 °C and sunny in Regina in November; "I've turned off the
  lights").
- Told to say a holding line and wait: does so ~85% of the time.
- A result pushed into the live session: spoken correctly 2/8 as plain text; **6/8** formatted as a system turn
  with a 3 s nudge toward speaking. Failures were silence (not invention), so the worker re-injects once.

So the decision "does this need Hermes?" moved to a small text model reading a Whisper transcript.

## The hand-off model

**MiniCPM5-2B** (2.6B, Apache-2.0) with thinking **off** is fast (~0.1 s) but refused instead of calling tools; with
thinking **on** it called tools but rewrote requests, sometimes into the wrong task ("remind me to call Deanna"
became "check memory for any information about Deanna").

A LoRA (r16, all projections, 3 epochs, 6 min on an RTX 3090, 11.6 GB peak) fixed both:

- Data: ~560 spoken-style examples. Tool asks from slot-filled templates (weather, machines and services, calendar,
  smart home, personal memory, web, files and messages) with transcript noise (fillers, lower case, no punctuation);
  the target request is **the user's own words, cleaned, never expanded**. Chat, knowledge, opinions, feelings and
  hard negatives ("what's a parlay?" vs "what did Warren and I bet on?") with replies taken from the base model's
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

## Transmission

Nothing leaves your machines: the phone talks to your Vessel server; audio goes to your GPU box and your Whisper
server; requests go to your Hermes. Use a private network (e.g. your tailnet) between them; the worker requires a
token.
