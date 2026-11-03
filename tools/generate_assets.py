#!/usr/bin/env python3
"""
Generates The Wake's original assets: the four sounds (synthesized, no samples) and the textures.

    python3 tools/generate_assets.py

Requires numpy, Pillow and an ffmpeg build with libvorbis. Output goes straight into
common/src/main/resources. Seeded, so re-running produces the same files.
"""
import math
import os
import subprocess
import tempfile
import wave

import numpy as np
from PIL import Image, ImageDraw, ImageFilter

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RES = os.path.join(ROOT, "common", "src", "main", "resources")
SOUNDS = os.path.join(RES, "assets", "thewake", "sounds")
TEXTURES = os.path.join(RES, "assets", "thewake", "textures", "misc")
RATE = 44100
rng = np.random.default_rng(1842)


# ---------------------------------------------------------------------------------------------
# DSP helpers
# ---------------------------------------------------------------------------------------------

def seconds(t):
    return int(round(t * RATE))


def band(x, low, high, soft=0.15):
    """Zero-phase FFT band-pass with soft edges."""
    spectrum = np.fft.rfft(x)
    freqs = np.fft.rfftfreq(len(x), 1.0 / RATE)
    mask = np.ones_like(freqs)
    np.seterr(over="ignore")
    if low > 0:
        mask *= 1.0 / (1.0 + np.exp(-(freqs - low) / max(1.0, low * soft)))
    if high < RATE / 2:
        mask *= 1.0 / (1.0 + np.exp((freqs - high) / max(1.0, high * soft)))
    return np.fft.irfft(spectrum * mask, n=len(x))


def pink(n):
    white = rng.standard_normal(n)
    spectrum = np.fft.rfft(white)
    freqs = np.fft.rfftfreq(n, 1.0 / RATE)
    freqs[0] = 1.0
    return np.fft.irfft(spectrum / np.sqrt(freqs), n=n)


def normalize(x, peak_db):
    peak = np.max(np.abs(x)) or 1.0
    return x / peak * (10 ** (peak_db / 20.0))


def loop_crossfade(x, fade):
    """Make a buffer loop seamlessly by crossfading its tail into its head."""
    n = seconds(fade)
    head = x[:n].copy()
    tail = x[-n:].copy()
    ramp = np.linspace(0.0, 1.0, n)
    out = x[: len(x) - n].copy()
    out[:n] = tail * (1.0 - ramp) + head * ramp
    return out


def damped(freq, decay, length, phase=0.0):
    t = np.arange(seconds(length)) / RATE
    return np.sin(2 * np.pi * freq * t + phase) * np.exp(-t / decay)


def write_ogg(name, x):
    os.makedirs(SOUNDS, exist_ok=True)
    pcm = np.clip(x, -1.0, 1.0)
    pcm = (pcm * 32767.0).astype(np.int16)
    with tempfile.TemporaryDirectory() as tmp:
        wav_path = os.path.join(tmp, name + ".wav")
        with wave.open(wav_path, "wb") as w:
            w.setnchannels(1)
            w.setsampwidth(2)
            w.setframerate(RATE)
            w.writeframes(pcm.tobytes())
        out = os.path.join(SOUNDS, name + ".ogg")
        subprocess.run(["ffmpeg", "-y", "-loglevel", "error", "-i", wav_path, "-ac", "1", "-c:a", "libvorbis", "-q:a", "5", out],
                       check=True)
    print("wrote", os.path.relpath(out, ROOT), f"{len(x) / RATE:.2f}s")


# ---------------------------------------------------------------------------------------------
# Sounds
# ---------------------------------------------------------------------------------------------

def room_tone():
    """A bedroom at 3 a.m.: mains hum, air, a far clock that is slightly too slow, slow breathing."""
    length = 9.0
    n = seconds(length + 1.0)
    t = np.arange(n) / RATE
    wobble = 1.0 + 0.12 * np.sin(2 * np.pi * 0.13 * t)
    hum = (np.sin(2 * np.pi * 50 * t) * 0.55 + np.sin(2 * np.pi * 100 * t) * 0.3
           + np.sin(2 * np.pi * 150 * t) * 0.12 + np.sin(2 * np.pi * 250 * t) * 0.05) * wobble
    air = band(pink(n), 40, 650) * 2.2
    clock = np.zeros(n)
    tick = band(rng.standard_normal(seconds(0.012)), 1800, 4200) * np.exp(-np.arange(seconds(0.012)) / (RATE * 0.0025))
    at = 0.4
    while at < length + 0.8:
        start = seconds(at)
        clock[start:start + len(tick)] += tick * (0.9 if int(at / 1.07) % 2 == 0 else 0.7)
        at += 1.07
    breath_env = np.clip(np.sin(2 * np.pi * (t / 4.5) - 0.6), 0.0, None) ** 2
    breath = band(rng.standard_normal(n), 250, 1400) * breath_env
    mix = hum * 0.020 + normalize(air, -30) + normalize(clock, -36) + normalize(breath, -38)
    mix = loop_crossfade(mix, 1.0)
    return normalize(mix, -16)


