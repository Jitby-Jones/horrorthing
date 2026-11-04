#!/usr/bin/env python3
"""Round 3: the creature's forms, as pixel art drawn from the rigs the 3D models will use.

    python tools/concepts/draw_forms.py            # every variant, then the boards
    python tools/concepts/draw_forms.py u1 a2      # just these sheets

Each variant in tools/creature/forms.py gets a sheet: a turnaround, silhouettes against the
WATCHER, its face close up, its mutation close up, and the variant where you would find it.
Each temper gets a board with its three variants side by side, for choosing between them.
Writes docs/concepts/forms/.
"""
import math
import os
import sys

import numpy as np
from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
sys.path.insert(0, os.path.join(os.path.dirname(HERE), "creature"))
import details as D  # noqa: E402
import face_art as FA  # noqa: E402
import forms as F  # noqa: E402
import poses as P  # noqa: E402
import real_face as RF  # noqa: E402
from draw_final import View, back_art, gloss, lay, plain_bg, room  # noqa: E402
from pixelart import BAYER4, RAMPS, Scene, noise2, rgb, wallpaper  # noqa: E402

ROOT = os.path.dirname(os.path.dirname(HERE))
OUT = os.path.join(ROOT, "docs", "concepts", "forms")
TH = FA.H + FA.EXTRA
HEADJ = ("jaw", "eye", "lid")


class Watcher(F.Variant):
    """The base creature, for comparison."""
    key, form, name = "w", "watcher", "WATCHER"

    def cards(self):
        return [F.Card("neck2", P.HEAD_CENTER, art={"kind": "face", "params": dict(lids=(0.2, 0.2), seed=2), "marks": []})]


WATCHER = Watcher()


# ------------------------------------------------------------------------------------------------
# Card art
# ------------------------------------------------------------------------------------------------

_ART = {}


def stroke(img, alpha, pts, r, colour, a=1.0, broken=0.0, rng=None, wax=False):
    rng = rng or np.random.default_rng(0)
    h, w = alpha.shape
    colour = np.asarray(colour, float)
    for (xa, ya), (xb, yb) in zip(pts[:-1], pts[1:]):
        n = int(max(abs(xb - xa), abs(yb - ya)) / max(0.5, r * 0.5)) + 1
        for t in np.linspace(0, 1, n):
            if broken and rng.random() < broken:
                continue
            x = xa + (xb - xa) * t + rng.normal(0, 0.12 * r)
            y = ya + (yb - ya) * t + rng.normal(0, 0.12 * r)
            x0, x1 = int(max(0, x - r)), int(min(w, x + r + 1))
            y0, y1 = int(max(0, y - r)), int(min(h, y + r + 1))
            if x0 >= x1 or y0 >= y1:
                continue
            yy, xx = np.mgrid[y0:y1, x0:x1]
            m = ((xx - x) ** 2 + (yy - y) ** 2 <= r * r) & alpha[y0:y1, x0:x1]
            if wax:
                m &= rng.random(m.shape) < 0.8
            img[y0:y1, x0:x1][m] = img[y0:y1, x0:x1][m] * (1 - a) + colour * a


def mark_crayon(img, alpha, rng, a=0.85, broken=0.22):
    """A smile somebody drew on in red crayon, far wider than its mouth."""
    pts = []
    for t in np.linspace(-1, 1, 40):
        x = FA.W / 2 + t * FA.W * 0.4
        y = FA.MOUTH_Y + 14 * (1 - t * t) - 30 * t ** 4 - 2
        pts.append((x, y))
    stroke(img, alpha, pts, 2.8, (176, 34, 26), a, broken, rng, wax=True)


def mark_scribbled(img, alpha, rng):
    mark_crayon(img, alpha, rng, a=0.6, broken=0.35)
    x = FA.W * 0.1
    zig = []
    up = True
    while x < FA.W * 0.9:
        zig.append((x, FA.MOUTH_Y + (-12 if up else 16) + rng.normal(0, 3)))
        x += rng.uniform(5, 10)
        up = not up
    stroke(img, alpha, zig, 1.6, (28, 22, 20), 0.85, 0.0, rng)


def mark_torn_eyes(img, alpha, rng):
    """Splits in the paper running out from the eye holes."""
    for (ex, ey) in (FA.EYE_L, FA.EYE_R):
        for k in range(4):
            a = rng.uniform(0, 2 * np.pi)
            x, y = ex + np.cos(a) * 22, ey + np.sin(a) * 15
            pts = [(x, y)]
            for _ in range(int(rng.integers(8, 18))):
                a += rng.normal(0, 0.3)
                x += np.cos(a) * 2.2
                y += np.sin(a) * 2.2
                pts.append((x, y))
            stroke(img, alpha, pts, 0.9, (24, 14, 12), 0.95, 0.0, rng)
            stroke(img, alpha, [(px + 1.4, py + 1.0) for px, py in pts], 0.6, (236, 228, 206), 0.6, 0.2, rng)


def mark_fold(img, alpha, rng):
    """Folded down the middle once, hard, and opened out again."""
    x = FA.W / 2
    pts = [(x + rng.normal(0, 0.4), y) for y in np.linspace(0, TH, 60)]
    stroke(img, alpha, pts, 0.9, (90, 80, 64), 0.8, 0.0, rng)
    stroke(img, alpha, [(px + 1.5, py) for px, py in pts], 0.8, (240, 233, 214), 0.55, 0.1, rng)
    # the half turned away from the light is a shade darker
    h, w = alpha.shape
    img[:, int(x) + 2:] *= 0.9


