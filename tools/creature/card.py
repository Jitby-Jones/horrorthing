"""The card the creature has for a head, at any resolution: its scissor-cut outline, the eye holes
torn in it, the dummy's chin piece, the face pressed into it from behind (a height field), and the
painted front of it (an old index card). Drawn from the same design as the pixel-art reference
(tools/concepts/face_art.py), in card-local units: u across (0 at the creature's right, the
viewer's left), v down (0 at the top of the card).
"""
import os
import sys

import numpy as np

sys.path.insert(0, os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "concepts"))
from pixelart import BAYER4, RAMPS, noise2  # noqa: E402

ASPECT = 0.52 / 0.30  # height / width
EYE_L = (0.5 - 46 / 220, 0.375)
EYE_R = (0.5 + 48 / 220, 0.39)
EYE_SIZE = (42 / 220, 28 / 380)
MOUTH_V = 0.735
MOUTH_W = 66 / 220
CUT_U = (0.5 - 35 / 220, 0.5 + 35 / 220)
NUMBER = "31"


def _noise1(n, scale, seed):
    rng = np.random.default_rng(seed)
    g = rng.random(int(n / scale) + 3)
    x = np.arange(n) / scale
    i = np.floor(x).astype(int)
    f = x - i
    f = f * f * (3 - 2 * f)
    return g[i] * (1 - f) + g[i + 1] * f


def layers(width=264, seed=2):
    """Masks, relief and painted front at `width` pixels across (height follows the card's shape)."""
    W = int(width)
    H = int(round(width * ASPECT))
    S = W / 220.0
    rng = np.random.default_rng(seed)
    yy, xx = np.mgrid[0:H, 0:W]
    u = (xx + 0.5) / W
    v = (yy + 0.5) / H

    # outline: a face too long, cut by hand
    t = np.arange(H) / (H - 1)
    kt = [0.0, 0.05, 0.16, 0.30, 0.46, 0.60, 0.76, 0.88, 0.95, 1.0]
    kw = [0.70, 0.80, 0.86, 0.87, 0.99, 0.91, 0.71, 0.53, 0.41, 0.0]
    hw = np.interp(t, kt, kw)
    chin = np.clip((t - 0.95) / 0.05, 0, 1)
    hw = np.where(t > 0.95, 0.40 * np.sqrt(np.clip(1 - chin ** 2, 0, 1)), hw) * (W / 2 - 3 * S)
    hw += 1.6 * S * (_noise1(H, 30 * S, seed) - 0.5) * 2
    for _ in range(11):
        c = rng.integers(int(10 * S), H - int(10 * S))
        hw[c:c + int(rng.integers(2, 6) * S)] -= rng.uniform(1, 3.5) * S
    top = (4 - 3 * u) * S
    card = (np.abs(xx + 0.5 - W / 2) <= hw[yy]) & (yy >= top)

    # the eye holes, torn
    holes = np.zeros((H, W), bool)
    for (eu, ev) in (EYE_L, EYE_R):
        ang = np.arctan2((v - ev) * H, (u - eu) * W)
        rough = np.zeros_like(ang)
        for k in range(3, 9):
            rough += rng.normal(0, 0.07) * np.cos(k * ang + rng.uniform(0, 6.28))
        rx = EYE_SIZE[0] * W * 0.5 * (1 + rough)
        ry = EYE_SIZE[1] * H * 0.5 * (1 + rough) * np.where(np.sin(ang) > 0, 1.12, 1.0)
        holes |= (((u - eu) * W / rx) ** 2 + ((v - ev) * H / ry) ** 2) <= 1.0

    jaw = card & (v >= MOUTH_V) & (u >= CUT_U[0]) & (u <= CUT_U[1])

    # the face behind the card (height field, about 0..1.2)
    cu = 0.5

    def g(u0, v0, su, sv):
        return np.exp(-(((u - u0) / su) ** 2 + ((v - v0) / sv) ** 2))

    su, sv = 1 / 220, 1 / 380
    relief = (0.55 * g(cu, 0.52, 70 * su, 120 * sv) + 0.5 * g(cu + 2 * su, 0.52, 14 * su, 48 * sv) * (v > 0.36)
              + 0.45 * g(cu + 3 * su, 0.62, 20 * su, 14 * sv)
              + 0.35 * g(EYE_L[0], EYE_L[1] - 22 * sv, 30 * su, 10 * sv) + 0.35 * g(EYE_R[0], EYE_R[1] - 22 * sv, 30 * su, 10 * sv)
              - 0.45 * g(EYE_L[0], EYE_L[1], 24 * su, 16 * sv) - 0.45 * g(EYE_R[0], EYE_R[1], 24 * su, 16 * sv)
              + 0.3 * g(cu - 62 * su, 0.5, 22 * su, 28 * sv) + 0.3 * g(cu + 64 * su, 0.51, 22 * su, 28 * sv)
              - 0.25 * g(cu - 48 * su, 0.62, 18 * su, 30 * sv) - 0.25 * g(cu + 50 * su, 0.63, 18 * su, 30 * sv)
              + 0.3 * g(cu, MOUTH_V - 6 * sv, 36 * su, 8 * sv) + 0.3 * g(cu, MOUTH_V + 8 * sv, 32 * su, 8 * sv)
              + 0.35 * g(cu, 0.9, 34 * su, 20 * sv))
    return {"W": W, "H": H, "S": S, "card": card, "holes": holes, "jaw": jaw, "relief": relief, "u": u, "v": v,
            "paint": lambda **kw: paint(W, H, S, card, holes, jaw, relief, u, v, seed, **kw)}


