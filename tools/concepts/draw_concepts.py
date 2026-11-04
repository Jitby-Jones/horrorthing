#!/usr/bin/env python3
"""Pixel-art concept sheets for the creature, drawn in code (reference art for the 3D model).

    python tools/concepts/draw_concepts.py [keys...]

Writes docs/concepts/<key>.png. Each sheet has two rows:
  front, side, the corner-of-the-eye test in a dark room, far away (as a giant), the silhouette;
  the face, the face when it reaches you, and one close detail.
Scale: 80 pixels per block (a three-block creature is 240 pixels), faces at 300 pixels.
"""
import os
import sys

import numpy as np
from PIL import Image, ImageDraw

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import details as D  # noqa: E402
from pixelart import BAYER4, RAMPS, Scene, font, noise2, rgb, upscale, wallpaper  # noqa: E402

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
OUT = os.path.join(ROOT, "docs", "concepts")
U = 80  # pixels per block
K = 2  # display scale
ROW1 = 288
GROUND = 276
F = 300  # face panel size
RIM = 0.32


class Body:
    """Maps block coordinates (x right, y up, z toward the viewer) to pixels."""

    def __init__(self, sc, cx, ground=GROUND, s=U, flip=1):
        self.sc, self.cx, self.ground, self.s, self.flip = sc, cx, ground, s, flip

    @property
    def k(self):
        return self.s / U

    def p(self, x, y, z=0.0):
        return (self.cx + self.flip * x * self.s, self.ground - y * self.s, z * self.s)

    def xy(self, x, y):
        return self.p(x, y)[:2]

    def limb(self, a, b, r0, r1=None, mat="black", obj=None):
        r1 = r0 if r1 is None else r1
        return self.sc.capsule(self.p(*a), self.p(*b), r0 * self.s, r1 * self.s, mat, obj)

    def ball(self, c, r, mat="black", obj=None, ry=None, rz=None, angle=0.0):
        ry = r if ry is None else ry
        rz = r if rz is None else rz
        return self.sc.ellipsoid(self.p(*c), r * self.s, ry * self.s, rz * self.s, mat, obj, angle=angle * self.flip)

    def chain(self, pts, radii, mat="black", obj=None, knots=0.0):
        obj = self.sc.new_id() if obj is None else obj
        for i in range(len(pts) - 1):
            self.limb(pts[i], pts[i + 1], radii[i], radii[i + 1], mat, obj)
            if knots and 0 < i:
                self.ball(pts[i], radii[i] * (1 + knots), mat, obj)
        return obj

    def line(self, a, b, mat, obj=None, flat=0.6, width=1):
        return self.sc.line(self.p(*a), self.p(*b), mat, obj, width=width, flat=flat)

    def poly(self, pts, z, mat, obj=None, normal=(0, 0, 1), flat=None):
        return self.sc.polygon([self.xy(x, y) for x, y in pts], z * self.s, mat, obj, normal=normal, flat=flat)

    def hand(self, wrist, angle, mat="pale", length=0.42, spread=0.2, curl=0.25, obj=None, palm=0.05, thumb=True,
             nails=True, r=0.016, knuckle=1.25):
        """A long-fingered hand from the wrist; angle 0 points down, positive toward +x."""
        obj = self.sc.new_id() if obj is None else obj
        z = wrist[2] if len(wrist) > 2 else 0.0
        d = np.array([np.sin(angle), -np.cos(angle)])
        perp = np.array([d[1], -d[0]])
        c = np.array(wrist[:2]) + d * palm * 1.2
        self.ball((c[0], c[1], z), palm * 0.85, mat, obj, ry=palm * 1.25, rz=palm * 0.45, angle=-angle)
        sgn = 1 if np.sin(angle) >= 0 else -1
        tips = []
        for k in range(4):
            off = (k - 1.5) / 1.5
            base = c + d * palm * 1.0 + perp * off * palm * 0.8
            a = angle + spread * off
            ln = length * (1.0 - 0.16 * abs(off) - (0.12 if k == 3 else 0))
            p = base
            rr = r
            for j, f in enumerate((0.44, 0.32, 0.24)):
                dd = np.array([np.sin(a), -np.cos(a)])
                q = p + dd * ln * f
                self.limb((p[0], p[1], z + 0.01), (q[0], q[1], z + 0.01), rr, rr * 0.86, mat, obj)
                if j < 2:
                    self.ball((q[0], q[1], z + 0.012), rr * knuckle, mat, obj)
                p = q
                rr *= 0.86
                a += curl * sgn * (j + 1) * 0.5
            tips.append((p, a))
        if thumb:
            a = angle - 0.85 * sgn
            base = c - perp * palm * 0.75 * sgn
            dd = np.array([np.sin(a), -np.cos(a)])
            q = base + dd * length * 0.3
            q2 = q + np.array([np.sin(a + 0.4 * sgn), -np.cos(a + 0.4 * sgn)]) * length * 0.18
            self.limb((base[0], base[1], z), (q[0], q[1], z), r * 1.05, r * 0.95, mat, obj)
            self.ball((q[0], q[1], z), r * knuckle, mat, obj)
            self.limb((q[0], q[1], z), (q2[0], q2[1], z), r * 0.95, r * 0.8, mat, obj)
            tips.append((q2, a))
        self.tips = tips
        return obj


def nails(sc, b, tips, obj, col="#3a2e22"):
    for p, a in tips:
        x, y = b.xy(*p)
        D.put(sc, x, y, col, obj)
        D.put(sc, x + np.sin(a) * 0.6, y + np.cos(a) * 0.6, col, obj)


# --------------------------------------------------------------------------------------------
# Backgrounds
# --------------------------------------------------------------------------------------------