def mark_split(img, alpha, rng):
    """Torn in half down the middle: a ragged gap, white fibres on its edges."""
    h, w = alpha.shape
    xc = FA.W / 2
    off = 0.0
    for y in range(h):
        off += rng.normal(0, 0.6)
        off *= 0.97
        half = 1.8 + abs(rng.normal(0, 0.8))
        x0, x1 = int(xc + off - half), int(xc + off + half)
        alpha[y, max(0, x0):min(w, x1 + 1)] = False
        for x in (x0 - 1, x1 + 1):
            if 0 <= x < w and alpha[y, x] and rng.random() < 0.7:
                img[y, x] = RAMPS["paper"][6 + int(rng.random() < 0.4)]


MARKS = {"crayon": mark_crayon, "crayon_scribbled": mark_scribbled, "torn_eyes": mark_torn_eyes, "fold": mark_fold,
         "split": mark_split}


def blank_card(seed=21, number="33"):
    """The card with nothing behind it: ruled paper, the scissor-cut face, the dummy's cuts, the
    stitches, and two eye holes torn right through. You can see what is behind it through them."""
    rng = np.random.default_rng(seed)
    mask = FA.card_mask(total_h=TH)
    sc = Scene(FA.W, TH)
    card = sc.new_id()
    sc.obj[mask] = card
    sc.mat[mask] = "paper"
    sc.z[mask] = 10
    yy, xx = np.mgrid[0:TH, 0:FA.W]
    n1 = noise2(FA.W, TH, 9, seed, 4)
    n2 = noise2(FA.W, TH, 22, seed + 3, 3)
    edge = np.zeros_like(mask)
    for k in range(1, 7):
        for dy, dx in ((0, k), (0, -k), (k, 0), (-k, 0)):
            edge |= ~np.roll(np.roll(mask, dy, 0), dx, 1)
    v = 0.66 + 0.16 * (n1 - 0.5) - 0.3 * np.clip(n2 - 0.52, 0, 1) * 3 - 0.2 * edge
    sc.paint_ramp(mask, "paper", v)
    rules = mask & (np.abs(((yy - 40 + 9.5) % 19) - 9.5) < 0.5) & (yy > 40)
    sc.paint[rules] = sc.paint[rules] * 0.72 + np.array([96.0, 112.0, 140.0]) * 0.28
    red = mask & (np.abs(yy - 30) < 1.0)
    sc.paint[red] = sc.paint[red] * 0.5 + np.array([150.0, 62.0, 58.0]) * 0.5
    FA.typed(sc, number, 16, 12, card)
    holes = np.zeros_like(mask)
    for (ex, ey) in (FA.EYE_L, FA.EYE_R):
        pts = []
        for a in np.linspace(0, 2 * np.pi, 26, endpoint=False):
            r = 1 + 0.2 * rng.normal()
            pts.append((ex + np.cos(a) * 21 * r, ey + np.sin(a) * 14 * r))
        holes |= D.tear(sc, card, pts, rng, fibre="paper", inside="#000000")
    # the mouth: only the slit, nothing behind it
    k = (xx - FA.W / 2) / (FA.MOUTH_W / 2)
    slit = mask & (np.abs(k) < 1) & (np.abs(yy - FA.MOUTH_Y + 1.5 * k ** 2) < 1.2)
    sc.paint_mask(slit, "#1c1410")
    for cxp in FA.CUT_X:
        for y in range(int(FA.MOUTH_Y), FA.H):
            if mask[y, int(cxp)]:
                D.put(sc, cxp, y, "#140a08")
        for i in range(5):
            y = FA.MOUTH_Y + 14 + i * (FA.H - FA.MOUTH_Y - 30) / 5
            for t in np.linspace(-1, 1, 15):
                for sgn in (-1, 1):
                    D.put(sc, cxp + t * 7, y + sgn * t * 4, "#2a1a14")
    for p0, p1 in (((20, 22), (-14, -8)), ((FA.W - 18, 16), (FA.W + 16, -14)), ((FA.W - 14, 0.36 * FA.H), (FA.W + 22, 0.33 * FA.H))):
        D.pin(sc, p0, p1, head=3.5, wound_obj=card, wound_ramp="rust", drip=1, rng=rng, wound=2.5)
    img = sc.render(outline=False)
    return img, mask & ~holes


def card_art(spec):
    key = repr(sorted((k, repr(v)) for k, v in spec.items()))
    if key not in _ART:
        kind = spec["kind"]
        if kind == "face":
            img, a = FA.face(**spec.get("params", {}))
            img, a = img.astype(float).copy(), a.copy()
            rng = np.random.default_rng(7)
            for mk in spec.get("marks", []):
                MARKS[mk](img, a, rng)
        elif kind == "blank":
            img, a = blank_card(**spec.get("params", {}))
        elif kind == "real":
            img, a = RF.real_face(**spec.get("params", {}))
        else:
            img, a = back_art()
        _ART[key] = (img, a)
    return _ART[key]


def with_jaw(spec, pose):
    jaw = pose.get("jaw", 0.0)
    if spec["kind"] in ("face", "real") and jaw:
        params = dict(spec.get("params", {}))
        params["jaw"] = max(params.get("jaw", 0.0), jaw * 1.1)
        return dict(spec, params=params)
    return spec


