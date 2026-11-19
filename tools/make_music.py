"""
Composes and synthesizes Ki Runner's original background music (no samples, no outside music).

    python tools/make_music.py      # writes app/src/main/res/raw/music_level.ogg and music_boss.ogg (needs ffmpeg)

  music_level  "Star Dash"   150 BPM, A minor   - walking stages and CLASSIC RUN
  music_boss   "Null Clash"  170 BPM, D minor   - boss fights

Instruments are simple synths: kick / snare / hats from noise and sine sweeps, a pulse-wave bass,
a square arpeggio and a filtered saw lead. Each track is 16 bars and loops seamlessly.
"""
import os
import subprocess
import sys

import numpy as np

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, "app", "src", "main", "res", "raw")
TMP = os.path.join(ROOT, ".tmp")
SR = 32000
rng = np.random.default_rng(7)

NOTE = {"C": 0, "C#": 1, "D": 2, "D#": 3, "E": 4, "F": 5, "F#": 6, "G": 7, "G#": 8, "A": 9, "A#": 10, "B": 11}


def midi(name):
    """'A4' -> 69, 'C#5' -> 73."""
    n, octv = name[:-1], int(name[-1])
    return 12 * (octv + 1) + NOTE[n]


def hz(m):
    return 440.0 * 2 ** ((m - 69) / 12)


class Track:
    def __init__(self, bpm, bars):
        self.step = 60.0 / bpm / 4                    # one 16th note, seconds
        self.n = int(round(bars * 16 * self.step * SR))
        self.mix = np.zeros(self.n)

    def at(self, sixteenth):
        return int(round(sixteenth * self.step * SR)) % self.n

    def add(self, start, sig, gain):
        i = self.at(start)
        end = i + len(sig)
        if end <= self.n:
            self.mix[i:end] += sig * gain
        else:                                         # wrap around: seamless loop
            k = self.n - i
            self.mix[i:] += sig[:k] * gain
            self.mix[:end - self.n] += sig[k:] * gain


def env(n, attack=0.004, decay=0.15, sustain=0.0, length=None):
    t = np.arange(n) / SR
    a = np.clip(t / max(attack, 1e-4), 0, 1)
    d = sustain + (1 - sustain) * np.exp(-t / max(decay, 1e-4))
    e = a * d
    if length is not None:                            # release at the end of the note
        rel = int(0.02 * SR)
        cut = max(0, min(n, int(length * SR)))
        if cut < n:
            e[cut:] *= np.exp(-np.arange(n - cut) / rel)
    return e


def osc(kind, f, dur, duty=0.5):
    n = int(dur * SR)
    t = np.arange(n) / SR
    ph = (f * t) % 1.0
    if kind == "square":
        return np.where(ph < duty, 1.0, -1.0)
    if kind == "saw":
        return 2 * ph - 1
    if kind == "tri":
        return 4 * np.abs(ph - 0.5) - 1
    return np.sin(2 * np.pi * ph)


def lowpass(x, cutoff):
    a = np.exp(-2 * np.pi * cutoff / SR)
    y = np.empty_like(x)
    acc = 0.0
    for i in range(len(x)):                           # one-pole filter (short notes, fast enough)
        acc = (1 - a) * x[i] + a * acc
        y[i] = acc
    return y


# ------------------------------------------------------------------ drums

def kick():
    dur = 0.28
    t = np.arange(int(dur * SR)) / SR
    f = 50 + 110 * np.exp(-t * 38)
    ph = 2 * np.pi * np.cumsum(f) / SR
    return np.sin(ph) * np.exp(-t * 11) + 0.25 * rng.uniform(-1, 1, len(t)) * np.exp(-t * 120)


def snare():
    dur = 0.22
    t = np.arange(int(dur * SR)) / SR
    noise = rng.uniform(-1, 1, len(t))
    noise = noise - np.concatenate(([0], noise[:-1])) * 0.6       # brighter
    return noise * np.exp(-t * 18) * 0.8 + np.sin(2 * np.pi * 190 * t) * np.exp(-t * 25) * 0.6


def hat(open_=False):
    dur = 0.18 if open_ else 0.05
    t = np.arange(int(dur * SR)) / SR
    noise = rng.uniform(-1, 1, len(t))
    noise = noise - np.concatenate(([0], noise[:-1]))             # high-pass
    return noise * np.exp(-t * (14 if open_ else 70))


# ------------------------------------------------------------------ "Star Dash" (walking stages)

