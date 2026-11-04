#!/usr/bin/env python3
"""Round 2: the chosen design, drawn as pixel art from the rig the 3D model uses.

    python tools/concepts/draw_final.py

The body is posed with tools/creature/poses.py (the same skeleton, poses and clips the game uses)
and drawn as shaded capsules; the head is the card from face_art.py, texture-mapped onto the
card's plane wherever the neck puts it. Writes docs/concepts/final_turnaround.png,
final_faces.png and final_scenes.png.
"""
import os
import sys

import numpy as np
from PIL import Image, ImageDraw

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
sys.path.insert(0, os.path.join(os.path.dirname(HERE), "creature"))
import details as D  # noqa: E402
import face_art as FA  # noqa: E402
import poses as P  # noqa: E402
from pixelart import BAYER4, RAMPS, Scene, font, noise2, rgb, upscale, wallpaper  # noqa: E402

ROOT = os.path.dirname(os.path.dirname(HERE))
OUT = os.path.join(ROOT, "docs", "concepts")
K = 2
JOINTS = P.rig()
BODY = {j.name for j in P.skeleton()}
BY = {j.name: j for j in JOINTS}
PALE = ("f0", "f1", "f2", "f3", "thumb", "palm")
BALLS = ("shoulder", "elbow", "wrist", "hip_", "knee", "ankle", "neck", "spine", "clav")


class View:
    """Orthographic camera: turn the world by yaw about Z, tip it by pitch, look along +Y."""

    def __init__(self, sc, cx, ground, s, yaw=0.0, pitch=0.0, origin=(0.0, 0.0, 0.0)):
        self.sc, self.cx, self.ground, self.s = sc, cx, ground, s
        self.cy, self.sy = np.cos(np.radians(yaw)), np.sin(np.radians(yaw))
        self.cp, self.sp = np.cos(np.radians(pitch)), np.sin(np.radians(pitch))
        self.origin = np.asarray(origin, float)

    def cam(self, p):
        x, y, z = np.asarray(p, float) - self.origin
        x, y = x * self.cy - y * self.sy, x * self.sy + y * self.cy
        y, z = y * self.cp - z * self.sp, y * self.sp + z * self.cp
        return x, y, z

    def p(self, p):
        x, y, z = self.cam(p)
        return (self.cx + x * self.s, self.ground - z * self.s, -y * self.s)


def posed(pose):
    mats = P.joint_transforms(JOINTS, pose)
    pts = {j.name: (mats[j.name] @ np.append(j.pos, 1.0))[:3] for j in JOINTS}
    return mats, pts


def ground_pose(pose, hands=False):
    return P.grounded(P.skeleton(), pose, hands=hands)


def draw_body(view, pts, light=1.0, shift=(0.0, 0.0, 0.0)):
    """The flat-black puppet: capsules along every bone, glossy ball joints, pale hands."""
    sc = view.sc
    s = view.s
    shift = np.asarray(shift, float)
    black = sc.new_id()
    pale = sc.new_id()
    balls = []
    for j in JOINTS:
        if j.name not in BODY or j.parent is None:
            continue
        a, b = pts[j.parent] + shift, pts[j.name] + shift
        ra, rb = BY[j.parent].radius[0], j.radius[0]
        is_pale = j.name.startswith(PALE) and not j.parent.startswith("wrist") or j.name.startswith("palm")
        if j.name.startswith(PALE) and j.parent.startswith("wrist"):
            is_pale = True
        mat, obj = ("pale", pale) if is_pale else ("black", black)
        sc.capsule(view.p(a), view.p(b), max(ra * s, 0.5), max(rb * s, 0.5), mat, obj)
        if j.name.startswith(BALLS) and not j.name.startswith(("uarm", "farm", "thigh", "shin")):
            r = j.radius[0] * 1.25
            sc.ellipsoid(view.p(b), r * s, r * s, r * s, "black", black)
            balls.append((view.p(b), r * s))
    return black, pale, balls