def draw_piece(view, card, M, piece, img, alpha, back, light, shift, obj):
    sc = view.sc
    hx, hy, hz = card.half

    def world(u, v):
        local = np.array([-hx + 2 * hx * u / FA.W, -hy, hz - 2 * hz * v / FA.H, 1.0])
        q = piece.T @ local
        rest = card.center + card.R @ q[:3]
        return (M @ np.append(rest, 1.0))[:3] + shift

    o = np.array(view.p(world(piece.u0, piece.v0)))
    u = np.array(view.p(world(piece.u1, piece.v0))) - o
    v = np.array(view.p(world(piece.u0, piece.v1))) - o
    A = np.array([[u[0], v[0]], [u[1], v[1]]])
    if abs(np.linalg.det(A)) < 1e-6:
        return
    is_back = (u[0] * v[1] - u[1] * v[0]) < 0
    if is_back and back is None:
        return
    # the back is the same outline seen from behind, painted plain (see is_back below)
    crop = img[piece.v0:piece.v1, piece.u0:piece.u1]
    crop_a = alpha[piece.v0:piece.v1, piece.u0:piece.u1]
    w_px = max(2, int(np.hypot(*u[:2])))
    h_px = max(2, int(np.hypot(*v[:2])))
    ch, cw = crop_a.shape
    if w_px < cw * 0.8 or h_px < ch * 0.8:
        crop = np.asarray(Image.fromarray(np.clip(crop, 0, 255).astype(np.uint8)).resize((w_px, h_px), Image.LANCZOS)).astype(float)
        crop_a = np.asarray(Image.fromarray((crop_a * 255).astype(np.uint8)).resize((w_px, h_px), Image.LANCZOS)) > 110
    sh, sw = crop_a.shape
    Ai = np.linalg.inv(A)
    xs_ = [o[0], o[0] + u[0], o[0] + v[0], o[0] + u[0] + v[0]]
    ys_ = [o[1], o[1] + u[1], o[1] + v[1], o[1] + u[1] + v[1]]
    x0, x1 = int(max(0, min(xs_) - 1)), int(min(sc.w, max(xs_) + 2))
    y0, y1 = int(max(0, min(ys_) - 1)), int(min(sc.h, max(ys_) + 2))
    if x0 >= x1 or y0 >= y1:
        return
    yy, xx = np.mgrid[y0:y1, x0:x1]
    rx = xx + 0.5 - o[0]
    ry = yy + 0.5 - o[1]
    a = Ai[0, 0] * rx + Ai[0, 1] * ry
    b = Ai[1, 0] * rx + Ai[1, 1] * ry
    inside = (a >= 0) & (a < 1) & (b >= 0) & (b < 1)
    ax = np.clip((a * sw).astype(int), 0, sw - 1)
    by = np.clip((b * sh).astype(int), 0, sh - 1)
    ok = inside & crop_a[by, ax]
    depth = o[2] + a * u[2] + b * v[2] + 0.5
    zs = sc.z[y0:y1, x0:x1]
    ok &= depth > zs
    if is_back:
        colour = np.asarray(RAMPS["paper"][3], float) * (0.85 + 0.15 * noise2(x1 - x0, y1 - y0, 6, 3, 2))[..., None]
    else:
        colour = crop[by, ax]
    region = (slice(y0, y1), slice(x0, x1))
    sc.z[region][ok] = depth[ok]
    sc.obj[region][ok] = obj
    sc.mat[region][ok] = "paper"
    sc.paint[region][ok] = np.clip(colour[ok] * light, 0, 255)
    sc.painted[region][ok] = True


def draw_cards(view, variant, mats, pose, shift, light=1.0):
    objs = []
    for card in variant.cards():
        if card.joint not in mats:
            continue
        img, alpha = card_art(with_jaw(card.art, pose))
        back = None if card.art["kind"] == "real" else (img, alpha)
        obj = view.sc.new_id()
        for pc in card.pieces or [F.Piece(0, 0, FA.W, TH)]:
            draw_piece(view, card, mats[card.joint], pc, img, alpha, back, light, shift, obj)
        objs.append(obj)
    return objs


# ------------------------------------------------------------------------------------------------
# The body
# ------------------------------------------------------------------------------------------------

def posed(joints, pose):
    mats = P.joint_transforms(joints, pose)
    pts = {j.name: (mats[j.name] @ np.append(j.pos, 1.0))[:3] for j in joints}
    return mats, pts


def draw_body(view, variant, joints, pts, shift):
    sc = view.sc
    s = view.s
    by = {j.name: j for j in joints}
    ids = {}
    balls = []
    for j in joints:
        if j.parent is None or j.name.startswith(HEADJ):
            continue
        mat = variant.material(j.name)
        if mat is None:
            continue
        if mat not in ids:
            ids[mat] = sc.new_id()
        a, b = pts[j.parent] + shift, pts[j.name] + shift
        ra, rb = by[j.parent].radius[0], j.radius[0]
        if variant.material(j.parent) != mat:
            ra = min(ra, rb * 1.4)
        sc.capsule(view.p(a), view.p(b), max(ra * s, 0.5), max(rb * s, 0.5), mat, ids[mat])
        k = variant.ball(j.name)
        if k:
            r = j.radius[0] * k
            bm = "black" if mat == "pale" else mat
            if bm not in ids:
                ids[bm] = sc.new_id()
            sc.ellipsoid(view.p(b), r * s, r * s, r * s, bm, ids[bm])
            balls.append((view.p(b), r * s, bm))
    # the pelvis and chest are wider than they are deep
    return ids, balls


def extras(view, variant, pts, pose, shift, ids):
    fn = EXTRAS.get(variant.key)
    if fn:
        fn(view, variant, pts, pose, shift, ids)