def star_dash():
    tr = Track(150, 16)
    K, S, H, OH = kick(), snare(), hat(), hat(True)
    chords = [["A3", "C4", "E4"], ["F3", "A3", "C4"], ["C4", "E4", "G4"], ["G3", "B3", "D4"],
              ["A3", "C4", "E4"], ["F3", "A3", "C4"], ["G3", "B3", "D4"], ["E3", "G#3", "B3"]] * 2
    for bar in range(16):
        b0 = bar * 16
        intro = bar < 2
        for s in range(16):
            if s % 4 == 0 and not (intro and bar == 0 and s > 0):
                tr.add(b0 + s, K, 0.9)
            if s in (4, 12) and not intro:
                tr.add(b0 + s, S, 0.55)
            if not intro or s % 2 == 0:
                tr.add(b0 + s, H, 0.16 if s % 2 else 0.09)
        tr.add(b0 + 14, OH, 0.12)
        if bar % 4 == 3:                                          # fill into the next phrase
            for s in (13, 14, 15):
                tr.add(b0 + s, S, 0.3 + 0.1 * (s - 13))
        # driving bass: root eighths with octave jumps
        root = midi(chords[bar][0]) - 12
        for e in range(8):
            m = root + (12 if e % 2 else 0)
            note = osc("square", hz(m), tr.step * 2, duty=0.25) * env(int(tr.step * 2 * SR), 0.002, 0.12, 0.35, tr.step * 1.8)
            tr.add(b0 + e * 2, note, 0.22)
        # square arpeggio, 16ths, up and down the chord
        tones = [midi(n) + 12 for n in chords[bar]]
        pattern = [0, 1, 2, 1, 0, 1, 2, 3]
        for s in range(16):
            idx = pattern[s % 8]
            m = tones[idx % 3] + (12 if idx == 3 else 0)
            note = osc("square", hz(m), tr.step, duty=0.5) * env(int(tr.step * SR), 0.002, 0.06)
            tr.add(b0 + s, note, 0.07 if bar >= 2 else 0.04)
    # lead melody (second half), original
    lead = [
        ["E5", None, "A5", None, "B5", "C6", "B5", "A5"],
        ["C6", None, "A5", None, "F5", None, "G5", "A5"],
        ["G5", None, "E5", None, "C5", None, "E5", "G5"],
        ["D6", None, "B5", None, "G5", None, "A5", "B5"],
        ["C6", "B5", "A5", "E5", "A5", None, "C6", None],
        ["D6", "C6", "A5", "F5", "A5", None, "C6", "D6"],
        ["E6", None, "D6", None, "B5", None, "G5", None],
        ["G#5", None, "B5", None, "E6", None, None, None],
    ]
    for i, bar_notes in enumerate(lead):
        bar = 8 + i
        for e, name in enumerate(bar_notes):
            if name is None:
                continue
            length = 2
            while e + length // 2 < 8 and bar_notes[e + length // 2] is None and length < 6:
                length += 2
            dur = tr.step * length
            raw = osc("saw", hz(midi(name)), dur) * 0.6 + osc("square", hz(midi(name)) * 1.003, dur, 0.3) * 0.4
            note = lowpass(raw, 2600) * env(len(raw), 0.01, 0.25, 0.55, dur * 0.9)
            tr.add(bar * 16 + e * 2, note, 0.17)
    return tr.mix


# ------------------------------------------------------------------ "Null Clash" (boss fights)

def null_clash():
    tr = Track(170, 16)
    K, S, H, OH = kick(), snare(), hat(), hat(True)
    roots = ["D3", "A#2", "C3", "A2"] * 4
    for bar in range(16):
        b0 = bar * 16
        for s in range(16):
            if s in (0, 3, 6, 8, 10, 14) or (bar % 2 and s == 11):
                tr.add(b0 + s, K, 0.95)
            if s in (4, 12):
                tr.add(b0 + s, S, 0.6)
            tr.add(b0 + s, H, 0.14 if s % 2 else 0.08)
        if bar % 4 == 3:
            for s in range(8, 16):                                 # snare roll
                tr.add(b0 + s, S, 0.2 + 0.05 * (s - 8))
        else:
            tr.add(b0 + 14, OH, 0.12)
        root = midi(roots[bar])
        for s in range(16):                                        # galloping 16th bass
            if s % 4 == 1:
                continue
            m = root + (12 if s % 4 == 3 else 0)
            dur = tr.step
            note = lowpass(osc("saw", hz(m), dur), 900) * env(int(dur * SR), 0.002, 0.08, 0.3, dur * 0.9)
            tr.add(b0 + s, note, 0.42)
        # stabs on the off-beats (power chord: root + fifth + octave)
        for s in (2, 6, 10, 14):
            dur = tr.step * 1.2
            chord = sum(osc("square", hz(root + 12 + d), dur, 0.4) for d in (0, 7, 12)) / 3
            tr.add(b0 + s, chord * env(len(chord), 0.002, 0.07), 0.08)
    # lead riff in D harmonic minor (second half), original
    riff = ["D5", "F5", "A5", "C#6", "D6", "C#6", "A5", "F5",
            "A#5", "A5", "G5", "F5", "E5", "F5", "G5", "A5"]
    for bar in range(8, 16):
        b0 = bar * 16
        shift = [0, -4, -2, -5][bar % 4]
        for s, name in enumerate(riff):
            if bar % 2 == 1 and s >= 12:
                continue
            m = midi(name) + shift
            dur = tr.step
            raw = osc("saw", hz(m), dur * 1.1) * 0.7 + osc("square", hz(m) * 0.998, dur * 1.1, 0.25) * 0.3
            note = lowpass(raw, 3200) * env(len(raw), 0.004, 0.09, 0.4, dur)
            tr.add(b0 + s, note, 0.12)
    return tr.mix


def master(x):
    x = x - np.mean(x)
    x = np.tanh(x * 1.6) / np.tanh(1.6)                              # soft clip = punchy, never harsh clipping
    return x / (np.max(np.abs(x)) + 1e-9) * 0.89


def write(name, x):
    os.makedirs(TMP, exist_ok=True)
    wav = os.path.join(TMP, name + ".wav")
    import wave
    with wave.open(wav, "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(SR)
        w.writeframes((x * 32767).astype("<i2").tobytes())
    out = os.path.join(OUT, name + ".ogg")
    subprocess.run(["ffmpeg", "-y", "-loglevel", "error", "-i", wav, "-c:a", "libvorbis", "-q:a", "4", out], check=True)
    print("wrote", out, "%.1f s" % (len(x) / SR), "%d KB" % (os.path.getsize(out) // 1024))


if __name__ == "__main__":
    write("music_level", master(star_dash()))
    write("music_boss", master(null_clash()))
    sys.exit(0)