def gloss(sc, balls, obj, strength=1.0):
    """Lacquered puppet joints: a dull highlight on each ball."""
    for (x, y, z), r in balls:
        hx, hy = x - r * 0.35, y - r * 0.4
        xi, yi = int(round(hx)), int(round(hy))
        if 0 <= xi < sc.w and 0 <= yi < sc.h and sc.obj[yi, xi] == obj:
            c = np.array([70.0, 64.0, 72.0]) * strength
            sc.paint[yi, xi] = c
            sc.painted[yi, xi] = True
            if r > 3 and xi + 1 < sc.w and sc.obj[yi, xi + 1] == obj:
                sc.paint[yi, xi + 1] = c * 0.7
                sc.painted[yi, xi + 1] = True


_ART = {}


def card_art(params, back=False):
    key = (tuple(sorted(params.items())), back)
    if key not in _ART:
        if back:
            _ART[key] = back_art()
        else:
            img, a = FA.face(**params)
            _ART[key] = (img, a)
    return _ART[key]


def back_art(seed=5):
    th = FA.H + FA.EXTRA
    mask = FA.card_mask(total_h=th)
    yy, xx = np.mgrid[0:th, 0:FA.W]
    v = 0.6 + 0.16 * (noise2(FA.W, th, 9, seed, 4) - 0.5) - 0.3 * np.clip(noise2(FA.W, th, 22, seed + 3, 3) - 0.6, 0, 1) * 3
    R = RAMPS["paper"]
    b = BAYER4[yy % 4, xx % 4]
    img = R[np.clip(np.floor(v * (len(R) - 1) + 0.5 + b), 0, len(R) - 1).astype(int)]
    # pencil, faint: something written on the back and crossed out
    rng = np.random.default_rng(seed)
    for row in range(5):
        y0 = 90 + row * 26
        x = 30.0
        while x < FA.W - 40:
            w = rng.uniform(8, 26)
            for t in np.linspace(0, 1, int(w * 2)):
                xi = int(x + t * w)
                yi = int(y0 + 3 * np.sin(t * 9 + row) + rng.normal(0, 0.5))
                if 0 <= yi < th and 0 <= xi < FA.W and mask[yi, xi]:
                    img[yi, xi] = img[yi, xi] * 0.72
            x += w + rng.uniform(5, 10)
        for t in np.linspace(0, 1, 300):
            xi = int(26 + t * (FA.W - 60))
            yi = int(y0 + 2 + rng.normal(0, 0.6))
            if 0 <= yi < th and mask[yi, xi]:
                img[yi, xi] = img[yi, xi] * 0.6
    return img, mask


def draw_card(view, mats, params=None, light=1.0, shift=(0.0, 0.0, 0.0)):
    """Maps the card art onto the card's plane wherever the neck has put it."""
    sc = view.sc
    params = params or {}
    M = mats["neck2"]
    shift = np.asarray(shift, float)
    th = FA.H + FA.EXTRA
    hx, hz = P.CARD[0], P.CARD[2]

    def world(u, v, depth=0.0):
        local = np.array([-hx + 2 * hx * u / FA.W, -P.CARD[1] + depth, hz - 2 * hz * v / FA.H])
        return (M @ np.append(P.HEAD_CENTER + local, 1.0))[:3] + shift

    o = np.array(view.p(world(0, 0)))
    u = np.array(view.p(world(FA.W, 0))) - o
    v = np.array(view.p(world(0, th))) - o
    normal = np.cross(u[:2].tolist() + [0], v[:2].tolist() + [0])[2]
    back = normal < 0
    img, alpha = card_art(params, back)
    # shrink the art to roughly its size on screen first, so it samples cleanly
    w_px = max(4, int(np.hypot(*u[:2])))
    h_px = max(6, int(np.hypot(*v[:2])))
    if w_px < FA.W * 0.8:
        small = Image.fromarray(img.astype(np.uint8)).resize((w_px, h_px), Image.LANCZOS)
        a_small = Image.fromarray((alpha * 255).astype(np.uint8)).resize((w_px, h_px), Image.LANCZOS)
        img_s = np.asarray(small).astype(float)
        alpha_s = np.asarray(a_small) > 110
    else:
        img_s, alpha_s = img, alpha
    sh, sw = alpha_s.shape
    A = np.array([[u[0], v[0]], [u[1], v[1]]])
    if abs(np.linalg.det(A)) < 1e-6:
        return None
    Ai = np.linalg.inv(A)
    x0 = int(max(0, min(o[0], o[0] + u[0], o[0] + v[0], o[0] + u[0] + v[0]) - 1))
    x1 = int(min(sc.w, max(o[0], o[0] + u[0], o[0] + v[0], o[0] + u[0] + v[0]) + 2))
    y0 = int(max(0, min(o[1], o[1] + u[1], o[1] + v[1], o[1] + u[1] + v[1]) - 1))
    y1 = int(min(sc.h, max(o[1], o[1] + u[1], o[1] + v[1], o[1] + u[1] + v[1]) + 2))
    obj = sc.new_id()
    for py in range(y0, y1):
        for px in range(x0, x1):
            a, b = Ai @ np.array([px + 0.5 - o[0], py + 0.5 - o[1]])
            if not (0 <= a < 1 and 0 <= b < 1):
                continue
            ax = int(a * sw) if not back else int((1 - a) * sw)
            by = int(b * sh)
            ax = min(max(ax, 0), sw - 1)
            if not alpha_s[by, ax]:
                continue
            depth = o[2] + a * u[2] + b * v[2] + 0.5
            if depth <= sc.z[py, px]:
                continue
            sc.z[py, px] = depth
            sc.obj[py, px] = obj
            sc.mat[py, px] = "paper"
            sc.paint[py, px] = np.clip(img_s[by, ax] * light, 0, 255)
            sc.painted[py, px] = True
    return obj