def extra_rig(view, variant, pts, pose, shift, ids):
    """The rig arm: black metal rods and knuckle clamps, up and back out of the picture."""
    sc = view.sc
    s = view.s
    path = variant.rig_path(pts["rig0"] + shift)
    obj = ids.setdefault("metal", sc.new_id())
    for k, (a, b) in enumerate(zip(path[:-1], path[1:])):
        sc.capsule(view.p(a), view.p(b), 0.022 * s, 0.020 * s, "metal", obj)
        sc.ellipsoid(view.p(a), 0.042 * s, 0.042 * s, 0.042 * s, "metal", obj)
        # a wing screw on each knuckle
        q = view.p(a)
        sc.capsule((q[0] - 0.05 * s, q[1], q[2] + 1), (q[0] + 0.05 * s, q[1], q[2] + 1), 0.008 * s, 0.008 * s, "metal", obj)
    # the plate bolted to its back
    q = view.p(path[0])
    sc.ellipsoid(q, 0.07 * s, 0.05 * s, 0.02 * s, "metal", obj)


def extra_tape(view, variant, pts, pose, shift, ids):
    """Tape spooling out of its joints and its mouth, streaming back and down, twisting."""
    sc = view.sc
    s = view.s
    wind = np.array([0.0, 1.0, 0.0]) * (1.4 if pose.get("_moving") else 0.35)
    rng = np.random.default_rng(3)
    for joint, n, phase in F.TAPES:
        if joint not in pts:
            continue
        p = pts[joint] + shift
        d = np.array([0.0, 0.3, -1.0])
        line = [p.copy()]
        for i in range(n * 2):
            d = d + np.array([0.0, 0.0, -0.18]) + wind * 0.25 + rng.normal(0, 0.12, 3)
            d = d / (np.linalg.norm(d) + 1e-9)
            p = p + d * 0.11
            if p[2] < 0.01:
                p[2] = 0.01
                d[2] = 0.0
            line.append(p.copy())
        scr = [np.array(view.p(q)) for q in line]
        obj = sc.new_id()
        for i, (a, b) in enumerate(zip(scr[:-1], scr[1:])):
            t = math.cos(i * 0.9 + phase)
            w = max(0.6, 0.018 * s * abs(t) + 0.3)
            dd = b[:2] - a[:2]
            L = np.linalg.norm(dd) + 1e-9
            nrm = np.array([-dd[1], dd[0]]) / L * w
            quad = [a[:2] + nrm, b[:2] + nrm, b[:2] - nrm, a[:2] - nrm]
            za = (a[2] + b[2]) / 2
            sc.polygon(quad, za + 0.4, "tape", obj, flat=0.25 + 0.5 * max(0.0, t) ** 3)
        sc.no_outline.add(obj)


def extra_pins(view, variant, pts, pose, shift, ids):
    pass


def extra_tape_chain(view, variant, pts, pose, shift, ids):
    """The final FURIOUS's tape: thin glossy ribbons along its tape joints, twisting."""
    sc = view.sc
    s = view.s
    chains = {}
    for name in pts:
        if name.startswith("tape") and "k" in name:
            r, k = name[4:].split("k")
            chains.setdefault(int(r), []).append((int(k), name))
        elif name.startswith("mtape"):
            chains.setdefault(99, []).append((int(name[5:]), name))
    for r, items in chains.items():
        items.sort()
        names = [n for _, n in items]
        if r != 99 and f"tape{r}end" in pts:
            names.append(f"tape{r}end")
        line = [pts[n] + shift for n in names]
        scr = [np.array(view.p(q)) for q in line]
        obj = sc.new_id()
        for i, (a, b) in enumerate(zip(scr[:-1], scr[1:])):
            t = math.cos(i * 1.3 + r)
            w = max(0.5, 0.011 * s * abs(t) + 0.3)
            dd = b[:2] - a[:2]
            L = np.linalg.norm(dd) + 1e-9
            nrm = np.array([-dd[1], dd[0]]) / L * w
            quad = [a[:2] + nrm, b[:2] + nrm, b[:2] - nrm, a[:2] - nrm]
            sc.polygon(quad, (a[2] + b[2]) / 2 + 0.6, "tape", obj, flat=0.3 + 0.6 * max(0.0, t) ** 4)
        sc.no_outline.add(obj)


EXTRAS = {"u1": extra_rig, "f2": extra_tape, "furious": extra_tape_chain}


def creature(view, variant, pose, light=1.0, shift=(0.0, 0.0, 0.0), card_light=None):
    joints = variant.rig()
    mats, pts = posed(joints, pose)
    shift = np.asarray(shift, float)
    ids, balls = draw_body(view, variant, joints, pts, shift)
    extras(view, variant, pts, pose, shift, ids)
    cards = draw_cards(view, variant, mats, pose, shift, light if card_light is None else card_light)
    return ids, balls, pts, cards


def finish(sc, ids, balls, pale_light=0.0, seed=4):
    if "pale" in ids:
        D.skin(sc, ids["pale"], "pale", seed, offset=pale_light, mottle=0.1)
    if "black" in ids:
        gloss(sc, [(p, r) for p, r, m in balls if m == "black"], ids["black"])
    if "metal" in ids:
        D.skin(sc, ids["metal"], "metal", seed + 1, offset=-0.42, mottle=0.3, blotches=0.35)
        rust = np.isin(sc.obj, [ids["metal"]]) & (noise2(sc.w, sc.h, 5, seed + 9, 2) > 0.66)
        sc.paint_ramp(rust, "rust", 0.25 + 0.3 * noise2(sc.w, sc.h, 2, seed + 4, 1))