def paint(W, H, S, card, holes, jaw, relief, u, v, seed, wet=1.0, wear=0.0, blood=0.0, shade_amount=0.45):
    """The front of the card: stained index card, its ruled lines bent over the face behind it, wet
    and see-through round the eyes and mouth, lips in the cut, the dummy's cuts and their stitches."""
    rng = np.random.default_rng(seed + 100)
    yy, xx = np.mgrid[0:H, 0:W]
    gx = np.gradient(relief, axis=1) * 26 * S
    gy = np.gradient(relief, axis=0) * 26 * S
    shade = 0.62 - shade_amount * (0.5 * gx + 0.42 * gy) + 0.1 * relief
    n1 = noise2(W, H, 9 * S, seed, 4)
    n2 = noise2(W, H, 22 * S, seed + 3, 3)
    stain = np.clip(n2 - (0.58 - 0.12 * wear), 0, 1) * 3.0
    edge = np.zeros_like(card)
    for k in range(1, int(7 * S) + 1):
        for dy, dx in ((0, k), (0, -k), (k, 0), (-k, 0)):
            edge |= ~np.roll(np.roll(card, dy, 0), dx, 1)
    val = shade + 0.16 * (n1 - 0.5) - 0.28 * stain - 0.16 * edge * (1 + wear)
    R = RAMPS["paper"]
    b = BAYER4[yy % 4, xx % 4] * 0.6
    img = R[np.clip(np.floor(val * (len(R) - 1) + 0.5 + b), 0, len(R) - 1).astype(int)].astype(float)
    # index-card rules, bent over the face
    bent = yy - 40 * S - 9.0 * S * relief
    period = 19 * S
    rules = (np.abs(((bent + period / 2) % period) - period / 2) < 0.5 * max(S, 1.0)) & (yy > 40 * S)
    redline = np.abs(yy - 30 * S - 9.0 * S * relief) < 1.0 * S
    fade = (0.18 + 0.22 * noise2(W, H, 30 * S, seed + 9, 2)) * (1 - 0.6 * np.clip(stain, 0, 1))
    blue = np.array([96.0, 112.0, 140.0])
    red = np.array([150.0, 62.0, 58.0])
    img[rules] = img[rules] * (1 - fade[rules, None]) + blue * fade[rules, None]
    img[redline] = img[redline] * 0.5 + red * 0.5

    def g(u0, v0, su_, sv_):
        return np.exp(-(((u - u0) / su_) ** 2 + ((v - v0) / sv_) ** 2))

    su, sv = 1 / 220, 1 / 380
    wetm = (g(EYE_L[0], EYE_L[1], 30 * su, 22 * sv) + g(EYE_R[0], EYE_R[1], 30 * su, 22 * sv)
            + 1.2 * g(0.5, MOUTH_V + 4 * sv, 52 * su, 24 * sv)) * wet
    wetm += 0.6 * wet * np.clip(noise2(W, H, 7 * S, seed + 21, 2) - 0.62, 0, 1) * 3 * (g(0.5, MOUTH_V + 30 * sv, 60 * su, 60 * sv) + 0.4)
    wetm = np.clip(wetm, 0, 1)
    flesh = RAMPS["flesh"][2]
    img = img * (1 - 0.55 * wetm[..., None]) + flesh * 0.55 * wetm[..., None]
    # the torn rims of the holes: fibres, wet below
    for (eu, ev) in (EYE_L, EYE_R):
        ring = ~holes & (np.roll(holes, int(2 * S), 0) | np.roll(holes, -int(2 * S), 0) | np.roll(holes, int(2 * S), 1)
                         | np.roll(holes, -int(2 * S), 1))
        lower = ring & (v > ev)
        img[ring] = img[ring] * 0.78 + np.array([215.0, 205.0, 185.0]) * 0.22
        img[lower] = img[lower] * 0.6 + RAMPS["gum"][3] * 0.4
    # nostrils
    for s in (-1, 1):
        m = ((u - (0.5 + (3 + s * 11) / 220)) / (4.5 / 220)) ** 2 + ((v - 0.655) / (2.6 / 380)) ** 2 <= 1
        img[m] = img[m] * 0.45
    # lips in the cut
    k = (u - 0.5) / (MOUTH_W / 2)
    inside = np.abs(k) < 1
    curve = MOUTH_V + (1.5 * k ** 2) / 380
    bow = 1.6 * np.exp(-((u - 0.5) / (5 / 220)) ** 2)
    up_h = (6.5 - 4 * k ** 2 - bow) * (1 - k ** 6) / 380
    lo_h = (8.5 - 6 * k ** 2) * (1 - k ** 6) / 380
    upper = inside & (v < curve) & (v > curve - up_h)
    lower = inside & (v >= curve + 1 / 380) & (v < curve + 1 / 380 + lo_h)
    img[upper] = RAMPS["flesh"][2] * 0.7 + img[upper] * 0.3
    img[lower] = RAMPS["flesh"][4] * 0.75 + img[lower] * 0.25
    gap = inside & (np.abs(v - curve) < (1.0 + 0.6 * (1 - k ** 2)) / 380) & (np.abs(k) < 0.97)
    img[gap] = np.array([28.0, 9.0, 9.0])
    # the dummy's cuts and the stitches across them
    for cu_ in CUT_U:
        cut = card & (np.abs(u - cu_) < 0.7 / W) & (v >= MOUTH_V)
        img[cut] = np.array([20.0, 10.0, 8.0])
        for i in range(5):
            y0 = MOUTH_V + (14 + i * (380 - MOUTH_V * 380 - 30) / 5) / 380
            for sgn in (-1, 1):
                for t in np.linspace(-1, 1, int(30 * S)):
                    x = int((cu_ + t * 7 / 220) * W)
                    y = int((y0 + sgn * t * 4 / 380) * H)
                    for o in range(int(max(1, S * 0.8))):
                        if 0 <= y + o < H and 0 <= x < W:
                            img[y + o, x] = np.array([42.0, 26.0, 20.0])
    # a typed number in the corner
    font = {"3": ["111", "001", "111", "001", "111"], "1": ["010", "110", "010", "010", "111"]}
    cx = int(16 * S)
    for ch in NUMBER:
        for gy_, row in enumerate(font[ch]):
            for gx_, bit in enumerate(row):
                if bit == "1":
                    y0 = int(12 * S + gy_ * S)
                    x0 = int(cx + gx_ * S)
                    img[y0:y0 + max(1, int(S)), x0:x0 + max(1, int(S))] = np.array([60.0, 52.0, 40.0])
        cx += int(4 * S)
    if blood:
        for (eu, ev) in (EYE_L, EYE_R):
            for _ in range(int(2 + blood * 4)):
                x = int((eu + rng.uniform(-10, 10) / 220) * W)
                y0 = int((ev + 14 / 380) * H)
                for j in range(int(rng.integers(8, 40) * S)):
                    if 0 <= y0 + j < H:
                        img[y0 + j, x:x + max(1, int(S * 0.8))] = RAMPS["red"][2]
    return np.clip(img, 0, 255)


