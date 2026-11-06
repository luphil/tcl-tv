#!/usr/bin/env python3
"""Generates the electronic drum kits by modelling the circuits of classic drum machines:
pitch-dropping sine kicks, metallic hats from six detuned square waves, noise-burst claps.
Writes packs in the same format as tools/build-drumkits.py, with velocity layers (harder hits
are brighter and punchier, as on the real machines) and slight analog drift between takes."""
import json, os
import numpy as np

OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "../build/drums/out")
RATE = 48000
LAYERS = 6
TAKES = 3
# The six square-wave oscillators of the TR-808's cymbal and hi-hat circuit
METAL = [205.3, 304.4, 369.6, 522.7, 540.0, 800.0]


def times(seconds):
    return np.arange(int(RATE * seconds)) / RATE


def band(signal, low, high):
    """Keeps a frequency range, with soft edges."""
    spectrum = np.fft.rfft(signal)
    freqs = np.fft.rfftfreq(len(signal), 1 / RATE)
    keep = np.ones_like(freqs)
    if low:
        keep *= 1 / (1 + (low / np.maximum(freqs, 1)) ** 4)
    if high:
        keep *= 1 / (1 + (freqs / high) ** 4)
    return np.fft.irfft(spectrum * keep, len(signal))


def noise(rng, seconds):
    return rng.uniform(-1, 1, int(RATE * seconds))


def square(t, hz, phase):
    return np.sign(np.sin(2 * np.pi * hz * t + phase))


def sweep(t, start, end, fall):
    """A sine falling from one pitch to another, as a kick's or tom's does."""
    hz = end + (start - end) * np.exp(-t / fall)
    return np.sin(2 * np.pi * np.cumsum(hz) / RATE)


def metal(rng, t, drift, scale=1.0):
    return sum(square(t, hz * scale * drift, rng.uniform(0, 6.28)) for hz in METAL) / 6


def kick(rng, drift, v, length, low, high, fall, decay, click, drive):
    t = times(length)
    body = sweep(t, high * drift, low * drift, fall * (0.7 + 0.5 * v)) * np.exp(-t / decay)
    snap = band(noise(rng, length), 1500, 9000) * np.exp(-t / 0.002) * click * v
    return np.tanh((body + snap) * drive) / np.tanh(drive)


def snare(rng, drift, v, tones, tone_decay, noise_decay, low, snappy):
    t = times(0.45)
    tone = sum(np.sin(2 * np.pi * hz * drift * t) for hz in tones) / len(tones) * np.exp(-t / tone_decay)
    hiss = band(noise(rng, 0.45), low, 12000) * np.exp(-t / noise_decay)
    return tone * 0.8 + hiss * snappy * (0.55 + 0.6 * v)


def clap(rng, drift, v, tail):
    t = times(0.5)
    source = band(noise(rng, 0.5), 700 * drift, 3800 * drift)
    shape = np.zeros_like(t)
    for start in (0.0, 0.011, 0.023, 0.034):  # The separate hands of the 808's clap
        shape = np.maximum(shape, (t >= start) * np.exp(-np.maximum(t - start, 0) / 0.004))
    shape = np.maximum(shape, (t >= 0.034) * 0.75 * np.exp(-np.maximum(t - 0.034, 0) / tail))
    return source * shape * (0.8 + 0.3 * v)


def hat(rng, drift, v, decay, scale=1.0, air=0.25):
    length = max(0.12, decay * 7)
    t = times(length)
    body = band(metal(rng, t, drift, scale) + band(noise(rng, length), 6000, None) * air, 6500, 15000)
    return body * np.exp(-t / decay) * (0.75 + 0.35 * v)


def cymbal(rng, drift, v, decay, scale, low):
    length = decay * 6
    t = times(length)
    source = metal(rng, t, drift, scale) + metal(rng, t, drift * 1.13, scale * 1.41) * 0.7
    return band(source + band(noise(rng, length), 5000, None) * 0.35, low, 15000) * np.exp(-t / decay)


def cowbell(rng, drift, v):
    t = times(0.5)
    tone = square(t, 540 * drift, 0) + square(t, 800 * drift, 0)
    return band(tone, 400, 3200) * (0.7 * np.exp(-t / 0.012) + 0.45 * np.exp(-t / 0.13))


def tone(rng, drift, v, start, end, fall, decay, length=0.5, hiss=0.05):
    t = times(length)
    body = sweep(t, start * drift, end * drift, fall) * np.exp(-t / decay)
    return body + band(noise(rng, length), 2000, 9000) * np.exp(-t / 0.006) * hiss * v


def rimshot(rng, drift, v):
    t = times(0.08)
    return band(np.sin(2 * np.pi * 1700 * drift * t) + np.sin(2 * np.pi * 455 * drift * t), 300, 6000) * np.exp(-t / 0.009)


def maracas(rng, drift, v):
    t = times(0.08)
    return band(noise(rng, 0.08), 5000, 14000) * np.exp(-t / 0.014)