def creature(view, pose, params=None, light=1.0, shift=(0.0, 0.0, 0.0), card_light=None):
    mats, pts = posed(pose)
    black, pale, balls = draw_body(view, pts, light, shift)
    card = draw_card(view, mats, params, light if card_light is None else card_light, shift)
    return black, pale, balls, card, pts


def finish(sc, black, pale, balls, pale_light=0.0, seed=4):
    D.skin(sc, pale, "pale", seed, offset=pale_light, mottle=0.1)
    gloss(sc, balls, black)


# ------------------------------------------------------------------------------------------------
# Sheets
# ------------------------------------------------------------------------------------------------

def lay(rows, title, lines, out, k=K):
    gap = 6
    label_h = 12
    W = max(sum(p.shape[1] for _, p in r) + gap * (len(r) + 1) for r in rows)
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
    img = upscale(canvas, k)
    head = 44 + 22 * len(lines) + 10
    full = Image.new("RGB", (img.width, img.height + head), (21, 19, 22))
    full.paste(img, (0, head))
    d = ImageDraw.Draw(full)
    d.text((18, 10), title, fill=(236, 228, 210), font=font(30, bold=True, serif=True))
    for i, t in enumerate(lines):
        d.text((20, 52 + i * 22), t, fill=(175, 165, 150), font=font(17))
    for x, yl, label in labels:
        d.text((x * k + 4, head + yl * k), label, fill=(150, 140, 125), font=font(16))
    full.save(out)
    print("wrote", out)


def plain_bg(w, h, ground, tone="#8b857b"):
    yy, xx = np.mgrid[0:h, 0:w]
    v = 0.55 + 0.08 * (noise2(w, h, 40, 1, 2) - 0.5)
    R = RAMPS["grey"]
    b = BAYER4[yy % 4, xx % 4]
    g = R[np.clip(np.floor(v * 7 + 0.5 + b), 0, 7).astype(int)] * (rgb(tone) / 140.0)
    g[ground:] = rgb("#4a4640")
    return g


def turnaround():
    pose = ground_pose(P.POSES["stand"])
    U = 86
    h = 320
    ground = 306
    panels = []
    for label, yaw in (("front", 0), ("three-quarter", 38), ("side", 90), ("back", 180)):
        sc = Scene(230, h)
        sc.bg[:] = plain_bg(230, h, ground - 1)
        view = View(sc, 115, ground, U, yaw=yaw)
        black, pale, balls, card, _ = creature(view, pose)
        finish(sc, black, pale, balls)
        panels.append((label, sc.render(rim=0.35)))
    # the hand, close
    panels.append(("its hand: pale, a knuckle too many, sewn on at the wrist", hand_closeup()))
    return panels


