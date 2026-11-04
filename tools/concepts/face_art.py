"""The refined creature's face, painted as pixel art at about 730 pixels per block.

The head is an old index card (faint ruled lines, a typed number in the corner) cut with scissors
to the shape of a face that is too long. A real face is pressed into it from behind: the nose,
brow and lips raise the card, wet eyes look out through ragged holes torn where the eyes are, the
card goes dark and see-through where it is wet. It is cut from the corners of the mouth straight
down to the chin like a ventriloquist's dummy, and the chin piece is held on with stitches. A few
short pins at the corners.

face(params) returns an RGBA image of the card (card-local pixels, top-left origin, the jaw in
place or dropped), plus a mask of the chin piece and the size of the card in pixels.
"""
import numpy as np

import details as D
from pixelart import BAYER4, RAMPS, Scene, noise2, rgb

W, H = 220, 380  # card art size in pixels (about 0.30 x 0.52 blocks)
EXTRA = 150  # room below the card for a dropped jaw


def outline(w=W, h=H, seed=2):
    """The scissor-cut shape: half-width per row, top to bottom. Too long a face: a narrow brow,
    pinched temples, wide cheekbones, then a long jaw tapering to a small chin."""
    rng = np.random.default_rng(seed)
    t = np.arange(h) / (h - 1)
    kt = [0.0, 0.05, 0.16, 0.30, 0.46, 0.60, 0.76, 0.88, 0.95, 1.0]
    kw = [0.70, 0.80, 0.86, 0.87, 0.99, 0.91, 0.71, 0.53, 0.41, 0.0]
    hw = np.interp(t, kt, kw)
    chin = np.clip((t - 0.95) / 0.05, 0, 1)
    hw = np.where(t > 0.95, 0.40 * np.sqrt(np.clip(1 - chin ** 2, 0, 1)), hw)
    hw = hw * (w / 2 - 3)
    # the scissors wander and nick
    hw += 1.6 * (noise1(h, 30, seed) - 0.5) * 2
    for _ in range(11):
        c = rng.integers(10, h - 10)
        hw[c:c + rng.integers(2, 6)] -= rng.uniform(1, 3.5)
    return hw


def noise1(n, scale, seed):
    rng = np.random.default_rng(seed)
    g = rng.random(int(n / scale) + 3)
    x = np.arange(n) / scale
    i = np.floor(x).astype(int)
    f = x - i
    f = f * f * (3 - 2 * f)
    return g[i] * (1 - f) + g[i + 1] * f


def card_mask(w=W, h=H, total_h=H + EXTRA, seed=2):
    hw = outline(w, h, seed)
    yy, xx = np.mgrid[0:total_h, 0:w]
    m = np.zeros((total_h, w), bool)
    rows = yy < h
    top = 4 - 3 * (xx / w)  # a slanted top cut
    m[rows] = (np.abs(xx[rows] + 0.5 - w / 2) <= hw[yy[rows]]) & (yy[rows] >= top[rows])
    return m


# Feature positions (card pixels)
EYE_L = (W / 2 - 46, 0.375 * H)
EYE_R = (W / 2 + 48, 0.39 * H)
EYE_SIZE = (42, 28)
MOUTH_Y = 0.735 * H
MOUTH_W = 66
CUT_X = (W / 2 - 35, W / 2 + 35)


def jaw_mask(total_h=H + EXTRA):
    """The chin piece: between the two cuts, from the mouth line down."""
    m = card_mask(total_h=total_h)
    yy, xx = np.mgrid[0:total_h, 0:W]
    return m & (yy >= MOUTH_Y) & (xx >= CUT_X[0]) & (xx <= CUT_X[1])