def common():
    return {
        "rimshot": rimshot,
        "cowbell": cowbell,
        "claves": lambda r, d, v: tone(r, d, v, 2500, 2500, 1, 0.022, 0.12, 0),
        "shaker": maracas,
        "conga_high": lambda r, d, v: tone(r, d, v, 380, 330, 0.02, 0.09),
        "conga_low": lambda r, d, v: tone(r, d, v, 240, 200, 0.02, 0.12),
        "tom_high": lambda r, d, v: tone(r, d, v, 260, 165, 0.03, 0.16, 0.8, 0.15),
        "tom_mid": lambda r, d, v: tone(r, d, v, 190, 120, 0.03, 0.2, 0.9, 0.15),
        "tom_low": lambda r, d, v: tone(r, d, v, 140, 85, 0.03, 0.26, 1.2, 0.15),
    }


KITS = {
    "808": ("TR-808 Style", {
        "kick": lambda r, d, v: kick(r, d, v, 1.4, 48, 145, 0.018, 0.34, 0.35, 1.3),
        "snare": lambda r, d, v: snare(r, d, v, (180, 330), 0.06, 0.13, 1500, 0.8),
        "clap": lambda r, d, v: clap(r, d, v, 0.095),
        "hat_closed": lambda r, d, v: hat(r, d, v, 0.022),
        "hat_open": lambda r, d, v: hat(r, d, v, 0.17),
        "ride": lambda r, d, v: cymbal(r, d, v, 0.5, 1.6, 3500),
        "crash": lambda r, d, v: cymbal(r, d, v, 0.9, 1.1, 2500),
        **common(),
    }),
    "909": ("TR-909 Style", {
        "kick": lambda r, d, v: kick(r, d, v, 0.8, 56, 230, 0.009, 0.15, 0.9, 2.2),
        "snare": lambda r, d, v: snare(r, d, v, (190, 340), 0.045, 0.1, 2200, 1.1),
        "clap": lambda r, d, v: clap(r, d, v, 0.12),
        "hat_closed": lambda r, d, v: hat(r, d, v, 0.03, 1.5, 0.5),
        "hat_open": lambda r, d, v: hat(r, d, v, 0.22, 1.5, 0.5),
        "ride": lambda r, d, v: cymbal(r, d, v, 0.7, 2.1, 4000),
        "crash": lambda r, d, v: cymbal(r, d, v, 1.1, 1.5, 3000),
        **common(),
    }),
    "trap": ("Trap 808", {
        "kick": lambda r, d, v: kick(r, d, v, 2.6, 42, 130, 0.03, 0.75, 0.6, 2.6),
        "snare": lambda r, d, v: snare(r, d, v, (220, 400), 0.05, 0.09, 2500, 1.3),
        "clap": lambda r, d, v: clap(r, d, v, 0.07),
        "hat_closed": lambda r, d, v: hat(r, d, v, 0.016, 1.7, 0.6),
        "hat_open": lambda r, d, v: hat(r, d, v, 0.12, 1.7, 0.6),
        "ride": lambda r, d, v: cymbal(r, d, v, 0.5, 2.0, 4000),
        "crash": lambda r, d, v: cymbal(r, d, v, 0.9, 1.5, 3000),
        **common(),
    }),
}


def build(kit, label, voices):
    folder = os.path.join(OUT, kit)
    os.makedirs(folder, exist_ok=True)
    rng = np.random.default_rng(808)
    index = {"label": label, "rate": RATE, "roles": {}, "levels": {}}
    offset = 0
    with open(os.path.join(folder, "pack.bin"), "wb") as pack:
        for role, voice in voices.items():
            layers = []
            for layer in range(LAYERS):
                v = layer / (LAYERS - 1)
                takes = []
                for _ in range(TAKES):
                    drift = 1 + rng.uniform(-0.004, 0.004)
                    takes.append(voice(rng, drift, v) * 10 ** ((v - 1) * 22 / 20))
                layers.append(takes)
            top = max(np.abs(hit).max() for takes in layers for hit in takes)
            rows, levels = [], []
            for takes in layers:
                row = []
                levels.append(round(20 * np.log10(np.mean([np.abs(h).max() for h in takes]) / top), 1))
                for hit in takes:
                    fade = min(RATE // 100, len(hit))
                    hit = hit * 0.9 / top
                    hit[-fade:] *= np.linspace(1, 0, fade)
                    stereo = np.stack([hit, hit], axis=1)
                    pcm = np.clip(stereo * 32767, -32768, 32767).astype("<i2")
                    pack.write(pcm.tobytes())
                    row.append([offset, len(pcm)])
                    offset += len(pcm)
                rows.append(row)
            index["roles"][role] = rows
            index["levels"][role] = levels
    json.dump(index, open(os.path.join(folder, "index.json"), "w"), separators=(",", ":"))
    print(f"{kit}: {offset / RATE / 60:.1f} min, {offset * 4 / 1e6:.0f} MB, {len(voices)} sounds", flush=True)


if __name__ == "__main__":
    for kit, (label, voices) in KITS.items():
        build(kit, label, voices)