def stand_pose(v, name="stand"):
    return F.grounded(v, v.poses()[name])


def extent(v, pose, yaw=0.0, pitch=0.0):
    """Screen-space bounds (x right, z up, in blocks) of a posed variant seen from yaw/pitch."""
    joints = v.rig()
    mats, pts = posed(joints, pose)
    view = View(None, 0.0, 0.0, 1.0, yaw=yaw, pitch=pitch)
    xs, zs = [], []
    for j in joints:
        if j.name.startswith(HEADJ) or v.material(j.name) is None:
            continue
        x, y, d = view.p(pts[j.name])
        xs.append(x)
        zs.append(-y)
    for card in v.cards():
        if card.joint not in mats:
            continue
        hx, hy, hz = card.half
        for cu, cv in ((-1, -1), (-1, 1), (1, -1), (1, 1)):
            q = card.center + card.R @ np.array([cu * hx, 0.0, cv * hz])
            x, y, d = view.p((mats[card.joint] @ np.append(q, 1.0))[:3])
            xs.append(x)
            zs.append(-y)
    return min(xs), max(xs), min(zs), max(zs)


def fit(v, pose, yaw, box, smax, pitch=0.0, keep_ground=None):
    """Scale and placement that fit a pose into box (x0, y0, x1, y1): (s, cx, ground)."""
    x0, x1, z0, z1 = extent(v, pose, yaw, pitch)
    bx0, by0, bx1, by1 = box
    s = min(smax, (bx1 - bx0) / max(x1 - x0, 1e-3), (by1 - by0) / max(z1 - z0, 1e-3))
    cx = (bx0 + bx1) / 2 - (x0 + x1) / 2 * s
    if keep_ground is not None:
        return s, cx, keep_ground
    ground = (by0 + by1) / 2 + (z0 + z1) / 2 * s
    return s, cx, ground


# ------------------------------------------------------------------------------------------------
# Panels
# ------------------------------------------------------------------------------------------------

U = 72
PH = 330


def figure(v, pose, yaw=0.0, w=230, h=PH, s=U, ground=None, bg=None, light=1.0, rim=0.35, pitch=0.0, cx=None):
    ground = h - 14 if ground is None else ground
    if cx is None:
        s, cx, _ = fit(v, pose, yaw, (6, 6, w - 6, ground), s, pitch, keep_ground=ground)
    sc = Scene(w, h)
    sc.bg[:] = plain_bg(w, h, int(min(ground, h)) - 1) if bg is None else bg
    view = View(sc, w / 2 if cx is None else cx, ground, s, yaw=yaw, pitch=pitch)
    ids, balls, pts, cards = creature(view, v, pose, light)
    finish(sc, ids, balls)
    return sc.render(rim=rim)


def silhouette(sc_w, sc_h, v, pose, cx, ground, s, yaw=0.0, colour="#0c0b0d", into=None):
    sc = Scene(sc_w, sc_h)
    view = View(sc, cx, ground, s, yaw=yaw)
    creature(view, v, pose)
    m = sc.obj >= 0
    if into is not None:
        into[m] = rgb(colour)
    return m


def silhouettes(v):
    w, h = 300, PH
    img = np.zeros((h, w, 3))
    img[:] = rgb("#cfc8b8")
    yy, xx = np.mgrid[0:h, 0:w]
    img[(yy == 160) | (yy == 320)] = rgb("#9c9586")
    poses = v.poses()
    cells = [(WATCHER, stand_pose(WATCHER), 0.0, 0, "#5d574c"), (v, stand_pose(v), 0.0, 1, "#0c0b0d"),
             (v, stand_pose(v), 90.0, 2, "#0c0b0d")]
    for i, name in enumerate(("move", "attack", "special")):
        p = poses[name]
        hanging = v.key == "f1" and name == "special"
        cells.append((v, p if hanging else F.grounded(v, p), 70.0 if name != "special" else 25.0, 3 + i, "#0c0b0d"))
    for x, pose, yaw, i, colour in cells:
        row, col = divmod(i, 3)
        ground = 160 if row == 0 else 320
        box = (col * 100 + 4, ground - 152, col * 100 + 96, ground)
        hanging = x.key == "f1" and i == 5
        s, cx, gr = fit(x, pose, yaw, box, 38, keep_ground=None if hanging else ground)
        silhouette(w, h, x, pose, cx, gr, s, yaw=yaw, colour=colour, into=img)
    return img


def closeup(v, focus, s, yaw=0.0, pitch=0.0, pose=None, w=300, h=PH, bg="#1b191c", light=1.0, rim=0.25, dark=False):
    sc = Scene(w, h)
    if dark:
        sc.bg[:] = wallpaper(w, h, 7, dark=0.45)
    else:
        sc.bg[:] = rgb(bg)
    pose = {} if pose is None else pose
    joints = v.rig()
    mats, pts = posed(joints, pose)
    if isinstance(focus, str):
        origin = pts[focus]
    else:
        origin = np.asarray(focus, float)
    view = View(sc, w / 2, h / 2, s, yaw=yaw, pitch=pitch, origin=origin)
    ids, balls, pts, cards = creature(view, v, pose, light)
    finish(sc, ids, balls)
    return sc.render(rim=rim)


def head_focus(v, pose=None):
    joints = v.rig()
    mats, pts = posed(joints, pose or {})
    c = v.head_center()
    return (mats["neck2"] @ np.append(c, 1.0))[:3]