def back(W, H, card, seed=5):
    """The back of the card: plain, stained, something written in pencil and crossed out."""
    rng = np.random.default_rng(seed)
    yy, xx = np.mgrid[0:H, 0:W]
    S = W / 220.0
    val = 0.6 + 0.16 * (noise2(W, H, 9 * S, seed, 4) - 0.5) - 0.3 * np.clip(noise2(W, H, 22 * S, seed + 3, 3) - 0.6, 0, 1) * 3
    R = RAMPS["paper"]
    b = BAYER4[yy % 4, xx % 4] * 0.6
    img = R[np.clip(np.floor(val * (len(R) - 1) + 0.5 + b), 0, len(R) - 1).astype(int)].astype(float)
    for row in range(5):
        y0 = (90 + row * 26) * S
        x = 30.0 * S
        while x < W - 40 * S:
            w = rng.uniform(8, 26) * S
            for t in np.linspace(0, 1, int(w * 2)):
                xi = int(x + t * w)
                yi = int(y0 + 3 * S * np.sin(t * 9 + row) + rng.normal(0, 0.5))
                if 0 <= yi < H and 0 <= xi < W:
                    img[yi, xi] *= 0.72
            x += w + rng.uniform(5, 10) * S
        for t in np.linspace(0, 1, int(300 * S)):
            xi = int(26 * S + t * (W - 60 * S))
            yi = int(y0 + 2 * S + rng.normal(0, 0.6))
            if 0 <= yi < H:
                img[yi, xi] *= 0.6
    return img