def front_bg(w, h=ROW1):
    bg = wallpaper(w, h, 11, dark=0.04)
    yy, xx = np.mgrid[0:h, 0:w]
    floor = yy >= GROUND - 2
    planks = RAMPS["wood"][np.clip(3 + ((xx // 22 + (yy // 6)) % 2) - (yy == GROUND - 2), 0, 5)]
    bg[floor] = planks[floor]
    return bg


def side_bg(w, h=ROW1):
    g = np.zeros((h, w, 3))
    yy, xx = np.mgrid[0:h, 0:w]
    v = 0.55 + 0.1 * (noise2(w, h, 30, 2, 2) - 0.5)
    R = RAMPS["grey"]
    b = BAYER4[yy % 4, xx % 4]
    g[:] = R[np.clip(np.floor(v * 7 + 0.5 + b), 0, 7).astype(int)]
    g[GROUND - 2:] = rgb("#4d4943")
    return g


def dark_room(w=330, h=ROW1, seed=5):
    bg = wallpaper(w, h, seed, dark=0.6, ramp="wall")
    yy, xx = np.mgrid[0:h, 0:w]
    corner = 96
    bg[xx < corner] *= 0.6
    bg[(xx >= corner - 1) & (xx <= corner)] *= 0.45
    floor = yy >= GROUND - 2
    planks = RAMPS["wood"][np.clip(2 + ((xx // 22 + (yy // 6)) % 2), 0, 5)]
    bg[floor] = planks[floor] * 0.42
    bg[floor & (xx < corner + (yy - GROUND) * -1.2)] *= 0.8
    d0, d1, dtop = w - 100, w - 20, GROUND - 168
    door = (xx >= d0) & (xx < d1) & (yy >= dtop) & (yy < GROUND - 2)
    bg[door] = RAMPS["night"][0]
    frame = (((xx >= d0 - 6) & (xx < d0)) | ((xx >= d1) & (xx < d1 + 6))) & (yy >= dtop - 6) & (yy < GROUND - 2)
    frame |= (yy >= dtop - 6) & (yy < dtop) & (xx >= d0 - 6) & (xx < d1 + 6)
    fr = RAMPS["wood"][np.clip(2 + (xx % 3 == 0), 0, 5)] * 0.5
    bg[frame] = fr[frame]
    # skirting board
    sk = (yy >= GROUND - 10) & (yy < GROUND - 2) & ~door & ~frame
    bg[sk] = RAMPS["wood"][1] * 0.8
    return bg, (d0, d1, dtop)


def far_scene(w=140, h=140):
    yy, xx = np.mgrid[0:h, 0:w]
    t = yy / h
    R = RAMPS["sky"]
    b = BAYER4[yy % 4, xx % 4]
    bg = R[np.clip(np.floor(t * 1.3 * (len(R) - 1) + 0.5 + b), 0, len(R) - 1).astype(int)]
    ridge = h - 34 + 6 * np.sin(xx * 0.05 + 1.0) + 3 * np.sin(xx * 0.13)
    bg[yy > ridge] = RAMPS["leaf"][1]

    def trees(sc):
        rng = np.random.default_rng(4)
        for x in range(-4, w + 6, 15):
            top = h - 52 - rng.integers(0, 16)
            sc.polygon([(x, top), (x + 16, top), (x + 16, h), (x, h)], 500, "leaf", None, flat=0.22 + 0.12 * rng.random())
            sc.polygon([(x + 6, top + 14), (x + 10, top + 14), (x + 10, h), (x + 6, h)], 501, "wood", None, flat=0.2)
    return bg, trees


# --------------------------------------------------------------------------------------------
# Concept base and sheet layout
# --------------------------------------------------------------------------------------------

class Concept:
    key = ""
    title = ""
    lines = []
    detail_label = "detail"

    def figure(self, b, view):
        raise NotImplementedError

    def texture(self, sc, b, view, dark=False):
        pass

    def corner(self, sc, door):
        b = Body(sc, 52)
        self.figure(b, "front")
        self.texture(sc, b, "front", dark=True)

    def face(self, attack=False):
        raise NotImplementedError

    def detail(self):
        raise NotImplementedError

    def panels(self):
        row1 = []
        sc = Scene(200, ROW1)
        sc.bg[:] = front_bg(200)
        b = Body(sc, 100)
        self.figure(b, "front")
        self.texture(sc, b, "front")
        row1.append(("front", sc.render(rim=RIM)))

        sc = Scene(180, ROW1)
        sc.bg[:] = side_bg(180)
        b = Body(sc, 80)
        self.figure(b, "side")
        self.texture(sc, b, "side")
        row1.append(("side", sc.render(rim=RIM)))

        room, door = dark_room()
        sc = Scene(330, ROW1)
        sc.bg[:] = room
        sc.amb, sc.dif = 0.05, 0.2
        self.corner(sc, door)
        row1.append(("corner of the eye, dark room", sc.render(rim=0.08)))

        bg, trees = far_scene()
        sc = Scene(140, 140)
        sc.bg[:] = bg
        sc.amb, sc.dif = 0.1, 0.4
        b = Body(sc, 76, ground=124, s=32)
        self.figure(b, "far")
        self.texture(sc, b, "far")
        trees(sc)
        far = sc.render(outline=False)
        sc = Scene(140, 144)
        sc.bg[:] = rgb("#cbc5b8")
        b = Body(sc, 70, ground=138, s=38)
        self.figure(b, "front")
        sil = sc.render(outline=False)
        sil[sc.obj >= 0] = rgb("#0b0a0c")
        row1.append(("far away / bare silhouette", np.concatenate([far, np.full((4, 140, 3), 21.0), sil], 0)))
        row2 = [("face", self.face(False)), ("when it reaches you", self.face(True)), (self.detail_label, self.detail())]
        return row1, row2


def sheet(title, lines, rows, out):
    gap = 6
    W = max(sum(p.shape[1] for _, p in r) + gap * (len(r) + 1) for r in rows)
    label_h = 12
    H = sum(max(p.shape[0] for _, p in r) + gap + label_h for r in rows) + gap
    canvas = np.zeros((H, W, 3))
    canvas[:] = rgb("#151316")
    labels = []
    y = gap
    for r in rows:
        x = gap
        rh = max(p.shape[0] for _, p in r)
        for label, p in r:
            canvas[y:y + p.shape[0], x:x + p.shape[1]] = p
            labels.append((x, y + rh + 1, label))
            x += p.shape[1] + gap
        y += rh + gap + label_h
    img = upscale(canvas, K)
    head = 40 + 22 * len(lines) + 10
    full = Image.new("RGB", (img.width, img.height + head), (21, 19, 22))
    full.paste(img, (0, head))
    d = ImageDraw.Draw(full)
    d.text((18, 10), title, fill=(236, 228, 210), font=font(30, bold=True, serif=True))
    for i, t in enumerate(lines):
        d.text((20, 50 + i * 22), t, fill=(175, 165, 150), font=font(17))
    for x, yl, label in labels:
        d.text((x * K + 4, head + yl * K), label, fill=(150, 140, 125), font=font(16))
    full.save(out)
    return full


def face_scene(bg="#141215"):
    sc = Scene(F, F)
    sc.bg[:] = rgb(bg) if isinstance(bg, str) else bg
    return sc


# ===== the shared flat-black stick body ============================================================

def stick_body(b, view, arm_len=1.0, hunch=0.0, hands="black", pins=False):
    """Flat-black, knotted, starved. Returns (neck top, body id, hand ids)."""
    sc = b.sc
    body = sc.new_id()
    hand_ids = []
    rng = np.random.default_rng(7)
    if view != "side":
        for s in (-1, 1):
            b.chain([(s * 0.09, 1.42, 0), (s * 0.115, 1.08, 0.01), (s * 0.125, 0.76, 0.02), (s * 0.11, 0.4, 0.01),
                     (s * 0.105, 0.07, 0)], [0.042, 0.03, 0.036, 0.026, 0.028], obj=body, knots=0.45)
            for t in range(4):
                b.limb((s * 0.105, 0.07, 0), (s * (0.12 + t * 0.02), 0.0, 0.08 + 0.02 * t), 0.012, 0.008, obj=body)
        b.ball((0, 1.46, 0), 0.125, obj=body, ry=0.07)
        for s in (-1, 1):
            b.ball((s * 0.1, 1.5, 0.02), 0.05, obj=body, ry=0.04)
        spine = [(0, 1.48 + i * 0.05, 0) for i in range(8)]
        b.chain(spine, [0.024] * 8, obj=body)
        for i, p in enumerate(spine[1:-1]):
            b.ball(p, 0.034, obj=body, ry=0.022)
        b.ball((0, 1.98, 0), 0.16, obj=body, ry=0.21)
        b.limb((-0.22, 2.2, 0), (0.22, 2.2, 0), 0.032, obj=body)
        for s in (-1, 1):
            b.ball((s * 0.22, 2.2, 0), 0.05, obj=body)
            el = (s * 0.27, 2.2 - 0.58 * arm_len, 0.02)
            wr = (s * 0.3, 2.2 - 1.16 * arm_len, 0.05)
            b.chain([(s * 0.22, 2.2, 0), el, wr], [0.036, 0.026, 0.024], obj=body, knots=0.7)
            if hands == "black":
                b.hand(wr, s * 0.06, "black", length=0.45, spread=0.12, curl=0.1, obj=body, r=0.012, knuckle=1.5)
            else:
                hand_ids.append(b.hand(wr, s * 0.06, hands, length=0.45, spread=0.14, curl=0.12, r=0.013))
        b.chain([(0, 2.18, 0), (0.0, 2.33, 0), (0, 2.48, 0)], [0.032, 0.026, 0.026], obj=body)
        b.ball((0, 2.3, 0), 0.034, obj=body)
        return (0, 2.48, 0), body, hand_ids
    # side view: stooped, arms forward, spine spurs
    b.chain([(0.0, 1.42, 0), (0.06, 1.08, 0), (0.08, 0.76, 0.0), (0.02, 0.4, 0), (-0.02, 0.07, 0)],
            [0.042, 0.03, 0.036, 0.026, 0.028], obj=body, knots=0.45)
    for t in range(3):
        b.limb((-0.02, 0.07, 0), (0.16 + t * 0.01, 0.0, 0.0), 0.014, 0.008, obj=body)
    b.chain([(0.03, 1.42, -.03), (0.0, 1.08, -0.03), (-0.04, 0.76, -0.03), (-0.07, 0.4, -0.03), (-0.08, 0.07, -0.03)],
            [0.042, 0.03, 0.036, 0.026, 0.028], obj=body, knots=0.45)
    for t in range(3):
        b.limb((-0.08, 0.07, -0.03), (0.08 + t * 0.01, 0.0, -0.03), 0.014, 0.008, obj=body)
    b.ball((0, 1.46, 0), 0.09, obj=body, ry=0.08)
    spine = [(-0.01 - 0.03 * np.sin(i / 7 * np.pi), 1.48 + i * 0.052, 0) for i in range(8)]
    b.chain(spine, [0.024] * 8, obj=body)
    for p in spine[1:-1]:
        b.ball(p, 0.03, obj=body)
        b.limb(p, (p[0] - 0.06, p[1] - 0.02, 0), 0.012, 0.004, obj=body)
    b.ball((0.03 + hunch * 0.05, 2.0, 0), 0.12, obj=body, ry=0.2, angle=-0.25)
    sh = (0.08 + hunch * 0.1, 2.2, 0)
    el = (0.16 + hunch * 0.05, 2.2 - 0.56 * arm_len, 0.0)
    wr = (0.26 + hunch * 0.05, 2.2 - 1.12 * arm_len, 0)
    b.chain([sh, el, wr], [0.036, 0.026, 0.024], obj=body, knots=0.7)
    if hands == "black":
        b.hand(wr, 0.25, "black", length=0.45, spread=0.06, curl=0.12, obj=body, r=0.012, knuckle=1.5, thumb=False)
    else:
        hand_ids.append(b.hand(wr, 0.25, hands, length=0.45, spread=0.08, curl=0.14, r=0.013, thumb=False))
    top = (0.2 + hunch * 0.12, 2.47, 0)
    b.chain([sh, ((sh[0] + top[0]) / 2 + 0.02, 2.34, 0), top], [0.03, 0.026, 0.026], obj=body)
    return top, body, hand_ids


# ===== A: the card ================================================================================

class Card(Concept):
    key = "a_card"
    title = "A.  The Card  (the creature as it is now: the baseline to beat)"
    lines = ["Flat-black stick body. A stained card for a head with a real face pressed into it, a halo of pins, wet strings.",
             "The card is cut across at the lips; the lower part is a hinged jaw that drops away into a wet slot of teeth."]
    detail_label = "pins through the card's edge, strings"

    def figure(self, b, view):
        top, self.body, _ = stick_body(b, view, hunch=0.5 if view == "side" else 0)
        sc = b.sc
        hx, hy = 0.135, 0.245
        cx, cy = top[0], top[1] + hy * 0.9
        self.card = sc.new_id()
        self.jaw = None
        if view == "side":
            t = (cx + 0.12, cy + hy, 0.0)
            bo = (cx - 0.02, cy - hy, 0.0)
            sc.capsule(b.p(*t), b.p(*bo), 0.02 * b.s, 0.02 * b.s, "paper", self.card)
            for i in range(7):
                f = i / 6
                p = (t[0] * (1 - f) + bo[0] * f, t[1] * (1 - f) + bo[1] * f)
                b.line((p[0] - 0.02, p[1], 0.1), (p[0] - 0.32, p[1] + (i - 3) * 0.07, 0.1), "metal", flat=0.8)
            self.c = b.xy(cx, cy)
            return
        tw, bw = hx * 1.12, hx * 0.92
        split = cy - hy * 0.48
        b.poly([(cx - tw, cy + hy), (cx + tw, cy + hy), (cx + bw, split), (cx - bw, split)], 0.25, "paper", self.card,
               normal=(-0.15, -0.1, 1))
        self.jaw = sc.new_id()
        b.poly([(cx - bw, split), (cx + bw, split), (cx + bw * 0.9, cy - hy), (cx - bw * 0.9, cy - hy)], 0.24, "paper",
               self.jaw, normal=(-0.15, -0.1, 1))
        rng = np.random.default_rng(1)
        self.pins = []
        for i in range(11):
            a = -np.pi / 2 + (i - 5) * 0.27 + rng.normal(0, 0.06)
            ex, ey = cx + np.cos(a) * hx * 1.0, cy + np.sin(-a) * hy * 0.85 + hy * 0.15
            ln = 0.22 + 0.16 * rng.random()
            self.pins.append((b.xy(ex, ey), b.xy(ex + np.cos(a) * ln, ey - np.sin(a) * ln)))
        rng = np.random.default_rng(4)
        for i in range(9):
            x0 = cx - bw * 0.85 + i * bw * 1.7 / 8
            y0 = cy - hy
            ln = 0.12 + 0.25 * rng.random()
            pts = [(x0 + 0.012 * np.sin(k * 1.3 + i), y0 - ln * k / 5, 0.28) for k in range(6)]
            for a2, c2 in zip(pts[:-1], pts[1:]):
                b.line(a2, c2, "bone", flat=0.6 + 0.2 * rng.random())
        self.c = b.xy(cx, cy)
        self.cs = (hx, hy)

    def texture(self, sc, b, view, dark=False):
        if view == "side":
            return
        k = b.k
        D.skin(sc, self.card, "paper", 3, offset=(-0.45 if dark else 0.0) + 0.05, mottle=0.2, blotches=0.25, edge=0.12)
        D.skin(sc, self.jaw, "paper", 4, offset=(-0.48 if dark else 0.0), mottle=0.2, blotches=0.25, edge=0.12)
        cx, cy = self.c
        fy = cy + 0.07 * b.s
        if k > 0.6:
            for s, dy in ((-1, 0.0), (1, 0.02)):
                D.eye(sc, self.card, cx + s * 0.055 * b.s, fy + dy * b.s, 0.06 * b.s, 0.03 * b.s, "paper",
                      look=(-0.2 * s, 0.2), socket=1.0, red=0.4, seed=2 + s, caps=2, iris=0.5,
                      lashes=k > 0.9, wet=not dark)
            D.nose(sc, self.card, cx + 1, fy - 0.005 * b.s, fy + 0.085 * b.s, 0.035 * b.s, "paper", light=-0.05 if dark else 0.0)
            D.lips(sc, [self.card, self.jaw], cx, cy - 0.117 * b.s, 0.09 * b.s, "flesh", v=-0.35 if dark else -0.1,
                   chapped=False)
            if dark:
                for s in (-1, 1):
                    D.put(sc, cx + s * 0.055 * b.s, fy + 0.005 * b.s, "#f3eee0")
        else:
            for s in (-1, 1):
                sc.paint_mask(sc.ell(cx + s * 0.055 * b.s, fy, 1.0, 0.7), "#1e1612", only_obj=self.card)
        rng = np.random.default_rng(5)
        for p0, p1 in self.pins:
            D.pin(sc, p0, p1, head=max(1.0, 1.6 * k), wound_obj=self.card if k > 0.6 else None, wound_ramp="rust",
                  drip=0, rng=rng, light=0.45 if dark else 1.0, wound=0.9)

    def corner(self, sc, door):
        b = Body(sc, 52)
        self.figure(b, "front")
        self.texture(sc, b, "front", dark=True)

    def face(self, attack=False):
        sc = face_scene()
        rng = np.random.default_rng(11)
        card = sc.new_id()
        split = 206
        jaw = 70 if attack else 0
        sc.polygon([(22, 6), (278, 6), (264, split), (36, split)], 10, "paper", card, normal=(-0.1, -0.12, 1))
        jo = sc.new_id()
        sc.polygon([(36, split + jaw), (264, split + jaw), (258, 296 + jaw), (42, 296 + jaw)], 10, "paper", jo,
                   normal=(-0.1, -0.05, 1))
        slot = None
        if attack:
            slot = sc.new_id()
            sc.polygon([(40, split - 2), (260, split - 2), (260, split + jaw + 2), (40, split + jaw + 2)], 5, "gum",
                       slot, flat=0.1)
        D.skin(sc, card, "paper", 3, offset=0.02, mottle=0.18, blotches=0.3, edge=0.15)
        D.skin(sc, jo, "paper", 4, offset=-0.04, mottle=0.18, blotches=0.3, edge=0.15)
        # creases from being handled
        for (x0, y0, x1, y1) in ((22, 120, 278, 104), (150, 6, 140, 206)):
            for i in range(260):
                t = i / 259
                x, y = x0 + (x1 - x0) * t, y0 + (y1 - y0) * t
                D.put(sc, x, y, D.ramp_at("paper", 0.45, x, y), card)
                D.put(sc, x + 1, y + 1, D.ramp_at("paper", 0.9, x, y), card)
        # the pressed face, low on the card, too small for it
        D.eye(sc, card, 112, 146, 40, 19, "paper", look=(0.3, 0.1), socket=1.2, red=0.55, seed=3, tilt=0.03)
        D.eye(sc, card, 190, 155, 40, 19, "paper", look=(-0.45, 0.3), socket=1.2, red=0.55, seed=4, tilt=-0.05,
              droop=0.4)
        D.wrinkles(sc, card, 112, 164, 40, 6, rng, "paper", angle=0.1, spread=0.15, length=0.6)
        D.wrinkles(sc, card, 190, 173, 40, 6, rng, "paper", angle=-0.1, spread=0.15, length=0.6)
        D.nose(sc, card, 151, 140, 190, 26, "paper", hook=2, light=0.05)
        if not attack:
            D.lips(sc, [card, jo], 151, split, 64, "flesh", curve=-1.5, v=-0.1, seed=3, thin=1.3)
        else:
            D.lips(sc, card, 151, split - 4, 64, "flesh", curve=-1.5, v=-0.1, seed=3, thin=1.0, part=0)
            D.maw(sc, slot, 150, split, 220, jaw + 2, seed=5, tooth_w=13, missing=0.15, saliva=7, tongue=True)
            D.lips(sc, jo, 151, split + jaw + 3, 64, "flesh", curve=-1.0, v=-0.15, seed=4, thin=1.0)
        # stains and handling grime
        D.drips(sc, card, 40, 260, 8, 9, rng, "rust", (5, 30), 0.25)
        # pins: through the forehead, a cheek, the brow, out of the edges
        for p0, p1, dr in (((120, 52), (82, -4), 3), ((228, 70), (300, 30), 2), ((150, 108), (156, -2), 2),
                           ((86, 182), (-2, 214), 2), ((214, 186), (300, 228), 1), ((40, 30), (-10, 4), 1),
                           ((262, 20), (300, -6), 1)):
            D.pin(sc, p0, p1, head=4.5, wound_obj=card, wound_ramp="rust", drip=dr, rng=rng)
        return sc.render(rim=0.0)

    def detail(self):
        sc = Scene(240, F)
        sc.bg[:] = wallpaper(240, F, 17, dark=0.1)
        rng = np.random.default_rng(3)
        card = sc.new_id()
        sc.polygon([(20, -10), (200, -10), (190, 150), (30, 150)], 10, "paper", card, normal=(-0.1, -0.1, 1))
        D.skin(sc, card, "paper", 9, mottle=0.2, blotches=0.35, edge=0.2)
        neck = sc.new_id()
        sc.capsule((110, 150, 0), (100, 320, 0), 14, 16, "black", neck)
        for i in range(4):
            sc.ellipsoid((108 - i * 2, 175 + i * 34, 2), 17, 10, 6, "black", neck)
        for i in range(14):
            x0 = 34 + i * 11
            ln = rng.uniform(40, 150)
            pts = [(x0 + 3 * np.sin(j * 0.8 + i), 150 + ln * j / 8, 20) for j in range(9)]
            obj = sc.new_id()
            sc.polyline(pts, "bone", obj, width=2, flat=0.62 + 0.2 * rng.random())
            end = pts[-1]
            D.put(sc, end[0], end[1] + 1, "#d8d0bc")
            D.put(sc, end[0], end[1] + 2, "#8a8274")
        img_pins = [((40, 40), (-10, 10)), ((60, 120), (-10, 150)), ((180, 30), (250, 0)), ((176, 110), (250, 140)),
                    ((112, 200), (190, 236)), ((100, 250), (20, 290))]
        for p0, p1 in img_pins:
            D.pin(sc, p0, p1, head=5, wound_obj=[card, neck], wound_ramp="rust", drip=2, rng=rng)
        D.drips(sc, card, 30, 190, 60, 6, rng, "rust", (8, 40), 0.25)
        return sc.render(rim=0.4)


# ===== B: the puppeteer ================================================================================

class Puppeteer(Concept):
    key = "b_puppeteer"
    title = "B.  The Puppeteer  (black-light theatre: a face and two hands)"
    lines = ["Everything is matte black except a pale, too-long face and two pale hands with half-block fingers. The face",
             "sits on a thin rod of a neck that lengthens: it peeks round door frames while the body stays hidden. In the dark",
             "you see three pale things: a face, and two hands holding the edge of the wall."]
    detail_label = "its hand on the door frame"

    def figure(self, b, view):
        sc = b.sc
        body = sc.new_id()
        self.hands = []
        if view != "side":
            for s in (-1, 1):
                b.chain([(s * 0.08, 1.3, 0), (s * 0.15, 0.7, 0.04), (s * 0.1, 0.06, 0)], [0.044, 0.036, 0.03], obj=body,
                        knots=0.5)
                for t in range(4):
                    b.limb((s * 0.1, 0.06, 0), (s * (0.12 + 0.025 * t), 0.0, 0.08 + 0.02 * t), 0.012, 0.008, obj=body)
            b.ball((0, 1.34, 0), 0.1, obj=body, ry=0.08)
            b.chain([(0, 1.34, 0), (0, 1.5, 0)], [0.035, 0.04], obj=body)
            b.ball((0, 1.72, 0), 0.17, obj=body, ry=0.24)
            for s in (-1, 1):
                b.ball((s * 0.2, 1.98, 0.0), 0.065, obj=body)
                b.limb((0, 1.95, 0), (s * 0.2, 1.98, 0), 0.042, obj=body)
                el, wr = (s * 0.31, 1.45, 0.02), (s * 0.3, 0.9, 0.06)
                b.chain([(s * 0.2, 1.98, 0), el, wr], [0.046, 0.034, 0.028], obj=body, knots=0.5)
                self.hands.append((b.hand(wr, s * 0.08, "pale", length=0.44, spread=0.22, curl=0.2), list(b.tips)))
            b.chain([(0, 1.95, 0), (0.02, 2.2, 0), (-0.015, 2.45, 0), (0.0, 2.6, 0)], [0.022, 0.018, 0.018, 0.018],
                    obj=body)
            self.face_obj = b.ball((0, 2.8, 0.03), 0.15, "pale", ry=0.24, rz=0.06)
            self.fc = b.xy(0, 2.8)
            return
        b.chain([(0.0, 1.3, 0), (0.14, 0.72, 0), (0.0, 0.06, 0)], [0.044, 0.036, 0.03], obj=body, knots=0.5)
        b.limb((0.0, 0.06, 0), (0.17, 0.0, 0), 0.03, 0.012, obj=body)
        b.chain([(0.04, 1.3, -0.02), (0.2, 0.75, -0.02), (0.06, 0.06, -0.02)], [0.044, 0.036, 0.03], obj=body, knots=0.5)
        b.limb((0.06, 0.06, -0.02), (0.22, 0.0, -0.02), 0.03, 0.012, obj=body)
        b.ball((0, 1.34, 0), 0.09, obj=body, ry=0.09)
        b.ball((0.04, 1.72, 0), 0.13, obj=body, ry=0.25, angle=-0.4)
        b.ball((0.1, 1.98, 0.02), 0.07, obj=body)
        b.chain([(0.1, 1.98, 0.02), (0.24, 1.48, 0.02), (0.34, 0.95, 0.04)], [0.046, 0.034, 0.028], obj=body, knots=0.5)
        self.hands.append((b.hand((0.34, 0.95, 0.04), 0.25, "pale", length=0.44, spread=0.08, curl=0.22, thumb=False),
                           list(b.tips)))
        b.chain([(0.08, 1.98, 0), (0.12, 2.25, 0), (0.22, 2.5, 0), (0.36, 2.62, 0)], [0.022, 0.018, 0.018, 0.018], obj=body)
        self.face_obj = b.ball((0.42, 2.72, 0.0), 0.05, "pale", ry=0.24, rz=0.12, angle=-0.25)
        self.fc = b.xy(0.42, 2.72)

    def texture(self, sc, b, view, dark=False):
        off = 0.1 if dark else 0.04
        D.skin(sc, self.face_obj, "pale", 7, offset=off, mottle=0.1)
        for h, tips in self.hands:
            D.skin(sc, h, "pale", 9, offset=off - 0.02, mottle=0.1)
            nails(sc, b, tips, h)
        if view == "side":
            return
        k = b.k
        fx, fy = self.fc
        for s in (-1, 1):
            D.hole(sc, self.face_obj, fx + s * 0.065 * b.s, fy - 0.03 * b.s, max(0.034 * b.s, 0.8),
                   max(0.05 * b.s, 1.0), "pale", glints=((0.0, 0.3),) if (dark or k > 0.9) else ())
        if k > 0.6:
            D.nose(sc, self.face_obj, fx + 0.5, fy - 0.02 * b.s, fy + 0.09 * b.s, 0.03 * b.s, "pale", nostrils=k > 0.9)
            D.lips(sc, self.face_obj, fx, fy + 0.16 * b.s, 0.09 * b.s, "pale", curve=0.012 * b.s, v=-0.2, chapped=False,
                   thin=0.6)
        else:
            sc.paint_mask(sc.seg((fx - 0.035 * b.s, fy + 0.165 * b.s), (fx + 0.035 * b.s, fy + 0.16 * b.s)), "#2b1c1c",
                          only_obj=self.face_obj)

    def corner(self, sc, door):
        d0, d1, dtop = door
        b = Body(sc, d0 - 2)
        body = sc.new_id()
        b.chain([(0.5, 1.0, -0.5), (0.35, 1.62, -0.4), (0.15, 1.86, -0.2), (-0.02, 1.9, 0.0)], [0.03, 0.022, 0.018, 0.018],
                obj=body)
        self.face_obj = b.ball((-0.13, 1.82, 0.05), 0.14, "pale", ry=0.22, rz=0.06, angle=0.18)
        self.fc = b.xy(-0.13, 1.82)
        self.hands = []
        for (y, a) in ((1.38, -1.3), (1.12, -1.45)):
            h = b.hand((0.1, y, 0.05), a, "pale", length=0.42, spread=0.12, curl=0.62, thumb=False)
            self.hands.append((h, list(b.tips)))
        sc.amb, sc.dif = 0.08, 0.32
        D.skin(sc, self.face_obj, "pale", 7, offset=-0.08, mottle=0.1)
        for h, tips in self.hands:
            D.skin(sc, h, "pale", 9, offset=-0.12, mottle=0.1)
            nails(sc, b, tips, h, "#1d1612")
        fx, fy = self.fc
        for s in (-1, 1):
            D.hole(sc, self.face_obj, fx + s * 5.2, fy - 2.5, 2.8, 4.2, "pale", glints=((0.0, 0.35),), angle=0.18)
        D.nose(sc, self.face_obj, fx + 0.5, fy - 2, fy + 7, 2.6, "pale", nostrils=False, light=-0.1)
        sc.paint_mask(sc.seg((fx - 2.5, fy + 13), (fx + 3, fy + 12.5)), "#1d1212", only_obj=self.face_obj)

    def face(self, attack=False):
        sc = face_scene("#0d0c0f")
        rng = np.random.default_rng(21)
        st = 1.0 if not attack else 1.0
        cy = 140 if not attack else 150
        ry = 128 if not attack else 150
        face = sc.ellipsoid((150, cy, 0), 102, ry, 34, "pale")
        # the face hangs on a rod; at the edges, the rim of a shell
        cheeks = -0.22 * (np.exp(-(((sc.X - 150) / 60) ** 4)) * 0 + np.exp(-((np.abs(sc.X - 150) - 62) / 18) ** 2)
                          * np.exp(-((sc.Y - cy - 40) / 34) ** 2))
        D.skin(sc, face, "pale", 7, offset=0.1, mottle=0.12, blotches=0.12, pores=0.02, veins=10, extra=cheeks)
        eye_y = cy - 34 if not attack else cy - 10
        for s in (-1, 1):
            D.hole(sc, face, 150 + s * 44, eye_y, 21 if not attack else 19, 30 if not attack else 40, "pale",
                   glints=((s * -0.1, 0.42), (s * 0.15, 0.5)), depth=1.0)
            D.wrinkles(sc, face, 150 + s * 44, eye_y + 34, 34, 7, rng, "pale", angle=0.0, spread=0.2, length=0.7)
        D.nose(sc, face, 150, eye_y - 4, eye_y + 72 if not attack else eye_y + 64, 21, "pale", hook=3, light=0.04)
        if not attack:
            D.lips(sc, face, 150, 236, 62, "pale", curve=9, v=-0.12, seed=4, thin=0.9)
            for s in (-1, 1):
                D.wrinkles(sc, face, 150 + s * 36, 226, 30, 5, rng, "pale", angle=-np.pi / 2 - s * 0.5, spread=0.2,
                           length=0.8)
        else:
            m = D.maw(sc, face, 150, 212, 66, 80, seed=8, tooth_w=8, missing=0.2, saliva=6, curve=-6, teeth_bottom=False,
                      tongue=False, depth_ramp="black")
            for s in (-1, 1):
                for i in range(12):
                    x = 150 + s * (33 + i * 0.8)
                    D.put(sc, x, 214 + i * 2.2, D.ramp_at("red", 0.45, x, i), face)
                    D.put(sc, x + s, 215 + i * 2.2, D.ramp_at("red", 0.25, x, i), face)
            D.drips(sc, face, 120, 180, 286, 5, rng, "red", (3, 14), 0.35)
        return sc.render(rim=0.0)

    def detail(self):
        sc = Scene(240, F)
        bg, door = dark_room(240, F)
        sc.bg[:] = bg * 1.6
        frame = sc.new_id()
        sc.polygon([(150, 0), (176, 0), (176, F), (150, F)], 2, "wood", frame, normal=(-0.3, 0, 1))
        D.skin(sc, frame, "wood", 2, offset=-0.05, mottle=0.25, light=True)
        b = Body(sc, 200, ground=F, s=330)
        h1 = b.hand((0.06, 0.62, 0.05), -1.35, "pale", length=0.42, spread=0.1, curl=0.62, thumb=False, r=0.014, palm=0.05)
        t1 = list(b.tips)
        h2 = b.hand((0.08, 0.33, 0.05), -1.5, "pale", length=0.42, spread=0.12, curl=0.66, thumb=False, r=0.014, palm=0.05)
        t2 = list(b.tips)
        sc.amb, sc.dif = 0.12, 0.6
        for h, tips in ((h1, t1), (h2, t2)):
            D.skin(sc, h, "pale", 9, offset=-0.04, mottle=0.12, veins=6, pores=0.03)
            for p, a in tips:
                x, y = b.xy(*p)
                for dx in range(-3, 4):
                    for dy in range(-2, 3):
                        if dx * dx / 9 + dy * dy / 4 <= 1:
                            D.put(sc, x + dx - np.sin(a) * 2, y + dy + np.cos(a) * 2, D.ramp_at("rust", 0.25 + 0.1 * (dy < 0)), h)
        # scratches it left in the frame
        rng = np.random.default_rng(2)
        for i in range(6):
            x = rng.uniform(152, 174)
            y = rng.uniform(40, 260)
            for j in range(int(rng.integers(10, 30))):
                D.put(sc, x + j * 0.15, y + j, "#c9a77a", frame)
        return sc.render(rim=0.2)


# ===== C: the collage ================================================================================

class Collage(Concept):
    key = "c_collage"
    title = "C.  The Collage  (a face cut out of photographs and taped onto paper)"
    lines = ["Black stick body. The head is a square of old paper taped to the neck; on it, eyes, a nose and a mouth cut",
             "from photographs of different people, glued on not quite where they go. In the corner of your eye the pieces",
             "slide around the paper; look straight at it and they are back in place, a little more wrong each time."]
    detail_label = "the paper tears from behind"

    def figure(self, b, view):
        top, self.body, _ = stick_body(b, view, arm_len=1.08, hunch=0.4 if view == "side" else 0)
        sc = b.sc
        self.obj = sc.new_id()
        self.tape = []
        if view == "side":
            c = (top[0] + 0.02, top[1] + 0.2)
            sc.capsule(b.p(c[0] + 0.03, c[1] + 0.2), b.p(c[0] - 0.03, c[1] - 0.2), 0.016 * b.s, 0.016 * b.s, "paper",
                       self.obj)
            self.c = b.xy(*c)
            return
        c = np.array([top[0], top[1] + 0.2])
        a = 0.12
        self.R = np.array([[np.cos(a), -np.sin(a)], [np.sin(a), np.cos(a)]])
        pts = [c + self.R @ np.array(v) for v in ((-0.2, 0.2), (0.2, 0.2), (0.2, -0.2), (-0.2, -0.2))]
        b.poly([tuple(p) for p in pts], 0.25, "paper", self.obj, normal=(-0.1, -0.12, 1))
        for v in ((-0.2, 0.2), (0.2, 0.2), (0.2, -0.2), (-0.2, -0.2)):
            q = c + self.R @ np.array(v)
            t = sc.new_id()
            b.ball((q[0], q[1], 0.27), 0.06, "paper", t, ry=0.022, rz=0.01, angle=0.7 * np.sign(v[0] * v[1]))
            self.tape.append(t)
        self.c = b.xy(*c)

    def texture(self, sc, b, view, dark=False):
        if view == "side":
            return
        k = b.k
        D.skin(sc, self.obj, "paper", 5, offset=-0.45 if dark else 0.04, mottle=0.18, blotches=0.25, edge=0.1)
        for t in self.tape:
            D.skin(sc, t, "stain", 3, offset=0.1 - (0.5 if dark else 0), gain=1.1, mottle=0.1)
        cx, cy = self.c
        for (dx, dy, r, s) in ((-0.085, -0.04, 0.05, 1), (0.075, -0.055, 0.056, 2)):
            o = self.R @ np.array([dx, dy]) * b.s
            ex, ey = cx + o[0], cy - o[1]
            cut = sc.new_id()
            sc.ellipsoid((ex, ey, 0.3 * b.s), r * b.s * 1.3, r * b.s * 0.85, 0.5, "flesh", cut, angle=-0.12 + 0.1 * s)
            D.skin(sc, cut, "flesh", 3 + s, offset=-0.4 if dark else 0.05, mottle=0.1, light=False, gain=0.62)
            if k > 0.6:
                D.eye(sc, cut, ex, ey, r * b.s * 1.6, r * b.s * 0.85, "flesh", look=(0.1, 0.0), socket=0.0, red=0.3,
                      seed=s, caps=3, iris=0.55, wet=not dark, lashes=k > 0.9)
                if dark:
                    D.put(sc, ex, ey, "#f2ede0")
            else:
                sc.paint_mask(sc.ell(ex, ey, r * b.s * 0.6, r * b.s * 0.45), "#100b0a", only_obj=cut)
        o = self.R @ np.array([0.01, -0.13]) * b.s
        mc = sc.new_id()
        sc.ellipsoid((cx + o[0], cy - o[1], 0.3 * b.s), 0.07 * b.s, 0.03 * b.s, 0.5, "flesh", mc, angle=-0.05)
        D.skin(sc, mc, "flesh", 7, offset=-0.4 if dark else 0.0, mottle=0.1, light=False, gain=0.6)
        sc.paint_mask(sc.seg((cx + o[0] - 0.05 * b.s, cy - o[1]), (cx + o[0] + 0.05 * b.s, cy - o[1] + 0.005 * b.s)),
                      "#200c0c", only_obj=mc)
        o = self.R @ np.array([0.0, -0.06]) * b.s
        nc = sc.new_id()
        sc.ellipsoid((cx + o[0] + 1, cy - o[1], 0.3 * b.s), 0.026 * b.s, 0.045 * b.s, 0.5, "flesh", nc)
        D.skin(sc, nc, "flesh", 8, offset=-0.4 if dark else 0.0, mottle=0.1, gain=0.9)

    def corner(self, sc, door):
        b = Body(sc, 52)
        self.figure(b, "front")
        self.texture(sc, b, "front", dark=True)

    def face(self, attack=False):
        sc = face_scene("#19171a")
        rng = np.random.default_rng(5)
        paper = sc.new_id()
        sc.polygon([(24, 22), (282, 12), (290, 276), (30, 288)], 10, "paper", paper, normal=(-0.1, -0.1, 1))
        D.skin(sc, paper, "paper", 21, offset=0.04, mottle=0.2, blotches=0.3, edge=0.15)
        D.drips(sc, paper, 40, 270, 30, 8, rng, "rust", (6, 30), 0.3)
        if attack:
            D.tear(sc, paper, [(70, 160), (240, 150), (250, 290), (60, 292)], rng, depth_ramp="gum")
            D.maw(sc, paper, 155, 185, 160, 92, seed=12, tooth_w=13, missing=0.1, saliva=8, curve=4)
        cuts = []
        eyes = ((92, 104, 0.1, 0.62), (210, 96, -0.06, 0.66)) if not attack else ((58, 60, 0.6, 0.62), (246, 128, -0.4, 0.66))
        for i, (ex, ey, a, v) in enumerate(eyes):
            cut = sc.new_id()
            pts = []
            for t in np.linspace(0, 2 * np.pi, 22, endpoint=False):
                rr = 1 + 0.08 * rng.normal()
                pts.append((ex + 52 * rr * np.cos(t) * np.cos(a) - 30 * rr * np.sin(t) * np.sin(a),
                            ey + 52 * rr * np.cos(t) * np.sin(a) + 30 * rr * np.sin(t) * np.cos(a)))
            shadow = sc.new_id()
            sc.polygon([(x + 3, y + 4) for x, y in pts], 12, "paper", shadow, flat=0.25)
            sc.polygon(pts, 14, "flesh", cut)
            D.skin(sc, cut, "flesh", 30 + i, offset=v - 0.62, mottle=0.12, light=False, gain=0.62, pores=0.02)
            D.eye(sc, cut, ex, ey, 66, 30, "flesh", look=(0.25 - 0.5 * i, 0.15), socket=0.8, red=0.5, seed=7 + i,
                  tilt=a, iris=0.44, caps=14)
            cuts.append(cut)
            edge = (sc.obj == cut) & ~(np.roll(sc.obj == cut, 2, 0) & np.roll(sc.obj == cut, -2, 0)
                                        & np.roll(sc.obj == cut, 2, 1) & np.roll(sc.obj == cut, -2, 1))
            sc.paint_ramp(edge, "bone", 0.85)
        nx, ny = (150, 150) if not attack else (104, 214)
        nose = sc.new_id()
        pts = [(nx - 16, ny - 50), (nx + 16, ny - 50), (nx + 36, ny + 30), (nx - 34, ny + 34)]
        sc.polygon([(x + 3, y + 4) for x, y in pts], 12, "paper", sc.new_id(), flat=0.25)
        sc.polygon(pts, 14, "flesh", nose)
        D.skin(sc, nose, "flesh", 40, offset=0.02, mottle=0.1, light=False, gain=0.6)
        D.nose(sc, nose, nx, ny - 46, ny + 16, 24, "flesh", light=0.0, hook=2)
        edge = (sc.obj == nose) & ~(np.roll(sc.obj == nose, 2, 0) & np.roll(sc.obj == nose, -2, 0)
                                    & np.roll(sc.obj == nose, 2, 1) & np.roll(sc.obj == nose, -2, 1))
        sc.paint_ramp(edge, "bone", 0.85)
        if not attack:
            mouth = sc.new_id()
            mx, my = 164, 226
            pts = [(mx - 64, my - 22), (mx + 60, my - 28), (mx + 66, my + 20), (mx - 60, my + 24)]
            sc.polygon([(x + 3, y + 4) for x, y in pts], 12, "paper", sc.new_id(), flat=0.25)
            sc.polygon(pts, 14, "flesh", mouth)
            D.skin(sc, mouth, "flesh", 41, offset=0.05, mottle=0.1, light=False, gain=0.58)
            D.lips(sc, mouth, mx, my, 92, "flesh", curve=5, v=-0.25, seed=2, thin=2.0)
            edge = (sc.obj == mouth) & ~(np.roll(sc.obj == mouth, 2, 0) & np.roll(sc.obj == mouth, -2, 0)
                                         & np.roll(sc.obj == mouth, 2, 1) & np.roll(sc.obj == mouth, -2, 1))
            sc.paint_ramp(edge, "bone", 0.85)
        for (x, y, a) in ((24, 22, 0.6), (282, 12, -0.6), (290, 276, 0.6), (30, 288, -0.6)):
            t = sc.new_id()
            sc.ellipsoid((x, y, 30), 44, 13, 1, "stain", t, angle=a)
            D.skin(sc, t, "stain", 3, offset=0.18, mottle=0.12, light=False, gain=0.62)
        return sc.render(rim=0.0)

    def detail(self):
        sc = Scene(240, F)
        sc.bg[:] = rgb("#19171a")
        rng = np.random.default_rng(9)
        paper = sc.new_id()
        sc.polygon([(0, 0), (240, 0), (240, F), (0, F)], 10, "paper", paper)
        D.skin(sc, paper, "paper", 33, offset=0.0, mottle=0.2, blotches=0.3)
        D.tear(sc, paper, [(70, 120), (170, 110), (180, 200), (80, 214)], rng, depth_ramp="gum")
        D.eye(sc, paper, 124, 160, 64, 34, "paper", look=(0.0, 0.0), socket=0.0, red=0.7, seed=3, caps=20, iris=0.45,
              lower_lid=False)
        for i in range(5):
            x = rng.uniform(60, 190)
            D.walk(sc, x, 108, np.pi / 2 + rng.normal(0, 0.3), 40, rng, colour=D.ramp_at("paper", 0.2), only=paper,
                   wobble=0.2, branch=0.1)
        cut = sc.new_id()
        sc.ellipsoid((60, 250, 12), 50, 22, 0.5, "flesh", cut, angle=-0.3)
        D.skin(sc, cut, "flesh", 12, offset=0.0, mottle=0.1, light=False, gain=0.6)
        D.lips(sc, cut, 60, 252, 70, "flesh", curve=6, v=-0.2, seed=8, thin=1.6)
        return sc.render(rim=0.0)


# ===== D: the radiant ================================================================================

class Radiant(Concept):
    key = "d_radiant"
    title = "D.  The Radiant  (a face ringed with fingers, on a black spire)"
    lines = ["A narrow black robe-spire with no legs to see, and two long black arms. The head is a round human face with a",
             "ring of long pale fingers growing out all round it like a sunburst, knuckles and nails. In the corner of your eye",
             "the ring curls and uncurls; when it reaches you the fingers fold in over your view."]
    detail_label = "the ring, close"

    def figure(self, b, view):
        sc = b.sc
        side = view == "side"
        self.robe = sc.new_id()
        hem = 0.26 if not side else 0.2
        rng = np.random.default_rng(5)
        pts = [(-hem, 0.0)]
        for i in range(1, 14):
            pts.append((-hem + i * hem * 2 / 14 + 0.01 * rng.normal(), 0.04 * rng.random()))
        pts += [(hem, 0.0), (0.07, 1.9), (0.055, 2.3), (0.03, 2.5), (-0.03, 2.5), (-0.055, 2.3), (-0.07, 1.9)]
        b.poly(pts, 0.0, "cloth", self.robe, normal=(0.15, -0.05, 1))
        arms = sc.new_id()
        self.hands = []
        for s in ((-1, 1) if not side else (1,)):
            b.chain([(s * 0.07, 2.14, 0.05), (s * 0.24, 1.62, 0.06), (s * 0.3, 1.02, 0.08)], [0.03, 0.024, 0.02], obj=arms,
                    knots=0.7)
            self.hands.append((b.hand((s * 0.3, 1.02, 0.08), s * 0.1, "pale", length=0.42, spread=0.22, curl=0.15,
                                      r=0.013), list(b.tips)))
        c = (0.0 if not side else 0.03, 2.74)
        if not side:
            self.face_obj = b.ball((c[0], c[1], 0.06), 0.14, "pale", ry=0.15, rz=0.06)
        else:
            self.face_obj = b.ball((c[0] + 0.04, c[1], 0.06), 0.05, "pale", ry=0.15, rz=0.1)
        self.fc = b.xy(*c)
        self.fingers = sc.new_id()
        self.tips = []
        n = 14
        for i in range(n):
            a = i * 2 * np.pi / n + 0.12
            ln = 0.3 + 0.12 * ((i * 7) % 5) / 4
            d = np.array([np.cos(a), np.sin(a)])
            if side:
                d = np.array([0.15 * np.cos(a) * 0.5, np.sin(a)])
            p = np.array(c) + d * 0.13
            q = p + d * ln * 0.5
            bend = 0.5 * np.sin(i * 1.7)
            d2 = np.array([d[0] * np.cos(bend) - d[1] * np.sin(bend), d[0] * np.sin(bend) + d[1] * np.cos(bend)])
            r = q + d2 * ln * 0.3
            t = r + d2 * ln * 0.22
            b.limb((p[0], p[1], 0.0), (q[0], q[1], 0.0), 0.018, 0.016, "pale", self.fingers)
            b.ball((q[0], q[1], 0.0), 0.021, "pale", self.fingers)
            b.limb((q[0], q[1], 0.0), (r[0], r[1], 0.0), 0.016, 0.013, "pale", self.fingers)
            b.ball((r[0], r[1], 0.0), 0.017, "pale", self.fingers)
            b.limb((r[0], r[1], 0.0), (t[0], t[1], 0.0), 0.013, 0.01, "pale", self.fingers)
            self.tips.append((tuple(t), np.arctan2(d2[0], -d2[1])))

    def texture(self, sc, b, view, dark=False):
        rob = sc.obj == self.robe
        yy, xx = np.mgrid[0:sc.h, 0:sc.w]
        folds = 0.25 + 0.13 * np.sin((xx - b.cx) * 0.55 / max(b.k, 0.3)) - 0.1 * (yy / sc.h)
        sc.paint_ramp(rob, "cloth", folds - (0.1 if dark else 0.0))
        off = 0.12 if dark else 0.05
        D.skin(sc, self.face_obj, "pale", 4, offset=off, mottle=0.1)
        D.skin(sc, self.fingers, "pale", 6, offset=off - 0.03, mottle=0.12)
        nails(sc, b, self.tips, self.fingers)
        for h, tips in self.hands:
            D.skin(sc, h, "pale", 9, offset=off - 0.03, mottle=0.1)
            nails(sc, b, tips, h)
        if view == "side":
            return
        fx, fy = self.fc
        k = b.k
        for s in (-1, 1):
            if k > 0.6:
                D.eye(sc, self.face_obj, fx + s * 0.075 * b.s, fy - 0.01 * b.s, 0.03 * b.s, 0.015 * b.s, "pale",
                      look=(-0.5 * s, 0), socket=1.4, red=0.3, seed=s + 4, caps=1, iris=0.6, lashes=False, wet=not dark)
            else:
                sc.paint_mask(sc.ell(fx + s * 0.075 * b.s, fy - 0.01 * b.s, 0.8, 0.7), "#140c0a", only_obj=self.face_obj)
            if dark:
                D.put(sc, fx + s * 0.075 * b.s, fy - 0.01 * b.s, "#f3eee0")
        if k > 0.6:
            D.lips(sc, self.face_obj, fx, fy + 0.07 * b.s, 0.11 * b.s, "pale", curve=0.02 * b.s, part=1.5, v=-0.25,
                   chapped=False, thin=0.6)
        else:
            sc.paint_mask(sc.seg((fx - 0.05 * b.s, fy + 0.07 * b.s), (fx + 0.05 * b.s, fy + 0.07 * b.s)), "#2a1414",
                          only_obj=self.face_obj)

    def corner(self, sc, door):
        b = Body(sc, 56, ground=GROUND + 8)
        self.figure(b, "front")
        self.texture(sc, b, "front", dark=True)

    def _ring(self, sc, c, attack, rad=64, ln=96):
        fingers = sc.new_id()
        tips = []
        n = 14
        for i in range(n):
            a = i * 2 * np.pi / n + 0.12
            d = np.array([np.cos(a), np.sin(a)])
            p = np.array(c) + d * rad
            if attack:
                q = p + d * ln * 0.35
                r = q - d * ln * 0.12 + np.array([-d[1], d[0]]) * 6
                t = r - d * ln * 0.22
                zs = (0, 40, 80, 110)
            else:
                q = p + d * ln * 0.45
                bend = 0.55 * np.sin(i * 1.7)
                d2 = np.array([d[0] * np.cos(bend) - d[1] * np.sin(bend), d[0] * np.sin(bend) + d[1] * np.cos(bend)])
                r = q + d2 * ln * 0.32
                t = r + d2 * ln * 0.22
                zs = (0, 0, 0, 0)
            rr = 9.5
            for j, (a0, a1) in enumerate(((p, q), (q, r), (r, t))):
                sc.capsule((a0[0], a0[1], zs[j]), (a1[0], a1[1], zs[j + 1]), rr, rr * 0.88, "pale", fingers)
                if j < 2:
                    sc.ellipsoid((a1[0], a1[1], zs[j + 1]), rr * 1.18, rr * 1.18, rr, "pale", fingers)
                rr *= 0.86
            tips.append((t, (t - r) / (np.linalg.norm(t - r) + 1e-9), zs[3]))
        return fingers, tips

    def face(self, attack=False):
        sc = face_scene("#141215")
        rng = np.random.default_rng(3)
        c = (150, 152)
        fingers, tips = self._ring(sc, c, attack, rad=70 if not attack else 66, ln=110 if not attack else 120)
        face = sc.ellipsoid((c[0], c[1], 30), 74, 80, 40, "pale")
        D.skin(sc, face, "pale", 4, offset=0.1, mottle=0.12, pores=0.03, blotches=0.12, veins=6)
        D.skin(sc, fingers, "pale", 8, offset=0.03, mottle=0.12, pores=0.02, veins=8)
        for (t, u, z) in tips:
            nail = sc.new_id()
            e = np.array(t) - u * 4
            sc.ellipsoid((e[0], e[1], z + 12), 5.5, 5.5, 2, "teeth", nail)
            D.skin(sc, nail, "teeth", 2, offset=-0.25, mottle=0.2)
        # knuckle creases
        for i in range(60):
            ys, xs = np.nonzero(sc.obj == fingers)
            k = rng.integers(len(xs))
            D.wrinkles(sc, fingers, xs[k], ys[k], 8, 1, rng, "pale", angle=rng.uniform(0, np.pi), length=0.8, value=-0.15)
        for s in (-1, 1):
            D.eye(sc, face, c[0] + s * 38, c[1] - 14, 20, 10, "pale", look=(-0.6 * s, 0.1), socket=1.5, red=0.5,
                  seed=5 + s, caps=6, iris=0.62)
        D.nose(sc, face, c[0], c[1] - 18, c[1] + 18, 15, "pale", light=0.02)
        if not attack:
            D.maw(sc, face, c[0], c[1] + 36, 84, 13, seed=6, tooth_w=5.2, missing=0.0, saliva=0, curve=-7,
                  tongue=False, teeth_bottom=True, gum_h=2)
            D.lips(sc, face, c[0], c[1] + 33, 92, "pale", curve=-8, v=-0.2, chapped=True, thin=0.8)
        else:
            D.maw(sc, face, c[0], c[1] + 30, 80, 40, seed=6, tooth_w=6, missing=0.1, saliva=5, curve=-5)
        return sc.render(rim=0.0)

    def detail(self):
        sc = Scene(240, F)
        sc.bg[:] = rgb("#151316")
        rng = np.random.default_rng(13)
        fingers = sc.new_id()
        pts = [(-10, 250), (60, 205), (118, 186), (170, 140), (196, 70)]
        rr = 22
        for j in range(len(pts) - 1):
            sc.capsule((*pts[j], 0), (*pts[j + 1], 0), rr, rr * 0.86, "pale", fingers)
            if 0 < j + 1 < len(pts) - 1:
                sc.ellipsoid((*pts[j + 1], 0), rr * 1.15, rr * 1.15, rr, "pale", fingers)
            rr *= 0.86
        D.skin(sc, fingers, "pale", 3, offset=0.05, mottle=0.14, pores=0.04, veins=6)
        for (x, y, a) in ((60, 205, 1.2), (118, 186, 1.4), (170, 140, 0.6)):
            D.wrinkles(sc, fingers, x, y, 30, 7, rng, "pale", angle=a, spread=0.12, length=0.9, value=-0.2)
        nail = sc.new_id()
        sc.ellipsoid((193, 82, 20), 12, 16, 6, "teeth", nail, angle=0.4)
        D.skin(sc, nail, "teeth", 3, offset=-0.2, mottle=0.25)
        sc.paint_mask(sc.ell(190, 72, 10, 4, 0.4), "#2a1e14", only_obj=nail)
        return sc.render(rim=0.15)


# ===== E: the bust ===================================================================================

class Bust(Concept):
    key = "e_bust"
    title = "E.  The Bust  (a smooth bald head with its eyes in the wrong places)"
    lines = ["Black stick legs and arms; the chest and shoulders are a pale plaster-like bust, broken off at the ribs. A long",
             "stretched neck holds a smooth egg of a head: two small wet eyes, one high on the temple and one low on the",
             "cheek, a nose drawn on in ink, a red smear. When it reaches you, the head splits down the middle."]
    detail_label = "the eye on its temple"

    def figure(self, b, view):
        sc = b.sc
        body = sc.new_id()
        side = view == "side"
        if not side:
            for s in (-1, 1):
                b.chain([(s * 0.09, 1.4, 0), (s * 0.12, 0.75, 0.02), (s * 0.11, 0.07, 0)], [0.04, 0.034, 0.028], obj=body,
                        knots=0.5)
                for t in range(4):
                    b.limb((s * 0.11, 0.07, 0), (s * (0.12 + 0.02 * t), 0.0, 0.08 + 0.02 * t), 0.012, 0.008, obj=body)
            b.ball((0, 1.44, 0), 0.11, obj=body, ry=0.07)
            b.chain([(0, 1.44, 0), (0, 1.62, 0), (0, 1.76, 0)], [0.03, 0.026, 0.03], obj=body, knots=0.7)
            self.bust = sc.new_id()
            b.ball((0, 1.98, 0.02), 0.24, "bone", self.bust, ry=0.22, rz=0.12)
            for s in (-1, 1):
                b.ball((s * 0.22, 2.06, 0.0), 0.09, "bone", self.bust)
                b.chain([(s * 0.26, 2.0, 0), (s * 0.32, 1.45, 0.02), (s * 0.32, 0.95, 0.05)], [0.035, 0.03, 0.026],
                        obj=body, knots=0.6)
                b.hand((s * 0.32, 0.95, 0.05), s * 0.06, "black", length=0.42, spread=0.12, curl=0.1, obj=body, r=0.012,
                       knuckle=1.5)
            self.neck = sc.new_id()
            b.limb((0, 2.12, 0.02), (0.02, 2.62, 0.02), 0.06, 0.045, "bone", self.neck)
            self.head = b.ball((0.03, 2.8, 0.03), 0.15, "bone", ry=0.2, rz=0.15)
            self.hc = b.xy(0.03, 2.8)
            return
        b.chain([(0.0, 1.4, 0), (0.07, 0.75, 0.0), (-0.02, 0.07, 0)], [0.04, 0.034, 0.028], obj=body, knots=0.5)
        b.limb((-0.02, 0.07, 0), (0.15, 0.0, 0), 0.03, 0.012, obj=body)
        b.chain([(0.03, 1.4, -.02), (-0.02, 0.75, -0.02), (-0.08, 0.07, -0.02)], [0.04, 0.034, 0.028], obj=body, knots=0.5)
        b.limb((-0.08, 0.07, -0.02), (0.09, 0.0, -0.02), 0.03, 0.012, obj=body)
        b.chain([(0, 1.44, 0), (0, 1.62, 0), (0.02, 1.76, 0)], [0.03, 0.026, 0.03], obj=body, knots=0.7)
        self.bust = sc.new_id()
        b.ball((0.04, 1.98, 0.02), 0.14, "bone", self.bust, ry=0.22, rz=0.2, angle=-0.2)
        b.chain([(0.06, 2.06, 0), (0.2, 1.5, 0.02), (0.28, 0.96, 0.05)], [0.035, 0.03, 0.026], obj=body, knots=0.6)
        b.hand((0.28, 0.96, 0.05), 0.25, "black", length=0.42, spread=0.06, curl=0.12, obj=body, r=0.012, knuckle=1.5,
               thumb=False)
        self.neck = sc.new_id()
        b.limb((0.08, 2.12, 0.02), (0.22, 2.6, 0.02), 0.06, 0.045, "bone", self.neck)
        self.head = b.ball((0.27, 2.78, 0.03), 0.18, "bone", ry=0.2, rz=0.15, angle=-0.3)
        self.hc = b.xy(0.27, 2.78)

    def texture(self, sc, b, view, dark=False):
        off = 0.12 if dark else 0.04
        for o in (self.bust, self.neck, self.head):
            D.skin(sc, o, "bone", 12 + o, offset=off, mottle=0.1, blotches=0.1)
        k = b.k
        hx, hy = self.hc
        if view == "side":
            D.eye(sc, self.head, hx - 0.07 * b.s, hy - 0.06 * b.s, 0.045 * b.s, 0.03 * b.s, "bone", look=(-0.5, 0),
                  socket=0.8, red=0.6, seed=2, caps=2, iris=0.6, lashes=False)
            return
        for i, (dx, dy) in enumerate(((-0.1, -0.11), (0.08, 0.06))):
            if k > 0.6:
                D.eye(sc, self.head, hx + dx * b.s, hy + dy * b.s, 0.045 * b.s, 0.03 * b.s, "bone",
                      look=((0.4, 0.3), (-0.5, -0.3))[i], socket=0.8, red=0.6, seed=2 + i, caps=2, iris=0.6, lashes=False,
                      wet=not dark)
            else:
                sc.paint_mask(sc.ell(hx + dx * b.s, hy + dy * b.s, 0.8, 0.8), "#0c0808", only_obj=self.head)
            if dark:
                D.put(sc, hx + dx * b.s, hy + dy * b.s, "#f3eee0")
        sc.paint_mask(sc.seg((hx, hy - 0.02 * b.s), (hx + 0.012 * b.s, hy + 0.07 * b.s)) |
                      sc.seg((hx + 0.012 * b.s, hy + 0.07 * b.s), (hx - 0.012 * b.s, hy + 0.075 * b.s)), "#2a2420",
                      only_obj=self.head)
        sc.paint_ramp(sc.ell(hx - 0.04 * b.s, hy - 0.1 * b.s, 0.1 * b.s, 0.022 * b.s, 0.3), "red", 0.45 - (0.25 if dark else 0),
                      only_obj=self.head)
        sc.paint_mask(sc.seg((hx - 0.02 * b.s, hy + 0.14 * b.s), (hx + 0.02 * b.s, hy + 0.14 * b.s)), "#3a2020",
                      only_obj=self.head)

    def corner(self, sc, door):
        b = Body(sc, 52)
        self.figure(b, "front")
        self.texture(sc, b, "front", dark=True)

    def face(self, attack=False):
        sc = face_scene(wallpaper(F, F, 21, dark=0.05))
        rng = np.random.default_rng(4)
        head = sc.ellipsoid((156, 140, 0), 112, 138, 90, "bone")
        neck = sc.capsule((156, 260, -60), (156, 340, -60), 58, 66, "bone")
        D.skin(sc, head, "bone", 3, offset=0.04, mottle=0.12, blotches=0.15, pores=0.015, veins=5)
        D.skin(sc, neck, "bone", 4, offset=-0.04, mottle=0.1)
        D.eye(sc, head, 84, 66, 38, 26, "bone", look=(0.5, 0.45), socket=0.9, red=0.8, seed=3, caps=16, iris=0.6,
              tilt=-0.5, lashes=False)
        D.eye(sc, head, 204, 196, 34, 24, "bone", look=(-0.6, -0.5), socket=0.9, red=0.8, seed=4, caps=16, iris=0.6,
              tilt=0.35, lashes=False)
        ink = sc.seg((156, 104), (166, 170), 3) | sc.seg((166, 170), (146, 176), 3)
        sc.paint_mask(ink, "#241e1a", only_obj=head)
        D.drips(sc, head, 158, 166, 176, 2, rng, "black", (4, 10), 0.4)
        sc.paint_ramp(sc.ell(112, 58, 64, 12, 0.32), "red", 0.42, only_obj=head)
        sc.paint_ramp(sc.ell(126, 52, 38, 6, 0.32), "red", 0.25, only_obj=head)
        D.drips(sc, head, 90, 150, 64, 6, rng, "red", (6, 26), 0.35)
        if not attack:
            sc.paint_mask(sc.seg((140, 222), (170, 222), 2), "#3a2020", only_obj=head)
        else:
            gap = sc.new_id()
            pts = [(150, 40), (162, 40), (182, 150), (176, 270), (138, 270), (130, 150)]
            D.tear(sc, head, pts, rng, fibre="bone", depth_ramp="gum")
            for y in np.arange(56, 262, 9):
                t = (y - 40) / 230
                xl = 150 - 22 * np.sin(np.pi * t) - 2
                xr = 162 + 22 * np.sin(np.pi * t) + 2
                D.tooth(sc, head, xl + 4, y, 10, 9, rng, down=True)
                D.tooth(sc, head, xr - 4, y + 4, 10, 9, rng, down=True)
        return sc.render(rim=0.0)

    def detail(self):
        sc = Scene(240, F)
        sc.bg[:] = rgb("#9a958a")
        rng = np.random.default_rng(8)
        head = sc.ellipsoid((150, 200, 0), 210, 260, 200, "bone")
        D.skin(sc, head, "bone", 5, offset=0.02, mottle=0.12, pores=0.03, veins=8, blotches=0.15)
        D.eye(sc, head, 110, 120, 110, 64, "bone", look=(0.6, 0.5), socket=0.8, red=0.9, seed=9, caps=40, iris=0.5,
              tilt=-0.4, lashes=False)
        D.wrinkles(sc, head, 110, 170, 90, 9, rng, "bone", angle=-0.3, spread=0.12, length=0.6)
        return sc.render(rim=0.0)


# ===== F: the sheet ==================================================================================

class Sheet(Concept):
    key = "f_sheet"
    title = "F.  The Sleeper  (something under a bedsheet, its face pushed through the cloth)"
    lines = ["A tall shape under an old red bedsheet, stained dark, with two long black arms out through holes in it. Its",
             "face presses through the cloth from inside: brow, nose, an open mouth, the sheet wet where it breathes. It has",
             "come out of a bed. When it reaches you its teeth come through the sheet."]
    detail_label = "where it breathes"

    def figure(self, b, view):
        sc = b.sc
        side = view == "side"
        self.cloth = sc.new_id()
        rng = np.random.default_rng(3)
        if not side:
            pts = [(-0.34, 0.0)]
            for i in range(1, 16):
                pts.append((-0.34 + i * 0.68 / 16 + 0.012 * rng.normal(), 0.05 * rng.random()))
            pts += [(0.34, 0.0), (0.27, 0.9), (0.23, 1.7), (0.24, 2.05), (0.18, 2.28), (0.15, 2.55), (0.11, 2.86),
                    (0.0, 2.96), (-0.11, 2.86), (-0.15, 2.55), (-0.18, 2.28), (-0.24, 2.05), (-0.23, 1.7), (-0.27, 0.9)]
            b.poly(pts, 0.0, "sheet", self.cloth, normal=(-0.05, -0.05, 1))
            self.fc = b.xy(0.0, 2.66)
            arms = sc.new_id()
            self.hands = []
            for s in (-1, 1):
                b.chain([(s * 0.22, 2.0, 0.05), (s * 0.34, 1.45, 0.06), (s * 0.36, 0.92, 0.08)], [0.03, 0.024, 0.02],
                        obj=arms, knots=0.7)
                self.hands.append((b.hand((s * 0.36, 0.92, 0.08), s * 0.08, "pale", length=0.45, spread=0.2, curl=0.15,
                                          r=0.013), list(b.tips)))
            for s in (-1, 1):
                b.limb((s * 0.12, 0.1, 0.1), (s * 0.18, 0.0, 0.2), 0.03, 0.014, obj=arms)
            self.side = False
            return
        pts = [(-0.24, 0.0), (0.22, 0.0), (0.2, 0.9), (0.16, 1.7), (0.2, 2.1), (0.26, 2.5), (0.3, 2.72), (0.22, 2.92),
               (0.02, 2.95), (-0.12, 2.7), (-0.16, 2.2), (-0.2, 1.4)]
        b.poly(pts, 0.0, "sheet", self.cloth, normal=(-0.05, -0.05, 1))
        self.fc = b.xy(0.22, 2.62)
        arms = sc.new_id()
        self.hands = []
        b.chain([(0.15, 2.0, 0.05), (0.3, 1.45, 0.06), (0.36, 0.95, 0.08)], [0.03, 0.024, 0.02], obj=arms, knots=0.7)
        self.hands.append((b.hand((0.36, 0.95, 0.08), 0.25, "pale", length=0.45, spread=0.08, curl=0.18, r=0.013,
                                  thumb=False), list(b.tips)))
        self.side = True

    def texture(self, sc, b, view, dark=False):
        m = sc.obj == self.cloth
        yy, xx = np.mgrid[0:sc.h, 0:sc.w]
        fx, fy = self.fc
        dx, dy = (xx - fx) / b.s, (yy - fy) / b.s
        r = np.sqrt(dx * dx + dy * dy) + 1e-6
        ang = np.arctan2(dy, dx)
        hang = np.sin(dx * 26 + 1.5 * np.sin(dy * 3)) * np.clip((dy - 0.15) / 0.8, 0, 1)
        tension = np.sin(ang * 9) * np.exp(-r / 0.25) * 0.8
        v = 0.42 + 0.12 * hang + 0.12 * tension - 0.12 * np.clip(dy, 0, 3) / 3
        stain = noise2(sc.w, sc.h, 14 * b.k + 2, 3, 3)
        v = v - 0.25 * np.clip(stain - 0.6, 0, 1) * 3
        if dark:
            v = v - 0.33
        sc.paint_ramp(m, "sheet", v)
        if not self.side:
            k = b.k
            face = (dx / 0.11) ** 2 + ((dy + 0.02) / 0.16) ** 2 <= 1
            shade = (0.3 if not dark else 0.0) + 0.25 * (-dx / 0.11) - 0.1 * (dy / 0.16)
            sc.paint_ramp(m & face, "sheet", shade + 0.2)
            for s in (-1, 1):
                brow = ((dx - s * 0.045) / 0.035) ** 2 + ((dy + 0.06) / 0.022) ** 2 <= 1
                sc.paint_ramp(m & brow, "sheet", 0.12 if not dark else 0.0)
            nose = (np.abs(dx) < 0.012) & (dy > -0.05) & (dy < 0.04)
            sc.paint_ramp(m & nose, "sheet", 0.75 if not dark else 0.35)
            mouth = (dx / 0.04) ** 2 + ((dy - 0.085) / 0.03) ** 2 <= 1
            sc.paint_ramp(m & mouth, "sheet", 0.02)
            wet = (dx / 0.09) ** 2 + ((dy - 0.09) / 0.07) ** 2 <= 1
            sc.paint_ramp(m & wet & ~mouth, "sheet", v - 0.1)
        for h, tips in self.hands:
            D.skin(sc, h, "pale", 9, offset=0.05 if not dark else 0.08, mottle=0.1)
            nails(sc, b, tips, h)

    def corner(self, sc, door):
        b = Body(sc, 58)
        self.figure(b, "front")
        self.texture(sc, b, "front", dark=True)

    def face(self, attack=False):
        sc = face_scene("#120f10")
        rng = np.random.default_rng(6)
        cloth = sc.new_id()
        sc.polygon([(0, 0), (F, 0), (F, F), (0, F)], 0, "sheet", cloth)
        yy, xx = np.mgrid[0:F, 0:F]
        cx, cy = 150, 140
        dx, dy = (xx - cx) / 100, (yy - cy) / 100
        r = np.sqrt(dx * dx + dy * dy) + 1e-6
        ang = np.arctan2(dy, dx)
        # the face under the cloth as a height field
        hgt = np.exp(-((dx / 0.75) ** 2 + (dy / 1.0) ** 2) ** 1.5)
        for s in (-1, 1):
            hgt += 0.25 * np.exp(-(((dx - s * 0.36) / 0.22) ** 2 + ((dy + 0.38) / 0.12) ** 2))
            hgt -= 0.22 * np.exp(-(((dx - s * 0.34) / 0.18) ** 2 + ((dy + 0.2) / 0.14) ** 2))
            hgt += 0.12 * np.exp(-(((dx - s * 0.52) / 0.12) ** 2 + ((dy - 0.12) / 0.16) ** 2))
        hgt += 0.55 * np.exp(-((dx / 0.08) ** 2 + ((dy - 0.05) / 0.32) ** 2)) * (dy > -0.3)
        mouth_r = (0.24 if not attack else 0.36, 0.14 if not attack else 0.34)
        mdy = 0.55 if not attack else 0.72
        hgt -= 0.6 * np.exp(-(((dx) / mouth_r[0]) ** 2 + ((dy - mdy) / mouth_r[1]) ** 2) ** 2)
        hgt += 0.15 * np.exp(-((dx / 0.4) ** 2 + ((dy - 0.95) / 0.15) ** 2))
        gx = np.gradient(hgt, axis=1) * 60
        gy = np.gradient(hgt, axis=0) * 60
        shade = 0.45 - 0.55 * gx - 0.35 * gy
        folds = 0.1 * np.sin(ang * 11 + 3 * r) * np.clip(r - 0.9, 0, 1) + 0.07 * np.sin(dx * 30 + 2 * np.sin(dy * 4)) * np.clip(r - 1.1, 0, 1)
        tension = 0.12 * np.sin(np.arctan2(dy - mdy, dx) * 14) * np.exp(-((np.sqrt(dx ** 2 + (dy - mdy) ** 2)) / 0.35))
        weave = 0.04 * ((xx + yy) % 3 == 0) - 0.03 * ((xx - yy) % 4 == 0)
        stain = noise2(F, F, 22, 5, 3)
        wet = np.exp(-((dx / 0.5) ** 2 + ((dy - mdy) / 0.42) ** 2))
        v = shade + folds + tension + weave - 0.3 * np.clip(stain - 0.6, 0, 1) * 3 - 0.32 * wet
        sc.paint_ramp(sc.obj == cloth, "sheet", v)
        mouth = ((dx / (mouth_r[0] * 0.8)) ** 2 + ((dy - mdy) / (mouth_r[1] * 0.75)) ** 2) <= 1
        sc.paint_ramp(mouth, "sheet", 0.0 + 0.08 * (dy < mdy - mouth_r[1] * 0.4))
        if attack:
            hole = D.tear(sc, cloth, [(cx - 30, cy + 52), (cx + 34, cy + 44), (cx + 40, cy + 104), (cx - 26, cy + 112)],
                          rng, fibre="sheet", depth_ramp="gum")
            D.maw(sc, cloth, cx + 4, cy + 52, 66, 60, seed=4, tooth_w=8, missing=0.1, saliva=5, curve=2)
            for i in range(40):
                t = rng.uniform(0, 2 * np.pi)
                rr = rng.uniform(40, 62)
                x0, y0 = cx + 4 + np.cos(t) * rr, cy + 78 + np.sin(t) * rr * 0.9
                D.walk(sc, x0, y0, t, int(rng.integers(3, 9)), rng, colour=D.ramp_at("sheet", 0.85), only=cloth,
                       wobble=0.3)
        else:
            for i in range(9):
                tx = cx - 28 + i * 7
                D.tooth(sc, cloth, tx, cy + mdy * 100 - 9, 6, 5, rng, down=True)
                for j in range(3):
                    D.put(sc, tx - 2 + j * 2, cy + mdy * 100 - 10, D.ramp_at("sheet", 0.3), cloth)
            D.drips(sc, cloth, cx - 26, cx + 26, int(cy + mdy * 100 + 14), 6, rng, "sheet", (6, 30), 0.15)
        return sc.render(rim=0.0)

    def detail(self):
        sc = Scene(240, F)
        sc.bg[:] = rgb("#120f10")
        rng = np.random.default_rng(10)
        cloth = sc.new_id()
        sc.polygon([(0, 0), (240, 0), (240, F), (0, F)], 0, "sheet", cloth)
        yy, xx = np.mgrid[0:F, 0:240]
        dx, dy = (xx - 120) / 60, (yy - 150) / 60
        r = np.sqrt(dx * dx + dy * dy)
        v = 0.4 + 0.15 * np.sin(np.arctan2(dy, dx) * 10 + r * 2) * np.exp(-r / 2) - 0.45 * np.exp(-(r / 1.1) ** 2)
        v += 0.04 * ((xx + yy) % 3 == 0) - 0.03 * ((xx - yy) % 4 == 0)
        sc.paint_ramp(sc.obj == cloth, "sheet", v)
        D.tear(sc, cloth, [(96, 128), (150, 124), (146, 168), (100, 170)], rng, fibre="sheet", depth_ramp="gum")
        for i in range(5):
            D.tooth(sc, cloth, 104 + i * 9, 134, 8, 9, rng, down=True)
        for i in range(4):
            D.tooth(sc, cloth, 108 + i * 9, 164, 8, 8, rng, down=False)
        D.drips(sc, cloth, 90, 160, 172, 9, rng, "sheet", (10, 60), 0.12)
        return sc.render(rim=0.0)


CONCEPTS = [Card(), Puppeteer(), Collage(), Radiant(), Bust(), Sheet()]


def board(paths, out, scale=0.5):
    ims = [Image.open(p) for p in paths]
    ims = [i.resize((int(i.width * scale), int(i.height * scale)), Image.NEAREST) for i in ims]
    cols = 2
    w = max(i.width for i in ims)
    rows = (len(ims) + cols - 1) // cols
    hs = [max(i.height for i in ims[r * cols:(r + 1) * cols]) for r in range(rows)]
    full = Image.new("RGB", (w * cols, sum(hs)), (21, 19, 22))
    y = 0
    for r in range(rows):
        for c in range(cols):
            i = r * cols + c
            if i < len(ims):
                full.paste(ims[i], (c * w, y))
        y += hs[r]
    full.save(out)


def main(argv):
    os.makedirs(OUT, exist_ok=True)
    chosen = [c for c in CONCEPTS if not argv or c.key in argv]
    paths = []
    for c in chosen:
        path = os.path.join(OUT, c.key + ".png")
        sheet(c.title, c.lines, c.panels(), path)
        paths.append(path)
        print("wrote", path)
    if not argv:
        board(paths, os.path.join(OUT, "board.png"))


if __name__ == "__main__":
    main(sys.argv[1:])