# -- per-variant close-ups and scenes ------------------------------------------------------------

def face_panel(v):
    k = v.key
    if k == "a1":
        return ("blank on its head; the face is in its chest", closeup(v, (0.0, 0.0, 2.75), 205))
    if k == "a2":
        return ("the old face and the new one", closeup(v, (0.0, 0.0, 3.2), 330))
    if k == "f3":
        return ("the ruff: seven faces round the end of the neck", closeup(v, head_focus(v), 300))
    if k == "u3":
        return ("put back on sideways", closeup(v, head_focus(v), 420))
    if k == "f1":
        return ("torn in half, and what was behind it", closeup(v, head_focus(v), 520))
    if k == "f2":
        return ("shredded: the face through the strips", closeup(v, head_focus(v), 520))
    if k == "u1":
        return ("folded down the middle, a smile drawn on", closeup(v, head_focus(v), 560))
    return ("its face", closeup(v, head_focus(v), 560))


def detail_panel(v):
    k = v.key
    poses = v.poses()
    if k == "u1":
        return ("the plate bolted between its shoulder blades", closeup(v, "upchest", 230, yaw=200, pitch=0, pose=poses["stand"],
                                                                      dark=True))
    if k == "u2":
        return ("the small hands, wringing", closeup(v, "spine1b", 420, yaw=10, pose=poses["stand"], dark=True))
    if k == "u3":
        return ("the leg, rebuilt backwards", closeup(v, (0.09, 0.0, 0.8), 175, yaw=90, pose=stand_pose(v), dark=True))
    if k == "a1":
        return ("the ribs are fingers", closeup(v, "chest", 380, yaw=-20, pose=poses["stand"], dark=True))
    if k == "a2":
        return ("they do not agree", closeup(v, (0.0, 0.0, 3.0), 210, yaw=25, pose=poses["stand"], dark=True))
    if k == "a3":
        return ("nine vertebrae", closeup(v, (0.0, -0.2, 3.7), 100, yaw=35, pose=stand_pose(v), dark=True))
    if k == "f1":
        return ("the second pair, from its back", closeup(v, "chest", 150, yaw=150, pitch=0, pose=stand_pose(v), dark=True))
    if k == "f2":
        return ("under the black: the armature", closeup(v, "elbow_L", 260, yaw=30, pose=stand_pose(v), dark=True))
    if k == "f3":
        return ("its back is faces", closeup(v, "spine2", 140, yaw=150, pose=stand_pose(v), dark=True))
    return ("detail", closeup(v, "chest", 300, pose=poses["stand"]))


def scene_panel(v):
    k = v.key
    fn = SCENES.get(k)
    if fn:
        return fn(v)
    w, h, ground = 360, PH, PH - 16
    return ("where you find it", figure(v, stand_pose(v, "special"), yaw=30, w=w, h=h, bg=room(w, h, ground)))


def scene_room(v, pose, yaw, label, dark=0.62, light=0.8, cx=None, w=360, s=U, rim=0.15, ground=None, after=None, before=None):
    h = PH
    ground = h - 16 if ground is None else ground
    sc = Scene(w, h)
    sc.bg[:] = room(w, h, ground, dark=dark)
    sc.amb, sc.dif = 0.12, 0.5
    if before:
        before(sc, ground)
    view = View(sc, w / 2 if cx is None else cx, ground, s, yaw=yaw)
    ids, balls, pts, cards = creature(view, v, pose, card_light=light)
    finish(sc, ids, balls, pale_light=-0.1)
    img = sc.render(rim=rim)
    if after:
        after(img, sc, ground, view, pts)
    return (label, img)


def scene_u1(v):
    pose = F.grounded(v, v.poses()["special"])
    w, h = 360, PH
    ground = h - 16
    s, cx, _ = fit(v, pose, -10, (30, 40, 330, ground), 70, keep_ground=ground)
    x0, x1, z0, z1 = extent(v, pose, -10)
    door_x = int(cx + x1 * s) - 50

    def door(sc, ground):
        yy, xx = np.mgrid[0:sc.h, 0:sc.w]
        top = int(ground - 2.2 * s)
        opening = (xx > door_x) & (xx < door_x + int(1.1 * s)) & (yy > top) & (yy < ground)
        sc.bg[opening] = RAMPS["night"][1]
        frame = ((np.abs(xx - door_x) < 4) | (np.abs(xx - door_x - int(1.1 * s)) < 4)) & (yy > top - 6) & (yy < ground)
        frame |= (np.abs(yy - top) < 4) & (xx > door_x - 4) & (xx < door_x + int(1.1 * s) + 4)
        sc.bg[frame] = RAMPS["wood"][2] * 0.6

    return scene_room(v, pose, -10, "out of the doorway, leaning further than anything could", cx=cx, s=s, before=door)


def scene_u2(v):
    pose = F.grounded(v, v.poses()["special"])
    door_x = 262

    def door(sc, ground):
        yy, xx = np.mgrid[0:sc.h, 0:sc.w]
        d = (xx > door_x) & (xx < door_x + 90) & (yy > ground - 160) & (yy < ground)
        sc.bg[d] = RAMPS["wood"][2] * 0.7
        sc.bg[d & ((xx - door_x) % 30 == 0)] = RAMPS["wood"][0]
        sc.bg[(xx > door_x + 6) & (xx < door_x + 16) & (np.abs(yy - (ground - 80)) < 3)] = RAMPS["metal"][2]
    return scene_room(v, pose, 62, "at your door: the small hand knocks; the long ones do not move", cx=170, before=door)