def face(look=((0.0, 0.0), (0.0, 0.0)), lids=(0.25, 0.25), jaw=0.0, wet=1.0, wear=0.0, smile=0.0, seed=1,
         blood=0.0, number="31", light=1.0):
    """Paints the card. look: per-eye (x, y) in -1..1; lids: per-eye closure 0 (open) .. 1 (shut);
    jaw: pixels the chin piece has dropped; wear: 0..1, how torn and stained this replacement face is."""
    rng = np.random.default_rng(seed)
    th = H + EXTRA
    sc = Scene(W, th)
    sc.bg[:] = 0
    mask = card_mask(total_h=th)
    jm = jaw_mask(th)
    card = sc.new_id()
    chin = sc.new_id()
    # the card as two flat objects; the chin is moved down by `jaw`
    yy, xx = np.mgrid[0:th, 0:W]
    upper = mask & ~jm
    sc.z[upper] = 10
    sc.obj[upper] = card
    sc.mat[upper] = "paper"
    jaw_px = int(round(jaw))
    moved = np.zeros_like(jm)
    if jaw_px:
        moved[jaw_px:] = jm[:th - jaw_px]
    else:
        moved = jm.copy()
    # behind the chin piece: the mouth (only seen when it drops)
    mouth = sc.new_id()
    hole = jm & ~moved if jaw_px else np.zeros_like(jm)
    gap_region = (yy >= MOUTH_Y - 6) & (yy < MOUTH_Y + jaw_px + 8) & (xx >= CUT_X[0] + 1) & (xx <= CUT_X[1] - 1)
    if jaw_px:
        sc.obj[gap_region & ~moved] = mouth
        sc.mat[gap_region & ~moved] = "gum"
        sc.z[gap_region & ~moved] = 5
    sc.z[moved] = 11
    sc.obj[moved] = chin
    sc.mat[moved] = "paper"

    # relief: the face behind the card, as a height field (card pixels)
    cx = W / 2
    def g(x0, y0, sx, sy):
        return np.exp(-(((xx - x0) / sx) ** 2 + ((yy - y0) / sy) ** 2))
    relief = (0.55 * g(cx, 0.52 * H, 70, 120) + 0.5 * g(cx + 2, 0.52 * H, 14, 48) * (yy > 0.36 * H)  # nose ridge
              + 0.45 * g(cx + 3, 0.62 * H, 20, 14)  # nose tip
              + 0.35 * g(EYE_L[0], EYE_L[1] - 22, 30, 10) + 0.35 * g(EYE_R[0], EYE_R[1] - 22, 30, 10)  # brows
              - 0.45 * g(EYE_L[0], EYE_L[1], 24, 16) - 0.45 * g(EYE_R[0], EYE_R[1], 24, 16)  # sockets
              + 0.3 * g(cx - 62, 0.5 * H, 22, 28) + 0.3 * g(cx + 64, 0.51 * H, 22, 28)  # cheekbones
              - 0.25 * g(cx - 48, 0.62 * H, 18, 30) - 0.25 * g(cx + 50, 0.63 * H, 18, 30)  # hollow cheeks
              + 0.3 * g(cx, MOUTH_Y - 6, 36, 8) + 0.3 * g(cx, MOUTH_Y + 8, 32, 8)  # lips
              + 0.35 * g(cx, 0.9 * H, 34, 20))  # chin
    gx = np.gradient(relief, axis=1) * 26
    gy = np.gradient(relief, axis=0) * 26
    shade = 0.62 * light - 0.5 * gx - 0.42 * gy + 0.12 * relief
    # paper: stains, handling grime toward the edges, creases
    n1 = noise2(W, th, 9, seed, 4)
    n2 = noise2(W, th, 22, seed + 3, 3)
    stain = np.clip(n2 - (0.58 - 0.12 * wear), 0, 1) * 3.0
    edge = np.zeros_like(mask)
    for k in range(1, 7):
        for dy, dx in ((0, k), (0, -k), (k, 0), (-k, 0)):
            edge |= ~np.roll(np.roll(mask, dy, 0), dx, 1)
    grime = edge.astype(float)
    v = shade + 0.16 * (n1 - 0.5) - 0.28 * stain - 0.16 * grime * (1 + wear)
    # index-card rules: faint blue lines, a red one near the top
    bent = yy - 40 - 9.0 * relief
    rules = (np.abs(((bent + 9.5) % 19) - 9.5) < 0.5) & (yy > 40)
    redline = np.abs(yy - 30 - 9.0 * relief) < 1.0
    paint = np.zeros((th, W, 3))
    R = RAMPS["paper"]
    b = BAYER4[yy % 4, xx % 4]
    idx = np.clip(np.floor(v * (len(R) - 1) + 0.5 + b), 0, len(R) - 1).astype(int)
    paint[:] = R[idx]
    blue = np.array([96.0, 112.0, 140.0])
    red = np.array([150.0, 62.0, 58.0])
    fade = (0.18 + 0.22 * noise2(W, th, 30, seed + 9, 2)) * (1 - 0.6 * np.clip(stain, 0, 1))
    paint[rules] = paint[rules] * (1 - fade[rules, None]) + blue * fade[rules, None]
    paint[redline] = paint[redline] * 0.5 + red * 0.5
    # the chin piece is drawn shifted
    if jaw_px:
        shifted = np.zeros_like(paint)
        shifted[jaw_px:] = paint[:th - jaw_px]
        paint[moved] = shifted[moved]
    sc.paint[mask | moved] = paint[mask | moved]
    sc.painted[mask | moved] = True
    sc.painted[jm & ~moved] = False
    # wet: darker, warmer, see-through around the eyes and mouth, a sheen
    wetm = (g(EYE_L[0], EYE_L[1], 30, 22) + g(EYE_R[0], EYE_R[1], 30, 22) + 1.2 * g(cx, MOUTH_Y + 4, 52, 24)) * wet
    wetm += 0.6 * wet * np.clip(noise2(W, th, 7, seed + 21, 2) - 0.62, 0, 1) * 3 * (g(cx, MOUTH_Y + 30, 60, 60) + 0.4)
    wetm = np.clip(wetm, 0, 1)
    flesh = RAMPS["flesh"][2]
    m_wet = (mask | moved) & (wetm > 0.08)
    sc.paint[m_wet] = sc.paint[m_wet] * (1 - 0.55 * wetm[m_wet, None]) + flesh * 0.55 * wetm[m_wet, None]
    hl = (-gx * 0.6 - gy * 0.8)
    sheen = m_wet & (wetm > 0.4) & (hl > 0.3) & (noise2(W, th, 3, seed + 33, 1) > 0.7) & (np.abs(yy - MOUTH_Y) > 10)
    sc.paint[sheen] = np.minimum(sc.paint[sheen] * 1.12 + 14, 255)
    # a typed number, top corner
    typed(sc, number, 16, 12, card)

    # eyes through torn holes
    for i, ((ex, ey), lk, lid) in enumerate(((EYE_L, look[0], lids[0]), (EYE_R, look[1], lids[1]))):
        hw_, hh = EYE_SIZE
        pts = []
        for a in np.linspace(0, 2 * np.pi, 26, endpoint=False):
            r = 1 + 0.18 * rng.normal()
            pts.append((ex + np.cos(a) * hw_ * 0.5 * r, ey + np.sin(a) * hh * 0.5 * r * (1.15 if np.sin(a) > 0 else 1.0)))
        holem = D.tear(sc, card, pts, rng, fibre="paper", inside="#0a0606")
        # the eye behind, set back inside a dark socket: from across a room the holes are two
        # dark spots; up close there is an eye at the bottom of each, wet.
        eyeball = sc.new_id()
        sc.obj[holem] = eyeball
        sc.mat[holem] = "bone"
        sc.painted[holem] = False
        dd = np.sqrt(((xx - ex) / (hw_ * 0.5)) ** 2 + ((yy - ey) / (hh * 0.5)) ** 2)
        sc.paint_ramp(holem, "gum", 0.05 + 0.12 * np.clip(1 - dd, 0, 1))
        D.eye(sc, eyeball, ex + 1, ey + 2, hw_ * 0.68, hh * 0.58, "gum", look=lk, iris=0.5, pupil=0.36 + 0.1 * i,
              red=0.55 + 0.3 * wear, seed=seed * 7 + i, socket=0.0, caps=12, lashes=True, wet=True, lower_lid=True)
        if lid > 0.02:
            top = ey + 2 - hh * 0.29
            line = top + hh * 0.62 * lid
            lidm = holem & (yy < line) & (dd < 0.75)
            sc.paint_ramp(lidm, "flesh", 0.28 + 0.2 * np.clip((yy - top) / max(hh * 0.6, 1), 0, 1))
            sc.paint_mask(holem & (np.abs(yy - line) < 0.8) & (dd < 0.72), "#1a0c0a")
        # the hole's lower rim is wet
        rim = (~holem) & np.roll(holem, -1, 0) & np.isin(sc.obj, [card])
        sc.paint_ramp(rim, "gum", 0.45)

    # nostrils: dark dents where the paper is pressed in
    for s in (-1, 1):
        sc.paint_ramp(sc.ell(cx + 3 + s * 11, 0.655 * H, 4.5, 2.6, -s * 0.4) & (sc.obj == card), "paper", 0.12)

    # the mouth: the card is cut at the lips; real lips show in the slit
    k = (xx - cx) / (MOUTH_W / 2)
    inside_w = np.abs(k) < 1
    curve = MOUTH_Y - smile * 9 * k ** 2 + 1.5 * k ** 2
    bow = 1.6 * np.exp(-((xx - cx) / 5) ** 2)  # the dip under the nose
    up_h = (6.5 - 4 * k ** 2 - bow) * (1 - k ** 6)
    upper_lip = inside_w & (yy < curve) & (yy > curve - up_h) & (sc.obj == card)
    sc.paint_ramp(upper_lip, "flesh", 0.24 + 0.1 * np.clip((yy - (curve - up_h)) / 6.0, 0, 1))
    if not jaw_px:
        lo_h = (8.5 - 6 * k ** 2) * (1 - k ** 6)
        lower_lip = inside_w & (yy >= curve + 1) & (yy < curve + 1 + lo_h) & np.isin(sc.obj, [chin, card])
        sc.paint_ramp(lower_lip, "flesh", 0.36 + 0.22 * np.clip(1 - np.abs((yy - curve - 1 - lo_h * 0.4) / np.maximum(lo_h, 1)) * 2, 0, 1))
        sc.paint_mask(inside_w & (np.abs(yy - curve) < 1.0 + 0.6 * (1 - k ** 2)) & (np.abs(k) < 0.97), "#1c0909")
        for i in range(12):
            x0 = cx + rng.uniform(-0.8, 0.8) * MOUTH_W / 2
            D.put(sc, x0, MOUTH_Y + 3 + rng.integers(0, 4), D.ramp_at("flesh", 0.15), [card, chin])
    # the cuts down from the corners of the mouth, and stitches across them
    for cxp in CUT_X:
        cm = card_mask()
        for y in range(int(MOUTH_Y), H):
            if cm[y, int(cxp)]:
                D.put(sc, cxp, y, "#140a08")
                D.put(sc, cxp + 1, y, D.ramp_at("paper", 0.25, cxp, y))
        n_st = 5
        for i in range(n_st):
            y = MOUTH_Y + 14 + i * (H - MOUTH_Y - 30) / n_st
            torn = jaw_px > 0 and rng.random() < 0.7
            for t in np.linspace(-1, 1, 15):
                for sgn in (-1, 1):
                    x = cxp + t * 7
                    yy1 = y + sgn * t * 4
                    if torn and t * sgn > 0:
                        yy1 += jaw_px
                    D.put(sc, x, yy1, "#2a1a14")
            if torn:
                # loose threads hanging into the gap
                for j in range(int(rng.integers(6, 16))):
                    D.put(sc, cxp + rng.normal(0, 1.2), y + j, "#3a281e")

    # behind the dropped chin: a real mouth
    if jaw_px:
        top = MOUTH_Y - 2
        # the cavity is an oval stretched between upper and lower lip; outside it the gap behind
        # the chin piece is wet dark membrane, the corners of the mouth pulled down the cuts
        oval = ((xx - cx) / ((CUT_X[1] - CUT_X[0]) / 2 - 3)) ** 2 + ((yy - (top + (jaw_px + 8) / 2)) / ((jaw_px + 10) / 2)) ** 2 <= 1
        membrane = (sc.obj == mouth) & ~oval
        sc.paint_ramp(membrane, "gum", 0.06 + 0.1 * noise2(W, th, 4, seed + 41, 2))
        for i in range(10):
            sgn = -1 if i % 2 else 1
            x0 = cx + sgn * rng.uniform(20, (CUT_X[1] - CUT_X[0]) / 2 - 3)
            for y in range(int(top), int(top + jaw_px + 8)):
                f = (y - top) / max(jaw_px + 8, 1)
                x = x0 + 2.5 * np.sin(f * np.pi) * sgn
                if 0 <= int(y) < th and 0 <= int(x) < W and (sc.obj[int(y), int(x)] == mouth) and not oval[int(y), int(x)]:
                    D.put(sc, x, y, D.ramp_at("flesh", 0.32 + 0.15 * np.sin(f * np.pi), x, y))
        for y in range(int(top), int(top + jaw_px + 8)):
            for sgn in (-1, 1):
                x = cx + sgn * ((CUT_X[1] - CUT_X[0]) / 2 - 4)
                D.put(sc, x, y, D.ramp_at("gum", 0.35, x, y), mouth)
        sub = Scene(W, th)
        D.maw(sc, mouth, cx, top + 2, CUT_X[1] - CUT_X[0] - 10, jaw_px + 4, seed=seed + 4, tooth_w=6.0, missing=0.12,
              saliva=6, curve=-4, tongue=True)
        stretched = (sc.obj == mouth) & oval & ~np.roll(oval, 3, 0) & (yy < top + 6)
        sc.paint_ramp(stretched, "flesh", 0.3)
        # a second row of small teeth further in
        for tx in np.arange(cx - 22, cx + 22, 4.5):
            D.tooth(sc, mouth, tx, top + 13, 3.6, 5, rng, down=True)
        # the stretched lower lip on the chin piece
        lower_lip = (np.abs(xx - cx) < MOUTH_W / 2 - 4) & (yy >= MOUTH_Y + jaw_px) & (yy < MOUTH_Y + jaw_px + 6) & (sc.obj == chin)
        sc.paint_ramp(lower_lip, "flesh", 0.42)
        D.drips(sc, chin, cx - 26, cx + 26, int(MOUTH_Y + jaw_px + 6), 7, rng, "gum", (6, 26), 0.35)

    # pins: short, at the corners, one at a temple, one in the chin piece
    pins = [((20, 22), (-14, -8)), ((W - 18, 16), (W + 16, -14)), ((W - 14, 0.36 * H), (W + 22, 0.33 * H)),
            ((cx - 14, 0.9 * H + jaw_px), (cx - 52, 0.97 * H + jaw_px + 10))]
    for p0, p1 in pins:
        D.pin(sc, p0, p1, head=3.5, wound_obj=[card, chin], wound_ramp="rust", drip=1 + int(blood * 3), rng=rng,
              wound=2.5)
    if blood:
        for (ex, ey) in (EYE_L, EYE_R):
            D.drips(sc, card, ex - 10, ex + 10, int(ey + 12), int(2 + blood * 4), rng, "red", (8, 40), 0.3)
    if wear:
        for _ in range(int(2 + wear * 6)):
            x0 = rng.uniform(10, W - 10)
            y0 = rng.uniform(40, H - 60)
            D.walk(sc, x0, y0, rng.uniform(0, 2 * np.pi), int(20 + 40 * wear), rng, colour=D.ramp_at("paper", 0.25),
                   only=card, wobble=0.25, branch=0.05)

    img = sc.render(outline=False)
    alpha = sc.obj >= 0
    return img, alpha


def typed(sc, text, x, y, obj):
    """A few typed characters (3x5 pixel font), faded."""
    font = {"0": ["111", "101", "101", "101", "111"], "1": ["010", "110", "010", "010", "111"],
            "2": ["111", "001", "111", "100", "111"], "3": ["111", "001", "111", "001", "111"],
            "4": ["101", "101", "111", "001", "001"], "5": ["111", "100", "111", "001", "111"],
            "6": ["111", "100", "111", "101", "111"], "7": ["111", "001", "010", "010", "010"],
            "8": ["111", "101", "111", "101", "111"], "9": ["111", "101", "111", "001", "111"],
            "N": ["101", "111", "111", "111", "101"], "o": ["000", "111", "101", "101", "111"], ".": ["000", "000", "000", "000", "010"]}
    cx = x
    for ch in text:
        glyph = font.get(ch)
        if glyph is None:
            cx += 4
            continue
        for gy, row in enumerate(glyph):
            for gx, bit in enumerate(row):
                if bit == "1":
                    D.put(sc, cx + gx, y + gy, "#3c3428", obj)
        cx += 4