def hand_closeup(w=260, h=320):
    sc = Scene(w, h)
    sc.bg[:] = wallpaper(w, h, 7, dark=0.25)
    rng = np.random.default_rng(3)
    wrist = np.array([130.0, -10.0])
    arm = sc.new_id()
    sc.capsule((130, -40, 0), (130, 52, 0), 15, 13, "black", arm)
    palm = sc.new_id()
    sc.ellipsoid((131, 78, 4), 25, 34, 9, "pale", palm)
    fingers = []
    for f, (dx, ln, ang) in enumerate(((-17, 0.94, 0.16), (-6, 1.0, 0.05), (6, 0.96, -0.05), (16, 0.84, -0.16))):
        x, y = 131 + dx, 104.0
        a = np.pi / 2 + ang
        pts = [(x, y)]
        for k, seg in enumerate(P.FINGER_SEGMENTS):
            L = seg * ln * 420
            a += 0.18 + 0.06 * k
            x += np.cos(a) * L
            y += np.sin(a) * L
            pts.append((x, y))
        fingers.append(D.finger(sc, pts, 7.0, 4.4, "pale", obj=palm, rng=rng))
    thumb = D.finger(sc, [(112, 66), (96, 92), (90, 116), (88, 134)], 7.5, 5.0, "pale", obj=palm, rng=rng)
    D.skin(sc, palm, "pale", 5, offset=0.06, mottle=0.12, pores=0.03, veins=9, blotches=0.1)
    for f in fingers + [thumb]:
        D.finish_finger(sc, f, "pale", rng, dirt=0.6)
    # the seam where the pale hand is sewn to the black arm
    D.stitches(sc, [palm, arm], [(104, 50), (118, 47), (131, 46), (144, 47), (158, 50)], spacing=6, size=4)
    D.skin(sc, arm, "black", 2, offset=0.0, mottle=0.08)
    return sc.render(rim=0.2)


def faces_sheet():
    variants = [
        ("1  first: barely awake", dict(lids=(0.7, 0.55), look=((0.0, 0.3), (0.1, 0.3)), wet=0.6, seed=1)),
        ("2  looking", dict(lids=(0.15, 0.2), look=((0.0, 0.0), (0.0, 0.0)), wet=0.8, seed=2)),
        ("3  not quite at you", dict(lids=(0.2, 0.05), look=((0.5, -0.1), (-0.2, 0.3)), wet=1.0, wear=0.3, seed=3)),
        ("4  smiling", dict(lids=(0.1, 0.1), look=((0.1, 0.0), (-0.1, 0.0)), smile=0.8, wet=1.2, wear=0.6, seed=4)),
        ("5  wrong", dict(lids=(0.0, 0.45), look=((0.9, -0.5), (-0.7, 0.4)), smile=0.4, wet=1.4, wear=1.0, blood=0.8, seed=5)),
    ]
    row1 = []
    for label, p in variants:
        img, a = FA.face(**p)
        img = img[:FA.H + 8]
        a = a[:FA.H + 8]
        img[~a] = rgb("#1b191c")
        row1.append((label, img))
    attack = [
        ("it blinks", dict(lids=(0.6, 0.6), seed=6)),
        ("slowly", dict(lids=(1.0, 1.0), seed=6)),
        ("the stitches go", dict(lids=(0.05, 0.0), jaw=36, seed=6, blood=0.3)),
        ("and the jaw keeps coming", dict(lids=(0.0, 0.0), jaw=110, seed=6, blood=0.9, look=((0.0, 0.2), (0.0, 0.2)))),
    ]
    row2 = []
    for label, p in attack:
        img, a = FA.face(**p)
        img[~a] = rgb("#1b191c")
        row2.append((label, img))
    row2.append(("the last frame: fingers round the edges of your screen", screen_fingers()))
    return [row1, row2]