def scene_u3(v):
    w, h = 360, PH
    sc = Scene(w, h)
    yy, xx = np.mgrid[0:h, 0:w]
    R = RAMPS["night"]
    b = BAYER4[yy % 4, xx % 4]
    sc.bg[:] = R[np.clip(np.floor((1 - yy / h) * 0.5 * (len(R) - 1) + 0.5 + b), 0, len(R) - 1).astype(int)]
    rng = np.random.default_rng(4)
    for _ in range(40):
        sc.bg[rng.integers(0, 150), rng.integers(0, w)] = rgb("#8a8fa6")
    ridge = 200
    # a blocky roof: stairs stepping down from the ridge either side
    for i in range(8):
        y0 = ridge + i * 16
        sc.bg[(yy >= y0) & (yy < y0 + 16) & (np.abs(xx - 180) < 30 + i * 22)] = RAMPS["wood"][1 + (i % 2)] * 0.6
    sc.amb, sc.dif = 0.08, 0.35
    view = View(sc, 180, ridge, 64, yaw=60)
    pose = F.grounded(v, v.poses()["special"])
    ids, balls, pts, cards = creature(view, v, pose, card_light=0.55)
    finish(sc, ids, balls, pale_light=-0.25)
    return ("on the ridge of your roof, folded up on its legs", sc.render(rim=0.1))


def scene_a1(v):
    pose = F.grounded(v, v.poses()["attack"])
    return scene_room(v, pose, 20, "it opens", dark=0.55, light=0.85)


def scene_a2(v):
    pose = F.grounded(v, v.poses()["special"])
    return scene_room(v, pose, 10, "one on you, one on the window", dark=0.6)


