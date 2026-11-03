#!/usr/bin/env python3
"""
Generates The Wake's original assets: every sound (synthesized, no samples) and the small textures.
The creature itself (mesh and skin) is built by tools/creature/build_creature.py.

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
    os.makedirs(os.path.dirname(os.path.join(SOUNDS, name)), exist_ok=True)
    pcm = np.clip(x, -1.0, 1.0)
    pcm = (pcm * 32767.0).astype(np.int16)
    with tempfile.TemporaryDirectory() as tmp:
        wav_path = os.path.join(tmp, name.replace("/", "_") + ".wav")
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


def click(strength, freqs=(1830, 3120, 5260), body=140):
    c = (damped(freqs[0], 0.010, 0.06) * 0.6 + damped(freqs[1], 0.006, 0.06, 1.1) * 0.45
         + damped(freqs[2], 0.004, 0.06, 2.3) * 0.3 + damped(body, 0.012, 0.06) * 0.5)
    noise = band(rng.standard_normal(len(c)), 900, 9000) * np.exp(-np.arange(len(c)) / (RATE * 0.0035))
    return (c + noise * 0.8) * strength


def place(out, x, at):
    i = seconds(at)
    n = min(len(x), len(out) - i)
    out[i:i + n] += x[:n]


def step(variant):
    """A bare, heavy foot put down slowly on a wooden floor: a thud, skin, a board giving a little."""
    n = seconds(0.75)
    t = np.arange(n) / RATE
    thud = damped(62 + variant * 7, 0.07, 0.75) * 0.9 + damped(118 + variant * 9, 0.045, 0.75, 0.7) * 0.5
    slap = band(rng.standard_normal(n), 500, 2600) * np.exp(-t / 0.018) * 0.55
    heel = np.zeros(n)
    place(heel, band(rng.standard_normal(seconds(0.2)), 120, 900) * np.exp(-np.arange(seconds(0.2)) / (RATE * 0.03)), 0.0)
    creak = band(rng.standard_normal(n), 300 + 60 * variant, 700 + 80 * variant, 0.05) * np.exp(-((t - 0.22) / 0.12) ** 2) * 0.35
    x = thud + slap + heel * 0.5 + creak
    return normalize(x * np.exp(-t / 0.35), -8)


def crack(variant):
    """Joints: knuckles, one after another, or a neck turned too far."""
    count = (3, 4, 7)[variant]
    spacing = (0.05, 0.07, 0.035)[variant]
    out = np.zeros(seconds(0.12 + count * spacing + 0.2))
    for i in range(count):
        f = rng.uniform(1600, 4200)
        c = click(rng.uniform(0.5, 1.0), (f, f * 1.7, f * 2.9), rng.uniform(90, 260))
        place(out, c, 0.02 + i * spacing + rng.uniform(-0.01, 0.01))
    # A wet grind under the neck version.
    if variant == 2:
        g = band(rng.standard_normal(len(out)), 200, 1400) * 0.18
        g *= np.clip(np.sin(np.linspace(0, np.pi, len(out))), 0, None) ** 2
        out += g
    return normalize(out, -7)


def lofi(x, low=280, high=3400, drive=2.2):
    """A cheap microphone: narrow band, saturated, a little hum."""
    t = np.arange(len(x)) / RATE
    y = band(x, low, high)
    y = np.tanh(y / (np.max(np.abs(y)) or 1.0) * drive)
    y += np.sin(2 * np.pi * 50 * t) * 0.012 + np.sin(2 * np.pi * 150 * t) * 0.006
    return y


def wet_breath(variant):
    """Breathing through something wet, too slow, recorded on a cheap microphone in the room."""
    length = 2.8 + variant * 0.6
    n = seconds(length)
    t = np.arange(n) / RATE
    env = np.zeros(n)
    a, b = 0.1, 1.2 + variant * 0.2
    m = (t > a) & (t < b)
    env[m] = np.sin(np.pi * (t[m] - a) / (b - a)) ** 1.4
    c, d = b + 0.35, length - 0.1
    m = (t > c) & (t < d)
    env[m] = np.sin(np.pi * (t[m] - c) / (d - c)) ** 1.1 * 1.2
    source = rng.standard_normal(n)
    air = band(source, 350, 1100) * 0.8 + band(source, 1300, 2100) * 0.4
    # Bubbling: fluid in the throat, a random fast flutter.
    bubble = np.abs(band(rng.standard_normal(n), 8, 28, 0.3))
    bubble = 0.45 + 0.9 * bubble / (np.max(bubble) or 1.0)
    gurgle = band(source, 90, 320) * (bubble ** 2) * 0.9
    x = (air * bubble + gurgle) * env
    return normalize(lofi(x, 240, 3600, 2.6) * (0.25 + 0.75 * env), -12)


def warble():
    """The tape motor: a thin whine that wavers, under hiss. Loops."""
    length = 6.0
    n = seconds(length + 1.0)
    t = np.arange(n) / RATE
    wow = 1.0 + 0.004 * np.sin(2 * np.pi * 0.55 * t) + 0.0015 * np.sin(2 * np.pi * 7.8 * t + 1.3)
    phase = 2 * np.pi * np.cumsum(wow) / RATE
    whine = np.sin(phase * 1210) * 0.35 + np.sin(phase * 2420 + 0.4) * 0.12 + np.sin(phase * 60) * 0.5 + np.sin(phase * 120) * 0.25
    hiss = band(rng.standard_normal(n), 2500, 9000) * 0.5
    rumble = band(pink(n), 25, 140) * 0.7
    mix = normalize(whine, -30) + normalize(hiss, -34) + normalize(rumble, -32)
    mix = loop_crossfade(mix, 1.0)
    return normalize(mix, -22)


def tape_static():
    """A burst of tape noise: snow, with the crackle of a head losing the track."""
    n = seconds(0.9)
    t = np.arange(n) / RATE
    env = np.minimum(1.0, t / 0.008) * np.exp(-t / 0.32)
    snow = band(rng.standard_normal(n), 400, 11000)
    crackle = (rng.random(n) < 0.0016).astype(float) * rng.standard_normal(n) * 6.0
    crackle = band(crackle, 1000, 8000)
    buzz = np.sign(np.sin(2 * np.pi * 59.94 * t)) * 0.15
    x = (snow + crackle * 0.6 + buzz) * env
    return normalize(x, -9)


def tape_click():
    """A VCR key going down: plastic, then the mechanism taking up the tape."""
    n = seconds(0.6)
    out = np.zeros(n)
    place(out, click(1.0, (1450, 2600, 4100), 120), 0.0)
    place(out, click(0.6, (900, 1700, 3000), 90), 0.07)
    t = np.arange(seconds(0.45)) / RATE
    whir = (np.sin(2 * np.pi * (180 + 90 * t) * t) * 0.3 + band(rng.standard_normal(len(t)), 300, 1600) * 0.2) * np.sin(np.pi * t / t[-1])
    place(out, whir * 0.5, 0.12)
    return normalize(out, -10)


def scream():
    """Short, distorted, very close: a voice that is not a voice, through a blown-out tape."""
    length = 1.25
    n = seconds(length)
    t = np.arange(n) / RATE
    # Pitch: lurches up, holds and wavers, collapses.
    f0 = np.interp(t, [0, 0.08, 0.35, 0.8, 1.05, 1.25], [300, 560, 620, 590, 380, 240])
    f0 = f0 * (1.0 + 0.035 * np.sin(2 * np.pi * 9.5 * t) + 0.02 * band(rng.standard_normal(n), 3, 18, 0.3) * 8)
    phase = 2 * np.pi * np.cumsum(f0) / RATE
    voice = np.zeros(n)
    for k in range(1, 18):
        voice += np.sin(phase * k + rng.uniform(0, 6.28)) / (k ** 0.85)
    # A second voice a fraction off, so it beats against itself.
    phase2 = 2 * np.pi * np.cumsum(f0 * 1.513) / RATE
    for k in range(1, 9):
        voice += 0.45 * np.sin(phase2 * k) / k
    breath_noise = band(rng.standard_normal(n), 1500, 7000) * 0.6
    x = voice / np.max(np.abs(voice)) + breath_noise
    # Throat: formants that shift as if the jaw were opening further than it can.
    x = band(x, 400, 3800) * 1.4 + band(x, 1800, 2600, 0.05) * 1.2 + band(x, 3000, 4200, 0.05) * 0.6
    env = np.minimum(1.0, t / 0.025) * np.clip((length - t) / 0.18, 0.0, 1.0)
    x *= env
    # Blown out: saturate, crush.
    x = np.tanh(x / (np.max(np.abs(x)) or 1.0) * 5.0)
    step = 6
    x = np.repeat(x[::step], step)[:n]
    x = np.round(x * 24.0) / 24.0
    x = band(x, 120, 7500)
    return normalize(x, -2.5)


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

def ribbon_texture(size):
    """Fallback for shader packs: a soft-edged patch of tape snow."""
    img = Image.new("RGBA", (size, size))
    px = img.load()
    c = (size - 1) / 2.0
    for y in range(size):
        for x in range(size):
            d = math.hypot(x - c, y - c) / c
            a = max(0.0, min(1.0, (1.0 - d) / 0.75))
            v = int(rng.integers(30, 230))
            px[x, y] = (v, v, min(255, v + 8), int(220 * a * a))
    return img


def glint_texture(size):
    """A soft pinpoint: something wet at the bottom of a socket."""
    img = Image.new("RGBA", (size, size))
    px = img.load()
    c = (size - 1) / 2.0
    for y in range(size):
        for x in range(size):
            d = math.hypot(x - c, y - c) / c
            a = max(0.0, 1.0 - d) ** 2.2
            px[x, y] = (255, 255, 250, int(255 * a))
    return img


def icon():
    """The face, as a still from a worn tape. Built from the creature preview render if present."""
    size = 128
    preview = os.path.join(ROOT, "tools", "creature", "previews", "stand_face.png")
    if os.path.exists(preview):
        src = Image.open(preview).convert("RGB")
        w, h = src.size
        side = int(min(w, h) * 0.62)
        cx, cy = w // 2, int(h * 0.47)
        src = src.crop((cx - side // 2, cy - side // 2, cx + side // 2, cy + side // 2)).resize((size, size), Image.LANCZOS)
    else:
        src = Image.new("RGB", (size, size), (20, 18, 16))
    arr = np.asarray(src).astype(np.float32) / 255.0
    luma = arr @ np.array([0.299, 0.587, 0.114])
    arr = arr * 0.6 + luma[:, :, None] * 0.4
    arr = np.clip((arr - 0.03) * 1.25, 0, 1)
    # Chroma bleed, scanlines, grain, a tracking band.
    shifted = arr.copy()
    shifted[:, 2:, 0] = arr[:, :-2, 0]
    shifted[:, :-2, 2] = arr[:, 2:, 2]
    arr = shifted
    rows = np.arange(size)[:, None, None]
    arr *= 0.92 + 0.08 * (rows % 2)
    arr += rng.normal(0, 0.035, arr.shape[:2])[:, :, None]
    band_rows = (rows > 96) & (rows < 103)
    arr = np.where(band_rows, arr * 0.6 + rng.random((size, size, 1)) * 0.4, arr)
    img = Image.fromarray((np.clip(arr, 0, 1) * 255).astype(np.uint8), "RGB")
    return img


def main():
    write_ogg("room_tone", room_tone())
    write_ogg("breath", breath())
    write_ogg("stillness", stillness())
    for v in range(3):
        write_ogg(f"creature/step{v + 1}", step(v))
        write_ogg(f"creature/crack{v + 1}", crack(v))
    for v in range(2):
        write_ogg(f"creature/breath{v + 1}", wet_breath(v))
    write_ogg("tape/warble", warble())
    write_ogg("tape/static", tape_static())
    write_ogg("tape/click", tape_click())
    write_ogg("creature/scream", scream())
    os.makedirs(TEXTURES, exist_ok=True)
    ribbon_texture(32).save(os.path.join(TEXTURES, "ribbon.png"))
    entity = os.path.join(RES, "assets", "thewake", "textures", "entity")
    os.makedirs(entity, exist_ok=True)
    glint_texture(16).save(os.path.join(entity, "creature_glint.png"))
    icon().save(os.path.join(RES, "thewake.png"))
    print("wrote textures and icon")


if __name__ == "__main__":
    main()