def screen_fingers(w=300, h=FA.H + FA.EXTRA):
    """The attack's last beat, as the player sees it: the menu torn away, the face too close, and
    long pale fingers curling in round the edges of the screen."""
    sc = Scene(w, h)
    yy, xx = np.mgrid[0:h, 0:w]
    sc.bg[:] = rgb("#0b0a0c")
    img, a = FA.face(lids=(0.0, 0.0), jaw=70, seed=6, blood=0.6)
    big = Image.fromarray(img.astype(np.uint8)).resize((int(FA.W * 1.6), int((FA.H + FA.EXTRA) * 1.6)), Image.NEAREST)
    am = Image.fromarray((a * 255).astype(np.uint8)).resize(big.size, Image.NEAREST)
    big = np.asarray(big).astype(float)
    am = np.asarray(am) > 0
    ox, oy = int(w / 2 - big.shape[1] / 2), -150
    for y in range(h):
        for x in range(w):
            by, bx = y - oy, x - ox
            if 0 <= by < big.shape[0] and 0 <= bx < big.shape[1] and am[by, bx]:
                sc.bg[y, x] = big[by, bx]
    rng = np.random.default_rng(9)
    # four fingers from the left edge, four from the right, curling in
    for side in (-1, 1):
        for f in range(4):
            y0 = 90 + f * 52 + rng.normal(0, 6)
            x0 = -6 if side < 0 else w + 6
            pts = [(x0, y0)]
            a = 0.0 if side < 0 else np.pi
            x, y = x0, y0
            for k, seg in enumerate(P.FINGER_SEGMENTS):
                L = seg * 330 * (1.0 - 0.1 * abs(f - 1.5))
                a += side * -0.32 * (k + 1) * 0.6 * (1 if side < 0 else -1) * -1
                x += np.cos(a) * L
                y += np.sin(a) * L + 4
                pts.append((x, y))
            fg = D.finger(sc, pts, 9.5, 6.0, "pale", rng=rng)
            D.skin(sc, fg["obj"], "pale", 3 + f, offset=0.02, mottle=0.12, pores=0.03, veins=3)
            D.finish_finger(sc, fg, "pale", rng, dirt=0.7)
    img = sc.render(rim=0.0)
    # tape damage across it
    for band in range(4):
        y = int(rng.integers(0, h - 6))
        n = int(rng.integers(2, 6))
        img[y:y + n] = np.roll(img[y:y + n], int(rng.integers(-30, 30)), axis=1)
    return img


