"""The face behind the card, painted as pixel art at the card's own scale (face_art.py).

It is the face that has been pressing into the card from behind the whole time: long, gaunt,
hairless, the eyes the card's holes were torn for, wet. Where the card lay on it the ink has come
off onto the skin, backwards: faint blue rules across the cheeks, the red line over the brow,
the card's typed number mirrored on the forehead. Each pin that held the card on has left a hole.

real_face(seed, jaw, look, lids) returns (RGB image, alpha), the same size as face_art.face().
"""
import numpy as np

import details as D
import face_art as FA
from pixelart import RAMPS, Scene, noise2

W, H, EXTRA = FA.W, FA.H, FA.EXTRA


def outline(w=W, h=H):
    """A head, not a card: the card's long face without the scissor nicks, rounder at the top."""
    t = np.arange(h) / (h - 1)
    kt = [0.0, 0.03, 0.10, 0.22, 0.38, 0.52, 0.66, 0.80, 0.90, 0.97, 1.0]
    kw = [0.30, 0.62, 0.80, 0.88, 0.94, 0.97, 0.86, 0.66, 0.50, 0.34, 0.0]
    return np.interp(t, kt, kw) * (w / 2 - 2)


def real_face(seed=3, jaw=0.0, look=((0.0, 0.0), (0.0, 0.0)), lids=(0.0, 0.0), light=1.0, number="31"):
    rng = np.random.default_rng(seed)
    th = H + EXTRA
    sc = Scene(W, th)
    yy, xx = np.mgrid[0:th, 0:W]
    hw = outline()
    jaw_px = int(round(jaw))
    face = sc.new_id()
    m = np.zeros((th, W), bool)
    # the jaw hangs open: everything below the mouth line moves down
    for y in range(th):
        src = y if y < FA.MOUTH_Y else y - jaw_px
        if 0 <= src < H:
            m[y] = np.abs(xx[y] + 0.5 - W / 2) <= hw[src]
    sc.obj[m] = face
    sc.mat[m] = "pale"
    sc.z[m] = 10

    cx = W / 2
    yv = np.where(yy >= FA.MOUTH_Y, yy - jaw_px, yy).astype(float)

    def g(x0, y0, sx, sy):
        return np.exp(-(((xx - x0) / sx) ** 2 + ((yv - y0) / sy) ** 2))

    relief = (0.6 * g(cx, 0.5 * H, 80, 140) + 0.55 * g(cx + 2, 0.53 * H, 13, 46) * (yv > 0.36 * H)
              + 0.5 * g(cx + 3, 0.63 * H, 19, 13)
              + 0.4 * g(FA.EYE_L[0], FA.EYE_L[1] - 24, 30, 10) + 0.4 * g(FA.EYE_R[0], FA.EYE_R[1] - 24, 30, 10)
              - 0.7 * g(FA.EYE_L[0], FA.EYE_L[1], 26, 19) - 0.7 * g(FA.EYE_R[0], FA.EYE_R[1], 26, 19)
              + 0.45 * g(cx - 64, 0.5 * H, 20, 26) + 0.45 * g(cx + 66, 0.51 * H, 20, 26)
              - 0.5 * g(cx - 50, 0.64 * H, 17, 34) - 0.5 * g(cx + 52, 0.65 * H, 17, 34)
              - 0.3 * g(cx - 70, 0.3 * H, 16, 40) - 0.3 * g(cx + 70, 0.3 * H, 16, 40)
              + 0.3 * g(cx, FA.MOUTH_Y + 20, 34, 18) + 0.4 * g(cx, 0.92 * H, 30, 18))
    gx = np.gradient(relief, axis=1) * 26
    gy = np.gradient(relief, axis=0) * 26
    edge = np.clip(np.abs(xx + 0.5 - W / 2) / np.maximum(hw[np.clip(yv.astype(int), 0, H - 1)], 1), 0, 1)
    v = (0.6 * light - 0.55 * gx - 0.45 * gy + 0.1 * relief - 0.45 * edge ** 6
         + 0.1 * (noise2(W, th, 9, seed, 4) - 0.5) - 0.14 * np.clip(noise2(W, th, 20, seed + 3, 2) - 0.6, 0, 1) * 4)
    sc.paint_ramp(m, "pale", v)
    # wet and raw where the card was pressed hardest: round the eyes and the mouth
    wet = np.clip(g(FA.EYE_L[0], FA.EYE_L[1], 30, 22) + g(FA.EYE_R[0], FA.EYE_R[1], 30, 22)
                  + 0.9 * g(cx, FA.MOUTH_Y + 2, 40, 14), 0, 1)
    wet = wet * np.clip(0.55 + 0.9 * (noise2(W, th, 6, seed + 51, 3) - 0.35), 0, 1)
    raw = m & (wet > 0.3)
    sc.paint_ramp(raw, "flesh", v - 0.16 + 0.12 * wet)
    # grey, waxy, blotched: it has not seen light
    blot = m & (noise2(W, th, 14, seed + 61, 3) > 0.62)
    cur = sc.paint.copy()
    sc.paint[blot] = cur[blot] * np.array([0.86, 0.84, 0.9])
    # the card's ink, come off on the skin backwards: rules, the red line, the typed number
    bent = yv - 40 - 9.0 * relief
    rules = m & (np.abs(((bent + 9.5) % 19) - 9.5) < 0.5) & (yv > 40)
    fade = 0.16 + 0.2 * noise2(W, th, 26, seed + 9, 2)
    blue = np.array([88.0, 104.0, 136.0])
    cur = sc.paint.copy()
    sc.paint[rules] = cur[rules] * (1 - fade[rules, None]) + blue * fade[rules, None]
    red = m & (np.abs(yv - 30 - 9.0 * relief) < 1.0)
    sc.paint[red] = sc.paint[red] * 0.6 + np.array([150.0, 62.0, 58.0]) * 0.4
    # mirrored number
    FA.typed(sc, number[::-1], W - 30, 12, face)
    # veins at the temples and across the brow
    for i in range(14):
        ys, xs = np.nonzero(m & ((g(cx - 72, 0.33 * H, 20, 46) > 0.4) | (g(cx + 72, 0.33 * H, 20, 46) > 0.4)
                                 | (g(cx, 0.48 * H, 70, 12) > 0.6)))
        if not len(xs):
            break
        k = rng.integers(len(xs))
        D.walk(sc, xs[k], ys[k], rng.uniform(0, 2 * np.pi), int(rng.integers(8, 30)), rng, colour=None, ramp="pale",
               value_drop=0.1, only=face, wobble=0.4, branch=0.06)
    # the eyes, wide, no card to hide behind now
    for i, ((ex, ey), lk, lid) in enumerate(((FA.EYE_L, look[0], lids[0]), (FA.EYE_R, look[1], lids[1]))):
        D.eye(sc, face, ex + 1, ey + 2, 44, 27, "pale", look=lk, iris=0.46, pupil=0.3 + 0.12 * i, red=0.9, seed=seed * 5 + i,
              socket=1.3, socket_depth=0.62, lid=0.12 + 0.5 * lid, lashes=False, caps=18)
        D.wrinkles(sc, face, ex, ey + 18, 40, 7, rng, "pale", angle=0.0, spread=0.25, length=0.6, value=-0.16)
    # the nose
    D.nose(sc, face, cx + 2, 0.42 * H, 0.64 * H, 22, "pale", hook=1.5, light=-0.05, crooked=0.05)
    # the mouth: thin lips pulled wide; open, if the jaw is
    if jaw_px > 2:
        D.maw(sc, face, cx, FA.MOUTH_Y - 3, 74, jaw_px + 10, seed=seed + 3, tooth_w=5.5, missing=0.1, saliva=6, curve=-3)
        for s in (-1, 1):
            D.wrinkles(sc, face, cx + s * 44, FA.MOUTH_Y + jaw_px * 0.5, 30, 5, rng, "pale", angle=np.pi / 2, spread=0.2, length=0.8)
    else:
        D.lips(sc, face, cx, FA.MOUTH_Y, 66, "flesh", curve=-1.5, part=1.2, v=-0.22, seed=seed + 5, thin=0.6)
    # where the pins went in, and what came out
    for (px, py) in ((20, 22), (W - 18, 16), (W - 14, 0.36 * H), (cx - 14, 0.9 * H + jaw_px)):
        wm = sc.ell(px, py, 3.2, 2.6) & m
        sc.paint_ramp(wm, "red", 0.1)
        D.put(sc, px, py, "#0a0303", face)
        D.drips(sc, face, px - 1, px + 1, int(py) + 2, 2, rng, "red", (6, 24), 0.3)
    # shreds of the card still stuck to it
    for _ in range(16):
        x0, y0 = rng.uniform(10, W - 10), rng.uniform(10, H - 30)
        if not m[int(y0), int(x0)]:
            continue
        pts = [(x0 + rng.normal(0, 4), y0 + rng.normal(0, 3)) for _ in range(5)]
        sm = sc.poly(pts) & m
        sc.paint_ramp(sm, "paper", 0.62 + 0.1 * rng.random())
    # a sheen on the wet
    hl = (-gx * 0.6 - gy * 0.8)
    sheen = m & (wet > 0.35) & (hl > 0.25) & (noise2(W, th, 3, seed + 33, 1) > 0.66)
    sc.paint[sheen] = np.minimum(sc.paint[sheen] * 1.15 + 16, 255)
    sc.painted[m] = True
    img = sc.render(outline=False)
    return img, m