def scene_a3(v):
    """From your side of the wall: the body behind it, the neck up and over, the face coming down."""
    pose = F.grounded(v, v.poses()["special"])
    wall_y = -0.5
    w, h = 360, PH
    ground = h - 16
    s, cx, _ = fit(v, pose, 0.0, (40, 20, 320, ground), 64, keep_ground=ground)
    top = int(ground - 3.0 * s)

    def wall(img, sc, ground, view, pts):
        yy, xx = np.mgrid[0:img.shape[0], 0:img.shape[1]]
        m = (yy > top) & (yy < ground)
        bx = (xx // 16 + (yy // 8) % 2) % 3
        stone = RAMPS["grey"][np.clip(1 + bx, 0, 7)] * 0.62
        stone[(yy % 8) == 0] *= 0.6
        front = sc.z > -wall_y * view.s
        img[m & ~front] = stone[m & ~front]
        img[(yy == top) | (yy == top + 1)] = RAMPS["grey"][3] * 0.7

    return scene_room(v, pose, 0.0, "over your wall: the face comes down your side of it", s=s, cx=cx, after=wall, dark=0.7)


def scene_f1(v):
    w, h = 360, PH
    sc = Scene(w, h)
    sc.bg[:] = room(w, h, h - 16, dark=0.6)
    yy, xx = np.mgrid[0:h, 0:w]
    sc.bg[yy < 50] = RAMPS["wood"][1] * 0.55
    sc.amb, sc.dif = 0.1, 0.45
    view = View(sc, 180, 52, U, yaw=40)
    pose = dict(v.poses()["special"])
    pose["root_shift"] = (0.0, 0.0, 0.0)
    joints = v.rig()
    mats, pts = posed(joints, pose)
    top = max(p[2] for n, p in pts.items())
    pose["root_shift"] = (0.0, 0.0, -top)
    ids, balls, pts, cards = creature(view, v, pose, card_light=0.7)
    finish(sc, ids, balls, pale_light=-0.15)
    return ("on the ceiling of the corridor, its face the right way up", sc.render(rim=0.12))


def scene_f2(v):
    w, h, ground = 360, PH, PH - 16
    sc = Scene(w, h)
    sc.bg[:] = room(w, h, ground, dark=0.62)
    sc.amb, sc.dif = 0.1, 0.45
    out = None
    for i, (dx, fade) in enumerate(((-0.9, 0.35), (-0.45, 0.6), (0.0, 1.0))):
        s2 = Scene(w, h)
        s2.bg[:] = sc.bg
        s2.amb, s2.dif = sc.amb, sc.dif
        view = View(s2, 200, ground, 60, yaw=70)
        pose = F.grounded(v, dict(v.poses()["move"], _moving=1))
        ids, balls, pts, cards = creature(view, v, pose, shift=(0.0, -dx, 0.0), card_light=0.75)
        finish(s2, ids, balls)
        img = s2.render(rim=0.1)
        m = s2.obj >= 0
        if out is None:
            out = sc.bg.copy()
        out[m] = out[m] * (1 - fade) + img[m] * fade
    rng = np.random.default_rng(2)
    for _ in range(5):
        y = int(rng.integers(0, h - 8))
        n = int(rng.integers(2, 7))
        out[y:y + n] = np.roll(out[y:y + n], int(rng.integers(-40, 40)), axis=1)
    return ("dropped frames: three of it at once, then none", out)


def scene_f3(v):
    w, h = 360, PH
    out = np.zeros((h, w, 3))
    for i, (lvl, lit) in enumerate(((0, 0.12), (7, 0.7))):
        sc = Scene(180, h)
        sc.bg[:] = room(180, h, h - 16, dark=0.85 - lit * 0.5)
        sc.amb, sc.dif = 0.02 + lit * 0.12, lit * 0.4
        view = View(sc, 90, h - 16, 64, yaw=30)
        pose = F.grounded(v, v.poses()["stand"])
        ids, balls, pts, cards = creature(view, v, pose, card_light=0.3 + lit)
        if "pale" in ids:
            D.skin(sc, ids["pale"], "pale", 4, offset=-0.45 + lit * 0.6, mottle=0.08)
        out[:, i * 180:(i + 1) * 180] = sc.render(rim=0.03)
    out[:, 180] = 21
    return ("light 0 and light 7: in the dark, only the faces", out)


SCENES = {"u1": scene_u1, "u2": scene_u2, "u3": scene_u3, "a1": scene_a1, "a2": scene_a2, "a3": scene_a3,
          "f1": scene_f1, "f2": scene_f2, "f3": scene_f3}


# ------------------------------------------------------------------------------------------------
# Sheets and boards
# ------------------------------------------------------------------------------------------------

def sheet(v):
    pose = stand_pose(v)
    row1 = []
    for label, yaw in (("front", 0), ("three-quarter", 38), ("side", 90)):
        row1.append((label, figure(v, pose, yaw=yaw)))
    row1.append(("silhouettes: the WATCHER, it; moving, attacking, its own", silhouettes(v)))
    row2 = [face_panel(v), detail_panel(v), scene_panel(v)]
    out = os.path.join(OUT, f"{v.key}_{v.name.lower().replace(' ', '_')}.png")
    lay([row1, row2], f"The Wake, round 3: {v.form.upper()} {v.key[-1]} - {v.name}", v.lines, out)
    return out


def board(form):
    vs = [x for x in F.VARIANTS if x.form == form]
    row1, row2, row3 = [], [], []
    for x in [WATCHER] + vs:
        row1.append((x.name if x is WATCHER else f"{x.key}: {x.name}", figure(x, stand_pose(x), yaw=20, w=210, s=64)))
        p = x.poses().get("move", x.poses()["stand"])
        row2.append(("moving", figure(x, F.grounded(x, p), yaw=75, w=210, s=64)))
        row3.append(("its face", closeup(x, head_focus(x), 300, w=210, h=260)))
    out = os.path.join(OUT, f"board_{form}.png")
    lay([row1, row2, row3], f"The Wake, round 3: {form.upper()}, three ways",
        [f"The WATCHER on the left for scale; the three {form} variants after it, the same size, the same light."], out)
    return out


def debug(keys, out):
    """A quick contact sheet of every pose, to check the rigs before drawing the sheets."""
    rows = []
    for k in keys:
        v = F.BY_KEY[k]
        ps = v.poses()
        row = []
        for label, name, yaw in (("stand front", "stand", 0), ("stand side", "stand", 90), ("move", "move", 60),
                                 ("attack", "attack", 60), ("special", "special", 25)):
            pose = ps[name] if (k == "f1" and name == "special") else F.grounded(v, ps[name])
            w, h = 150, 210
            s, cx, gr = fit(v, pose, yaw, (4, 4, w - 4, h - 8), 46, keep_ground=None if (k == "f1" and name == "special") else h - 8)
            row.append((f"{k} {label}", figure(v, pose, yaw=yaw, w=w, h=h, s=s, cx=cx, ground=gr)))
        rows.append(row)
    lay(rows, "debug", [], out)


def final_sheet(key, out):
    """The chosen form as it will be built: its clips, frame by frame, from the model's own rig."""
    import form_models as FM
    v = FM.FORMS[key]
    clips = v.clips()
    rows = []
    row = [("stand, front", figure(v, clips["stand"][2][0], yaw=0, w=200)), ("stand, side", figure(v, clips["stand"][2][0], yaw=90, w=200)),
           ("stand, 3/4", figure(v, clips["stand"][2][0], yaw=35, w=200))]
    row.append((f"face", closeup(v, head_focus(v, clips["stand"][2][0]), 420, pose=clips["stand"][2][0], w=200)))
    rows.append(row)
    for name in v.extra_clips():
        fps, loop, frames = clips[name]
        pick = sorted(set([0, len(frames) // 3, (2 * len(frames)) // 3, len(frames) - 1]))
        rows.append([(f"{name} {i}", figure(v, frames[i], yaw=60, w=160, h=260, s=56)) for i in pick])
    for name in ("run", "climb", "hold", "scream", "crawl", "stoop_walk"):
        fps, loop, frames = clips[name]
        pick = sorted(set([0, len(frames) // 2]))
        rows.append([(f"{name} {i}", figure(v, frames[i], yaw=60, w=160, h=260, s=56)) for i in pick])
    # pack the short rows two to a line
    packed = []
    cur = []
    for r in rows:
        if len(cur) + len(r) > 8:
            packed.append(cur)
            cur = []
        cur += r
    if cur:
        packed.append(cur)
    lay(packed, f"The Wake: {v.form.upper()} as built ({v.name})", ["Clips from tools/creature/form_models.py, posed with the model's rig."], out)


def main(argv):
    os.makedirs(OUT, exist_ok=True)
    if len(argv) > 1 and argv[1] == "final":
        final_sheet(argv[2], argv[3])
        return
    keys = argv[1:] or [v.key for v in F.VARIANTS] + ["boards"]
    if keys[0] == "debug":
        debug(keys[2:] or [v.key for v in F.VARIANTS], keys[1])
        return
    for k in keys:
        if k == "boards":
            for form in ("uneasy", "angry", "furious"):
                board(form)
        elif k.startswith("board_"):
            board(k[6:])
        else:
            sheet(F.BY_KEY[k])


if __name__ == "__main__":
    main(sys.argv)