def room(w, h, ground, dark=0.62, seed=5):
    bg = wallpaper(w, h, seed, dark=dark, ramp="wall")
    yy, xx = np.mgrid[0:h, 0:w]
    floor = yy >= ground
    planks = RAMPS["wood"][np.clip(2 + ((xx // 22 + (yy // 6)) % 2), 0, 5)]
    bg[floor] = planks[floor] * (0.5 - dark * 0.3)
    return bg


def scene_doorframe():
    """Round the door frame: the rest of it behind the wall, its face leaning out into the doorway
    past the jamb, its long fingers curled round the frame, one hand high, one low."""
    w, h, ground = 330, 300, 288
    sc = Scene(w, h)
    sc.bg[:] = room(w, h, ground)
    sc.amb, sc.dif = 0.08, 0.3
    U = 80
    view = View(sc, 210, ground, U, yaw=-18)
    pose = ground_pose(P.merge(P.POSES["peek_right"], P.finger_curl(70, "L"), P.finger_curl(70, "R"), {
        "shoulder_L": (-30, -40, 0), "elbow_L": (-100, 0, 0), "wrist_L": (0, -30, 0),
        "shoulder_R": (-60, 30, 0), "elbow_R": (-40, 0, 0), "wrist_R": (0, 40, 0),
        "spine1": (6, -8, 0), "spine2": (6, -10, 0), "chest": (6, -8, 0), "neck0": (30, -12, 0), "neck2": (-64, -30, 0)}))
    # it has bent its knees to get its head under the lintel; the body is behind the wall anyway
    black, pale, balls, card, pts = creature(view, pose, dict(lids=(0.15, 0.1), look=((0.6, 0.1), (0.5, 0.1)), seed=3),
                                             card_light=0.62, shift=(0.0, 0.0, -1.05))
    finish(sc, black, pale, balls, pale_light=-0.1)
    img = sc.render(rim=0.06)
    # the wall in front: everything of it right of the jamb is hidden, except fingers on the frame
    jamb = 186
    top = ground - 170
    wall_img = room(w, h, ground, dark=0.5)
    yy, xx = np.mgrid[0:h, 0:w]
    wall = ((xx > jamb) | (xx < jamb - 84) | (yy < top)) & (yy < ground)
    keep = (sc.obj == pale) & (xx > jamb) & (xx < jamb + 16) & (yy > top)
    img[wall & ~keep] = wall_img[wall & ~keep]
    frame = ((np.abs(xx - jamb - 3) < 4) | (np.abs(xx - (jamb - 87)) < 4)) & (yy > top - 6) & (yy < ground)
    frame |= (np.abs(yy - top + 3) < 4) & (xx > jamb - 91) & (xx < jamb + 7)
    img[frame & ~keep] = RAMPS["wood"][2] * 0.55
    beyond = (xx >= jamb - 84) & (xx <= jamb) & (yy >= top) & (yy < ground) & (sc.obj < 0)
    img[beyond] = RAMPS["night"][1]
    return ("round the door frame", img)


def scene_ceiling():
    """Lying in bed, looking up: its face is on your ceiling, pressed flat, looking down. The neck
    goes away across the ceiling to wherever the rest of it is standing."""
    w, h = 330, 300
    sc = Scene(w, h)
    yy, xx = np.mgrid[0:h, 0:w]
    planks = RAMPS["wood"][np.clip(1 + ((yy // 30 + (xx // 90) % 2) % 2) + ((xx % 90) == 0) * -1, 0, 5)] * 0.5
    sc.bg[:] = planks
    sc.bg[(yy % 30) == 0] *= 0.6
    img, a = FA.face(lids=(0.0, 0.0), look=((0.0, 0.0), (0.0, 0.0)), wet=1.3, seed=4)
    img = img[:FA.H]
    a = a[:FA.H]
    small = np.asarray(Image.fromarray(img.astype(np.uint8)).resize((96, 166), Image.LANCZOS)).astype(float)
    am = np.asarray(Image.fromarray((a * 255).astype(np.uint8)).resize((96, 166), Image.LANCZOS)) > 110
    # the neck, from the back of the card away to the edge
    neck = sc.new_id()
    sc.capsule((176, 160, -5), (330, 300, -40), 5.5, 7.5, "black", neck)
    for t in (0.25, 0.5, 0.75):
        sc.ellipsoid((176 + 154 * t, 160 + 140 * t, -5 - 35 * t), 8, 8, 8, "black", neck)
    hand = sc.new_id()
    rng = np.random.default_rng(5)
    base = np.array([262.0, 96.0])
    sc.ellipsoid((base[0], base[1], 2), 12, 16, 5, "pale", hand, angle=0.4)
    fingers = []
    for f, a0 in enumerate((-2.2, -1.9, -1.6, -1.3)):
        pts = [base]
        ang = a0
        q = base.copy()
        for k, seg in enumerate(P.FINGER_SEGMENTS):
            ang += 0.05
            q = q + np.array([np.cos(ang), np.sin(ang)]) * seg * 300
            pts.append(q.copy())
        fingers.append(D.finger(sc, pts, 4.2, 2.6, "pale", obj=hand, rng=rng, z=3))
    img_out = sc.render(rim=0.2)
    D.skin(sc, hand, "pale", 3, offset=-0.12, mottle=0.12, veins=4)
    for f in fingers:
        D.finish_finger(sc, f, "pale", rng, dirt=0.6)
    img_out = sc.render(rim=0.2)
    ox, oy = 118, 70
    region = img_out[oy:oy + 166, ox:ox + 96]
    region[am] = small[am] * 0.8
    img_out[oy:oy + 166, ox:ox + 96] = region
    return ("from your bed, looking up", img_out)


def scene_crawl():
    w, h, ground = 330, 300, 270
    sc = Scene(w, h)
    sc.bg[:] = room(w, h, ground, dark=0.5)
    sc.amb, sc.dif = 0.1, 0.4
    U = 80
    view = View(sc, 175, ground, U, yaw=32)
    clips = P.clips(P.skeleton())
    pose = dict(clips["crawl"][2][2])
    pose["neck2"] = (-70, 0, 10)
    black, pale, balls, card, pts = creature(view, pose, dict(lids=(0.0, 0.0), look=((0.0, -0.4), (0.0, -0.4)), seed=4),
                                             card_light=0.7)
    # the doorway it came through, around it
    door = (np.abs(sc.X - 165) < 45) & (sc.Y > ground - 160) & (sc.Y < ground)
    sc.bg[door] = RAMPS["night"][1]
    finish(sc, black, pale, balls)
    return ("through a doorway, low, its face up at you", sc.render(rim=0.2))


def scene_giant():
    w, h = 330, 300
    sc = Scene(w, h)
    yy, xx = np.mgrid[0:h, 0:w]
    t = yy / h
    R = RAMPS["sky"]
    b = BAYER4[yy % 4, xx % 4]
    sc.bg[:] = R[np.clip(np.floor(t * 1.3 * (len(R) - 1) + 0.5 + b), 0, len(R) - 1).astype(int)]
    sc.amb, sc.dif = 0.12, 0.4
    s = 10.0
    view = View(sc, 165, 262, s * 4, yaw=0)
    pose = P.merge(P.POSES["stand"], P.finger_curl(70, "L"), P.finger_curl(70, "R"), {
        "shoulder_L": (0, -96, 0), "elbow_L": (0, 74, 0), "shoulder_R": (0, 96, 0), "elbow_R": (0, -74, 0),
        "neck2": (-40, 0, 0), "neck0": (40, 0, 0)})
    black, pale, balls, card, pts = creature(view, pose, dict(lids=(0.2, 0.2), seed=1), card_light=0.85)
    # a treeline in front: blocky oaks, the hands resting on the two nearest
    rng = np.random.default_rng(3)
    for x in range(-10, w + 10, 18):
        top = 212 + rng.integers(-10, 9)
        sc.polygon([(x, top), (x + 20, top), (x + 20, h), (x, h)], 9999, "leaf", None, flat=0.24 + 0.1 * rng.random())
    finish(sc, black, pale, balls, pale_light=0.05)
    return ("act two: over the trees, hands on them like a door frame", sc.render(rim=0.3, outline=False))


def scene_bed():
    """In your bed, under the sheet: too long for it, its face pushed up through the cloth."""
    w, h = 330, 300
    sc = Scene(w, h)
    sc.bg[:] = room(w, h, 300, dark=0.45)
    yy, xx = np.mgrid[0:h, 0:w]
    # a Minecraft bed seen from above and the side, the frame, the pillow
    bed = sc.new_id()
    sc.polygon([(40, 110), (300, 110), (300, 230), (40, 230)], 10, "wood", bed, flat=0.35)
    pillow = sc.new_id()
    sc.polygon([(46, 116), (100, 116), (100, 224), (46, 224)], 12, "bone", pillow, flat=0.7)
    cloth = sc.new_id()
    sheet = (sc.X > 70) & (sc.X < 322) & (np.abs(sc.Y - 170) < 58 - 6 * np.exp(-((sc.X - 320) / 30) ** 2))
    sc.z[sheet] = 20
    sc.obj[sheet] = cloth
    sc.mat[sheet] = "sheet"
    # what is under it: a long body, bony knees, a face near the pillow, mouth open
    cx, cy = 128, 170
    dx = (sc.X - cx) / 30.0
    dy = (sc.Y - cy) / 26.0
    hgt = 1.1 * np.exp(-((dx ** 2) + (dy / 1.1) ** 2) ** 1.3)
    hgt += 0.9 * np.exp(-(((sc.X - cx + 2) / 5.5) ** 2 + ((sc.Y - cy) / 13.0) ** 2))  # nose
    for s_ in (-1, 1):
        hgt -= 0.55 * np.exp(-(((sc.X - cx + 14) / 7) ** 2 + ((sc.Y - cy - s_ * 13) / 7.5) ** 2))  # sockets
        hgt += 0.35 * np.exp(-(((sc.X - cx + 22) / 5) ** 2 + ((sc.Y - cy - s_ * 13) / 9) ** 2))  # brow
    hgt -= 0.9 * np.exp(-(((sc.X - cx - 20) / 6) ** 2 + ((sc.Y - cy) / 9) ** 2))  # open mouth
    hgt += 0.35 * np.exp(-(((sc.X - cx - 34) / 7) ** 2 + ((sc.Y - cy) / 13) ** 2))  # chin
    body = np.exp(-(((sc.Y - 170) / 22) ** 2)) * np.clip((sc.X - 152) / 40, 0, 1) * 0.6
    for k in (215, 262, 306):
        body += 0.45 * np.exp(-(((sc.X - k) / 8) ** 2 + ((sc.Y - 158) / 14) ** 2))
    hgt += body
    gx = np.gradient(hgt, axis=1) * 11
    gy = np.gradient(hgt, axis=0) * 11
    folds = 0.07 * np.sin((sc.Y - 170) * 0.3 + 3 * np.sin(sc.X * 0.04)) * np.clip((sc.X - 160) / 60, 0, 1)
    wet = np.exp(-(((sc.X - cx - 20) / 18) ** 2 + ((sc.Y - cy) / 16) ** 2))
    v = 0.5 - 0.6 * gx - 0.5 * gy + folds - 0.35 * wet
    sc.paint_ramp(sheet, "sheet", v)
    # a hand hanging off the side of the bed, out from under the sheet
    rng = np.random.default_rng(2)
    hand = sc.new_id()
    sc.ellipsoid((214, 230, 30), 9, 7, 4, "pale", hand)
    fingers = []
    for f_, (dxf, curl) in enumerate(((-8, 0.10), (-3, 0.04), (2, -0.02), (7, -0.08))):
        pts = [(214 + dxf, 234)]
        ang = np.pi / 2 + curl
        q = np.array(pts[0], float)
        for k_, seg in enumerate(P.FINGER_SEGMENTS):
            ang += curl * 0.6 + 0.05
            q = q + np.array([np.cos(ang), np.sin(ang)]) * seg * 150
            pts.append(tuple(q))
        fingers.append(D.finger(sc, pts, 2.4, 1.4, "pale", obj=hand, rng=rng, z=31))
    f = {"obj": hand}
    D.skin(sc, f["obj"], "pale", 4, offset=-0.05, mottle=0.1)
    D.skin(sc, bed, "wood", 3, offset=-0.1, mottle=0.2)
    D.skin(sc, pillow, "bone", 2, offset=-0.05, mottle=0.12)
    return ("your bed, in the morning", sc.render(rim=0.0))


def scene_light():
    """The dark-room test: the same corner at light 0, 4 and 7."""
    w, h, ground = 330, 300, 288
    out = np.zeros((h, w, 3))
    for i, (lvl, lit) in enumerate(((0, 0.16), (4, 0.42), (7, 0.7))):
        sc = Scene(110, h)
        sc.bg[:] = room(110, h, ground, dark=0.82 - lit * 0.5)
        sc.amb, sc.dif = 0.03 + lit * 0.12, lit * 0.4
        view = View(sc, 55, ground, 80, yaw=12)
        pose = ground_pose(P.POSES["stand"])
        black, pale, balls, card, pts = creature(view, pose, dict(lids=(0.2, 0.2), seed=2), card_light=0.25 + lit)
        D.skin(sc, pale, "pale", 4, offset=-0.45 + lit * 0.6, mottle=0.08)
        img = sc.render(rim=0.04)
        out[:, i * 110:(i + 1) * 110] = img
        out[:, i * 110] = 21
    return ("light 0 / 4 / 7: only the card and the hands", out)


def main():
    os.makedirs(OUT, exist_ok=True)
    lay([turnaround()], "The Wake, round 2: turnaround",
        ["The chosen design: the card from A, the puppet body and pale hands from B, the replacement faces from C, F's wetness.",
         "Flat-black stick body with lacquered ball joints like a stop-motion armature; the neck long enough to lengthen.",
         "The head: an old index card cut to a face too long, a real face pressed into it from behind, a dummy's jaw."],
        os.path.join(OUT, "final_turnaround.png"))
    lay(faces_sheet(), "The Wake, round 2: replacement faces, and the attack frame by frame",
        ["Like a stop-motion puppet, it has a set of faces, and every cut swaps one in: a little worse each time.",
         "In the corner of your eye it is wearing another one. Look at it and it is back to the first, almost.",
         "When it reaches you: one slow blink, then the stitches give and the chin piece drops, and behind it is a mouth."],
        os.path.join(OUT, "final_faces.png"))
    lay([[scene_doorframe(), scene_ceiling(), scene_crawl()], [scene_giant(), scene_bed(), scene_light()]],
        "The Wake, round 2: where you find it",
        ["Posed with the game's own rig. Silhouettes come from poses, not from things stuck on: the face and hands",
         "round a frame, the card flat against a ceiling, crawling with the face cranked up, the giant's hands on the trees."],
        os.path.join(OUT, "final_scenes.png"))


if __name__ == "__main__":
    main()
