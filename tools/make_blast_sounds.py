"""
Synthesizes the energy-blast sound effects (original audio, no samples).

    python tools/make_blast_sounds.py   # writes app/src/main/res/raw/sfx_blast.wav and sfx_hit.wav
"""
import math
import os
import random
import struct
import wave

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, "app", "src", "main", "res", "raw")
RATE = 22050


def write(name, samples):
    peak = max(1e-6, max(abs(v) for v in samples))
    with wave.open(os.path.join(OUT, name), "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(RATE)
        w.writeframes(b"".join(struct.pack("<h", int(v / peak * 0.9 * 32767)) for v in samples))


def blast():
    """Bright 'pew': a square-ish wave sweeping down from high to mid, with a quick attack."""
    n = int(RATE * 0.24)
    out, phase = [], 0.0
    for i in range(n):
        t = i / n
        f = 1400 * (1 - t) ** 1.6 + 260
        phase += 2 * math.pi * f / RATE
        tone = math.tanh(3 * math.sin(phase)) * 0.7 + math.sin(phase * 2) * 0.2
        env = min(1.0, i / (RATE * 0.006)) * (1 - t) ** 1.4
        out.append(tone * env)
    return out


def hit():
    """Punchy pop: a falling low thump plus a short noise burst."""
    rnd = random.Random(7)
    n = int(RATE * 0.34)
    out, phase, lp = [], 0.0, 0.0
    for i in range(n):
        t = i / n
        f = 180 * (1 - t) + 45
        phase += 2 * math.pi * f / RATE
        thump = math.sin(phase) * (1 - t) ** 2.2
        lp += (rnd.uniform(-1, 1) - lp) * 0.35
        noise = lp * (1 - t) ** 5
        out.append(thump * 0.9 + noise * 0.8)
    return out


if __name__ == "__main__":
    os.makedirs(OUT, exist_ok=True)
    write("sfx_blast.wav", blast())
    write("sfx_hit.wav", hit())
    print("wrote sfx_blast.wav and sfx_hit.wav to", OUT)
