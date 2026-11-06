# Compare two versions of the local engine's speech detector on simulated phone conditions.
# Usage: vad_bench.py OLD/local_engine.py NEW/local_engine.py AUDIO_DIR
import importlib.util, math, random, sys, types, wave
from array import array

A = (sys.argv[3] if len(sys.argv) > 3 else 'audio') + '/'  # 16 kHz mono WAVs: tool_weather.wav (the question), chat_sky.wav (echo)


def load(path, name):
    spec = importlib.util.spec_from_file_location(name, path)
    m = importlib.util.module_from_spec(spec); spec.loader.exec_module(m); return m


def wav(name):
    w = wave.open(A + name)
    assert w.getframerate() == 16000 and w.getnchannels() == 1
    return array('h', w.readframes(w.getnframes()))


def rms(x):
    return math.sqrt(sum(v * v for v in x) / max(1, len(x)))


rnd = random.Random(7)


def noise(sec, level):
    # brown-ish noise, normalised to `level` rms
    n, v, out = int(sec * 16000), 0.0, []
    for _ in range(n):
        v = 0.97 * v + rnd.gauss(0, 1)
        out.append(v)
    r = math.sqrt(sum(x * x for x in out) / n) or 1
    return [x * level / r for x in out]


def mix(*parts):
    out = []
    for p in parts:
        out.extend(p)
    return array('h', [max(-32768, min(32767, int(v))) for v in out])


def add(sig, bed):
    return [a + b for a, b in zip(sig, bed)]


user, echo = wav('tool_weather.wav'), wav('chat_sky.wav')
ur = rms([v for v in user if abs(v) > 300])
print(f"speech clip rms (voiced) {ur:.0f}")


def scaled(x, target):
    g = target / ur
    return [v * g for v in x]


def scene(noise_lvl, speech_lvl, echo_lvl=0.0, gap=0.3):
    parts = [noise(2.0, noise_lvl)]
    if echo_lvl:
        e = scaled(echo, echo_lvl)
        parts.append(add(e, noise(len(e) / 16000, noise_lvl)))
        parts.append(noise(gap, noise_lvl))
    s = scaled(user, speech_lvl)
    parts.append(add(s, noise(len(s) / 16000, noise_lvl)))
    parts.append(noise(2.0, noise_lvl))
    return mix(*parts), bool(echo_lvl)


scenes = {
    'clean, normal voice': scene(30, 3000),
    'room noise, normal voice': scene(200, 2500),
    'phone gain: loud room, normal voice': scene(500, 2500),
    'phone gain: loud room, soft voice': scene(500, 1500),
    'quiet speaker, quiet room': scene(60, 700),
    'Hermes echo then question': scene(200, 2500, echo_lvl=1200),
    'loud echo then soft question': scene(300, 1500, echo_lvl=2000, gap=0.2),
}
negatives = {
    'loud room only (10 s)': mix(noise(10, 500)),
    'faint echo only': mix(noise(1, 150), add(scaled(echo, 400), noise(len(echo) / 16000, 150)), noise(1, 150)),
}

for label, path in (('old', sys.argv[1]), ('new', sys.argv[2])):
    m = load(path, 'le_' + label)
    print(f"--- {label}")
    for name, (x, has_echo) in scenes.items():
        v = m.Utterances(); segs = []
        for i in range(0, len(x), 1600):
            segs += v.feed(x[i:i + 1600])
        lens = [round(len(s) / 16000, 1) for s in segs]
        # the user's question is the last segment and ~3 s long
        got = any(abs(l - len(user) / 16000) < 1.5 for l in lens)
        print(f"  {'OK  ' if got else 'MISS'} {name:40s} segments {lens}")
    for name, x in negatives.items():
        v = m.Utterances(); segs = []
        for i in range(0, len(x), 1600):
            segs += v.feed(x[i:i + 1600])
        print(f"  {'OK  ' if not segs else 'FALSE'} {name:40s} segments {[round(len(s)/16000,1) for s in segs]}")