def shutter():
    """An old leaf shutter: open, a beat, close. Mechanical, small, close to the ear."""
    n = seconds(0.32)
    out = np.zeros(n)

    def click(strength):
        c = (damped(1830, 0.010, 0.06) * 0.6 + damped(3120, 0.006, 0.06, 1.1) * 0.45
             + damped(5260, 0.004, 0.06, 2.3) * 0.3 + damped(140, 0.012, 0.06) * 0.5)
        noise = band(rng.standard_normal(len(c)), 900, 9000) * np.exp(-np.arange(len(c)) / (RATE * 0.0035))
        return (c + noise * 0.8) * strength

    a = click(1.0)
    b = click(0.75)
    out[seconds(0.005):seconds(0.005) + len(a)] += a
    out[seconds(0.092):seconds(0.092) + len(b)] += b
    return normalize(out, -9)


def breath():
    """One slow breath, very close: in, a held moment, out."""
    length = 3.4
    n = seconds(length)
    t = np.arange(n) / RATE
    env = np.zeros(n)
    inhale = (t > 0.05) & (t < 1.35)
    env[inhale] = np.sin(np.pi * (t[inhale] - 0.05) / 1.3) ** 1.6
    exhale = (t > 1.75) & (t < 3.3)
    env[exhale] = np.sin(np.pi * (t[exhale] - 1.75) / 1.55) ** 1.2 * 1.15
    source = rng.standard_normal(n)
    voiced = band(source, 300, 900) * 0.8 + band(source, 1100, 1700) * 0.45 + band(source, 2300, 3200) * 0.2
    rasp = band(source, 3500, 7000) * 0.12
    chest = band(source, 60, 180) * 0.6
    x = (voiced + rasp + chest) * env
    return normalize(x, -11)


def stillness():
    """The ringing of a room with no sound in it. Almost nothing."""
    length = 10.0
    n = seconds(length + 1.0)
    t = np.arange(n) / RATE
    ring = np.sin(2 * np.pi * 7800 * t) * 0.5 + np.sin(2 * np.pi * 7803.2 * t) * 0.5
    hiss = band(rng.standard_normal(n), 2000, 12000) * 0.5
    low = band(pink(n), 20, 90) * 0.6
    mix = normalize(ring, -38) + normalize(hiss, -44) + normalize(low, -40)
    mix = loop_crossfade(mix, 1.0)
    return normalize(mix, -30)


# ---------------------------------------------------------------------------------------------
# Textures
# ---------------------------------------------------------------------------------------------

def night_texture(size):
    """Fallback for shader packs: a starfield to wrap the silhouette in."""
    img = Image.new("RGBA", (size, size))
    px = img.load()
    for y in range(size):
        for x in range(size):
            v = y / (size - 1)
            r = int(3 + 6 * (1 - v))
            g = int(4 + 8 * (1 - v))
            b = int(10 + 18 * (1 - v))
            px[x, y] = (r, g, b, 255)
    for _ in range(size * size // 22):
        x, y = rng.integers(0, size, 2)
        level = int(rng.integers(150, 256))
        px[int(x), int(y)] = (level, level, min(255, level + 10), 255)
    return img


def ribbon_texture(size):
    img = night_texture(size)
    px = img.load()
    c = (size - 1) / 2.0
    for y in range(size):
        for x in range(size):
            d = math.hypot(x - c, y - c) / c
            a = max(0.0, min(1.0, (1.0 - d) / 0.75))
            r, g, b, _ = px[x, y]
            px[x, y] = (r, g, b, int(255 * a * a))
    return img


def icon():
    """A long exposure of a figure made of night, in a sepia field."""
    size = 128
    base = Image.new("RGB", (size, size))
    px = base.load()
    for y in range(size):
        for x in range(size):
            d = math.hypot(x - 64, y - 70) / 90.0
            shade = max(0.0, 1.0 - d * 1.1)
            grain = rng.normal(0, 7)
            px[x, y] = (int(min(255, max(0, 92 * shade + 18 + grain))),
                        int(min(255, max(0, 76 * shade + 14 + grain))),
                        int(min(255, max(0, 56 * shade + 10 + grain))))
    stars = night_texture(size)

    def figure(dx, alpha):
        mask = Image.new("L", (size, size), 0)
        d = ImageDraw.Draw(mask)
        ox = 64 + dx
        d.rectangle([ox - 9, 22, ox + 9, 40], fill=alpha)          # head
        d.rectangle([ox - 10, 41, ox + 10, 74], fill=alpha)        # body
        d.rectangle([ox - 18, 41, ox - 11, 74], fill=alpha)        # arms
        d.rectangle([ox + 11, 41, ox + 18, 74], fill=alpha)
        d.rectangle([ox - 10, 75, ox - 1, 108], fill=alpha)        # legs
        d.rectangle([ox + 1, 75, ox + 10, 108], fill=alpha)
        return mask

    out = base.convert("RGBA")
    for dx, a in ((-15, 50), (-9, 80), (-4, 120), (0, 255)):
        m = figure(dx, a).filter(ImageFilter.GaussianBlur(1.6 if a < 255 else 0.4))
        out = Image.composite(stars, out, m)
    return out.convert("RGB")


def main():
    write_ogg("room_tone", room_tone())
    write_ogg("shutter", shutter())
    write_ogg("breath", breath())
    write_ogg("stillness", stillness())
    os.makedirs(TEXTURES, exist_ok=True)
    night_texture(64).save(os.path.join(TEXTURES, "night.png"))
    ribbon_texture(32).save(os.path.join(TEXTURES, "ribbon.png"))
    icon().save(os.path.join(RES, "thewake.png"))
    print("wrote textures and icon")


if __name__ == "__main__":
    main()
