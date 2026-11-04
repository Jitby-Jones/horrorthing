#!/usr/bin/env python3
"""
Builds one of the creature's tempers in headless Blender, the way build_creature.py builds the
WATCHER, and exports it for the mod.

    python tools/creature/build_form.py uneasy|angry|furious [--quick] [--previews]

The forms and their clips are in tools/creature/form_models.py (chosen from the concept sheets in
docs/concepts/forms/). The body is the form's own skeleton under a skin modifier, sculpted and
painted like the WATCHER's: black lacquer, or for FURIOUS bare gunmetal armature; pale hands
wherever the skeleton has hands, the small ones whiter and wetter. The head is the WATCHER's
card, re-dressed for the temper (card.dress), moved to wherever this form keeps its head; for
FURIOUS it is shredded into strips over the real face. FURIOUS also has rigid parts: tape strips
on hinged joints, cassette hubs, wing nuts, and shards of the black hanging off its limbs.

Writes into common/src/main/resources/assets/thewake/:
    models/creature/creature_<form>.bin.gz
    textures/entity/creature_<form>_body.png, creature_<form>_head.png
    textures/entity/creature_furious_face.png, creature_furious_parts.png (FURIOUS)
"""
import argparse
import json
import math
import os
import re
import sys

import bpy  # noqa: I001
import bmesh
import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
sys.path.insert(0, os.path.join(os.path.dirname(HERE), "concepts"))
import build_creature as B  # noqa: E402
import card as card_mod  # noqa: E402
import export_v3  # noqa: E402
import form_models as FM  # noqa: E402
import forms as F  # noqa: E402
import poses as P  # noqa: E402

FORM_INDEX = {"uneasy": 1, "angry": 2, "furious": 3}


# ---------------------------------------------------------------------------------------------
# Painting the body: by bone, not by place, so hands are pale wherever the skeleton has hands
# ---------------------------------------------------------------------------------------------

def body_painter(form, joints):
    by = {j.name: j for j in joints}
    pale_names = sorted(j.name for j in joints if form.material(j.name) == "pale")
    small = sorted(n for n in pale_names if n.endswith("2"))
    metal = form.material("spine1") == "metal"
    knuckles = np.array([j.pos for j in joints if re.match(r"^f\dk[1-3]_", j.name)])
    tips = np.array([j.pos for j in joints if re.match(r"^f\dk4_", j.name) or j.name.startswith("thumb3")])
    wrists = np.array([j.pos for j in joints if j.name.startswith("wrist_")])

    def nearest_dist(p, pts):
        best = np.full(len(p), np.inf)
        for q in pts:
            best = np.minimum(best, np.linalg.norm(p - q, axis=1))
        return best

    def paint(p, n, ao, part):
        label, segments = B.nearest_segment(joints, p)
        child = np.array([b for _, b in segments])[label]
        parent = np.array([a for a, _ in segments])[label]
        big = B.Noise(21).fbm(p * 3.0, 4)
        mid = B.Noise(22).fbm(p * 14.0, 4)
        fine = B.Noise(23).fbm(p * 70.0, 3)
        ball = part == B.PART_BALL
        if metal:
            # gunmetal threaded rod: dark, a fine thread along every bone, rust in the threads
            a = np.array([by[x].pos for x in parent])
            b = np.array([by[x].pos for x in child])
            ab = b - a
            t = np.einsum("ij,ij->i", p - a, ab) / np.maximum(np.einsum("ij,ij->i", ab, ab), 1e-9)
            along = t * np.linalg.norm(ab, axis=1)
            thread = 0.5 + 0.5 * np.sin(along * 2.0 * math.pi / 0.006)
            base = B.srgb_to_linear((44, 46, 50))
            col = base * (0.7 + 0.5 * big[:, None]) * (0.75 + 0.35 * thread[:, None]) * (0.9 + 0.2 * fine[:, None])
            rust = B.smoothstep(0.58, 0.72, B.Noise(27).fbm(p * 9.0, 4)) * (0.4 + 0.6 * (1.0 - thread))
            col = B.mix(col, B.srgb_to_linear((92, 44, 22)) * (0.6 + 0.6 * mid[:, None]), rust * 0.75)
            spec = B.smoothstep(0.55, 0.95, n[:, 2]) * 0.35 * thread
            col = col + spec[:, None] * B.srgb_to_linear((120, 124, 132))
            col[ball] = B.srgb_to_linear((34, 34, 38)) * (0.8 + 0.4 * fine[ball, None])
        else:
            base = B.srgb_to_linear((17, 15, 16))
            col = base * (0.75 + 0.5 * big[:, None]) * (0.85 + 0.3 * mid[:, None]) * (0.9 + 0.2 * fine[:, None])
            gloss = B.smoothstep(0.45, 0.9, n[ball, 2]) * (0.6 + 0.4 * (n[ball, 1] < 0))
            col[ball] = B.mix(col[ball], B.srgb_to_linear((74, 66, 72)), gloss * 0.7)
        col = col * (0.55 + 0.45 * ao[:, None])
        pale = np.isin(child, pale_names) & ~ball
        if pale.any():
            hp = p[pale]
            whiter = np.isin(child[pale], small)
            skin = B.srgb_to_linear((206, 184, 172)) * (0.82 + 0.3 * B.Noise(24).fbm(hp * 40.0, 3))[:, None]
            skin = np.where(whiter[:, None], B.srgb_to_linear((226, 214, 206)) * (0.86 + 0.24 * B.Noise(28).fbm(hp * 50.0, 3))[:, None], skin)
            mottle = B.smoothstep(0.55, 0.75, B.Noise(25).fbm(hp * 18.0, 3))
            skin = B.mix(skin, B.srgb_to_linear((170, 128, 132)), mottle * np.where(whiter, 0.25, 0.45))
            veins = B.smoothstep(0.6, 0.66, B.Noise(26).fbm(hp * 160.0, 3))
            skin = B.mix(skin, B.srgb_to_linear((120, 128, 150)), veins * 0.35)
            crease = np.exp(-(nearest_dist(hp, knuckles) / 0.004) ** 2) if len(knuckles) else np.zeros(len(hp))
            skin = B.mix(skin, B.srgb_to_linear((96, 70, 66)), np.clip(crease, 0, 1) * 0.5)
            nail = nearest_dist(hp, tips) < 0.016 if len(tips) else np.zeros(len(hp), bool)
            skin[nail] = B.mix(skin[nail], B.srgb_to_linear((70, 56, 44)), 0.8)
            # wet: the small ones catch the light
            wet = whiter * B.smoothstep(0.5, 0.9, n[pale, 2]) * 0.5
            skin = skin + wet[:, None] * B.srgb_to_linear((200, 196, 190))
            skin *= (0.6 + 0.4 * ao[pale])[:, None]
            col[pale] = skin
        # the seam where each pale hand is sewn on
        if len(wrists):
            seam = (nearest_dist(p, wrists) < 0.024) & np.isin(child, pale_names)
            if seam.any():
                stitch = np.sin(np.arctan2(p[seam, 1], p[seam, 0]) * 40.0) > 0.5
                col[seam] = np.where(stitch[:, None], B.srgb_to_linear((44, 30, 24)), col[seam])
        return col

    return paint


# ---------------------------------------------------------------------------------------------
# Rigid parts with their own UVs (FURIOUS): tape, hubs, nuts, shards; the real face
# ---------------------------------------------------------------------------------------------

def new_mesh_object(name, verts, faces, uvs):
    """A mesh from vertices, faces and one UV per face corner."""
    mesh = bpy.data.meshes.new(name)
    mesh.from_pydata([tuple(v) for v in verts], [], [tuple(f) for f in faces])
    layer = mesh.uv_layers.new()
    i = 0
    for poly in mesh.polygons:
        for li in poly.loop_indices:
            layer.data[li].uv = tuple(uvs[i])
            i += 1
    obj = B.link(bpy.data.objects.new(name, mesh))
    for poly in obj.data.polygons:
        poly.use_smooth = True
    return obj


def region(u0, v0, u1, v1):
    """An atlas region in image coordinates (top-down) as a function to Blender UVs."""
    def f(a, b):
        return (u0 + (u1 - u0) * a, 1.0 - (v0 + (v1 - v0) * b))
    return f


TAPE_UV = region(0.0, 0.0, 0.5, 0.25)
HUB_UV = region(0.0, 0.25, 0.25, 0.5)
NUT_UV = region(0.25, 0.25, 0.5, 0.5)
SHARD_UV = region(0.0, 0.5, 1.0, 1.0)
BALL_UV = region(0.5, 0.0, 0.75, 0.5)
ROD_UV = region(0.75, 0.0, 1.0, 0.5)


def strip(name, a, b, width, twist_axis, uvf, k=0, n=1):
    """One hinged strip of tape from a to b (rest positions), a little twisted."""
    d = b - a
    side = np.cross(d, twist_axis)
    side = side / (np.linalg.norm(side) + 1e-9) * width * 0.5
    verts = [a - side, a + side, b + side, b - side]
    faces = [(0, 1, 2, 3), (3, 2, 1, 0)]
    v0, v1 = k / n, (k + 1) / n
    uvs = [uvf(0, v0), uvf(1, v0), uvf(1, v1), uvf(0, v1), uvf(0, v1), uvf(1, v1), uvf(1, v0), uvf(0, v0)]
    return new_mesh_object(name, verts, faces, uvs)


def box(name, center, size, uvf, rot=None):
    c = np.asarray(center, float)
    hx, hy, hz = np.asarray(size, float) / 2
    R = np.eye(3) if rot is None else rot
    corners = [np.array([sx * hx, sy * hy, sz * hz]) for sx in (-1, 1) for sy in (-1, 1) for sz in (-1, 1)]
    verts = [c + R @ q for q in corners]
    faces = [(0, 1, 3, 2), (4, 6, 7, 5), (0, 4, 5, 1), (2, 3, 7, 6), (0, 2, 6, 4), (1, 5, 7, 3)]
    uvs = []
    for _ in faces:
        uvs += [uvf(0, 0), uvf(1, 0), uvf(1, 1), uvf(0, 1)]
    return new_mesh_object(name, verts, faces, uvs)


def disc(name, center, normal, radius, depth, uvf, sides=10):
    c = np.asarray(center, float)
    nrm = np.asarray(normal, float) / np.linalg.norm(normal)
    a = np.cross(nrm, [0.0, 0.0, 1.0] if abs(nrm[2]) < 0.9 else [1.0, 0.0, 0.0])
    a /= np.linalg.norm(a)
    b = np.cross(nrm, a)
    verts, faces, uvs = [], [], []
    for side in (0, 1):
        off = nrm * depth * (0.5 if side else -0.5)
        for i in range(sides):
            t = 2 * math.pi * i / sides
            verts.append(c + off + (a * math.cos(t) + b * math.sin(t)) * radius)
    top = list(range(sides, 2 * sides))
    bot = list(range(sides))[::-1]
    for ring, fc in ((top, None), (bot, None)):
        faces.append(tuple(ring))
        uvs += [uvf(0.5 + 0.5 * math.cos(2 * math.pi * k / sides), 0.5 + 0.5 * math.sin(2 * math.pi * k / sides)) for k in range(sides)]
    for i in range(sides):
        j = (i + 1) % sides
        faces.append((i, j, sides + j, sides + i))
        uvs += [uvf(0, 0), uvf(1, 0), uvf(1, 1), uvf(0, 1)]
    return new_mesh_object(name, verts, faces, uvs)


def shard(name, a, b, radius, angle, uvf, seed):
    """A curved plate of the black lacquer, split and hanging off the bone from a to b."""
    rng = np.random.default_rng(seed)
    d = b - a
    L = np.linalg.norm(d)
    d = d / L
    u = np.cross(d, [0.0, 1.0, 0.0])
    if np.linalg.norm(u) < 1e-3:
        u = np.cross(d, [1.0, 0.0, 0.0])
    u /= np.linalg.norm(u)
    w = np.cross(d, u)
    verts, faces, uvs = [], [], []
    rows, cols = 4, 4
    for i in range(rows):
        for k in range(cols):
            t = 0.15 + 0.6 * i / (rows - 1)
            ang = angle + (k / (cols - 1) - 0.5) * 1.6
            peel = 0.012 * i * (1 + rng.random())
            r = radius * 1.18 + peel
            verts.append(a + d * t * L + (u * math.cos(ang) + w * math.sin(ang)) * r)
    for i in range(rows - 1):
        for k in range(cols - 1):
            q = (i * cols + k, i * cols + k + 1, (i + 1) * cols + k + 1, (i + 1) * cols + k)
            faces.append(q)
            uvs += [uvf(k / cols, i / rows), uvf((k + 1) / cols, i / rows), uvf((k + 1) / cols, (i + 1) / rows), uvf(k / cols, (i + 1) / rows)]
            faces.append(q[::-1])
            uvs += [uvf(k / cols, (i + 1) / rows), uvf((k + 1) / cols, (i + 1) / rows), uvf((k + 1) / cols, i / rows), uvf(k / cols, i / rows)]
    return new_mesh_object(name, verts, faces, uvs)


def paint_parts(size=256):
    """The atlas for FURIOUS's rigid parts: glossy tape, a cassette hub, a wing nut, the lacquer."""
    rng = np.random.default_rng(31)
    img = np.zeros((size, size, 4), np.float32)
    yy, xx = np.mgrid[0:size, 0:size] / size
    # tape: brown-black, a hard sheen down the middle, crinkles
    tape = (yy < 0.25)
    across = (xx % 0.5) / 0.5
    sheen = np.exp(-((across - 0.42) / 0.12) ** 2)
    crinkle = 0.5 + 0.5 * np.sin(yy * 220 + 6 * np.sin(xx * 40))
    c = np.stack([30 + 70 * sheen * crinkle, 22 + 52 * sheen * crinkle, 16 + 40 * sheen * crinkle], -1)
    img[tape, :3] = c[tape]
    # hub: white plastic, six teeth, a dark centre
    hub = (yy >= 0.25) & (yy < 0.5) & (xx < 0.25)
    cx, cy = (xx - 0.125) / 0.125, (yy - 0.375) / 0.125
    r = np.sqrt(cx ** 2 + cy ** 2)
    teeth = (np.cos(np.arctan2(cy, cx) * 6) > 0.4) & (r < 0.45) & (r > 0.3)
    hc = np.where(r < 0.3, 40, np.where(teeth, 60, 205))[..., None] * np.array([1.0, 0.98, 0.94])
    img[hub, :3] = hc[hub]
    # wing nut: dark galvanised metal
    nut = (yy >= 0.25) & (yy < 0.5) & (xx >= 0.25) & (xx < 0.5)
    img[nut, :3] = (np.array([70.0, 72.0, 78.0]) * (0.8 + 0.4 * rng.random((size, size, 1))))[nut]
    # lacquered vertebra: black, a dull highlight near the top (v small = top), like the body's balls
    ball = (yy < 0.5) & (xx >= 0.5) & (xx < 0.75)
    bv = yy / 0.5
    hl = np.exp(-((bv - 0.22) / 0.1) ** 2)
    bc = np.stack([20 + 70 * hl, 18 + 62 * hl, 20 + 68 * hl], -1)
    img[ball, :3] = bc[ball]
    # lacquered rod: black with a sheen down one side
    rod = (yy < 0.5) & (xx >= 0.75)
    ru = (xx - 0.75) / 0.25
    sh = np.exp(-((ru - 0.3) / 0.08) ** 2)
    rc = np.stack([22 + 50 * sh, 20 + 45 * sh, 22 + 50 * sh], -1)
    img[rod, :3] = rc[rod]
    # lacquer: black, glossy, cracked
    lac = yy >= 0.5
    n = rng.random((size, size))
    lc = np.stack([18 + 10 * n, 16 + 9 * n, 18 + 10 * n], -1)
    crack = np.abs(np.sin(xx * 60 + 4 * np.sin(yy * 30))) < 0.04
    lc[crack] = (6, 5, 6)
    img[lac, :3] = lc[lac]
    img[..., 3] = 255
    return img


# ---------------------------------------------------------------------------------------------
# Heads
# ---------------------------------------------------------------------------------------------

def strip_masks(n=7):
    G = B.CARD_GEO
    H, W = G["card"].shape
    xs = np.arange(W)[None, :] / W
    upper = G["card"] & ~G["holes"] & ~G["jaw"]
    gap = 0.016
    return [upper & (xs >= i / n + gap) & (xs < (i + 1) / n - gap) for i in range(n)]


def strip_card_sheet(orig):
    """FURIOUS: the upper card built as separate strips (with gaps between), so each can be turned
    on its own at export without stretching into the next."""
    def sheet(name, mask, step=4, thickness=0.011):
        if name != "card":
            return orig(name, mask, step, thickness)
        objs = [orig(f"card_s{i}", m & mask, step, thickness) for i, m in enumerate(strip_masks())]
        return B.join(objs, "card")
    return sheet


def lacquer_neck(form, joints):
    """ANGRY: the long neck as rigid lacquered segments and balls, one per vertebra, so the folds
    stay sharp (a skin would pinch)."""
    by = {j.name: j for j in joints}
    chain = ["neck0"] + FM.NECK_JOINTS
    out = []
    for a_name, b_name in zip(chain[:-1], chain[1:]):
        a, b = by[a_name].pos, by[b_name].pos
        bm = bmesh.new()
        B.tube(bm, [a, b], 0.019, 0.017, 8)
        seg = B.to_object(bm, f"seg_{b_name}")
        ball = B.sphere_part(f"ball_{b_name}", b, 0.036, segments=(14, 10))
        around_uv(seg, a, b, ROD_UV)
        sphere_uv(ball, b, BALL_UV)
        out.append((f"seg_{b_name}", seg, f"creature_{form.key}_parts", a_name, False))
        out.append((f"ball_{b_name}", ball, f"creature_{form.key}_parts", b_name, False))
    return out


def sphere_uv(obj, center, uvf):
    """Latitude down the region (the highlight sits near the top of the ball)."""
    mesh = obj.data
    layer = mesh.uv_layers.new() if not mesh.uv_layers else mesh.uv_layers.active
    co = B.get_co(mesh) - center
    r = np.linalg.norm(co, axis=1) + 1e-9
    lat = np.arccos(np.clip(co[:, 2] / r, -1, 1)) / math.pi
    lon = (np.arctan2(co[:, 1], co[:, 0]) / (2 * math.pi)) % 1.0
    for poly in mesh.polygons:
        for li in poly.loop_indices:
            vi = mesh.loops[li].vertex_index
            layer.data[li].uv = uvf(0.05 + 0.9 * lon[vi], 0.02 + 0.96 * lat[vi])


def around_uv(obj, a, b, uvf):
    """Round the rod across the region, along it down the region."""
    mesh = obj.data
    layer = mesh.uv_layers.new() if not mesh.uv_layers else mesh.uv_layers.active
    co = B.get_co(mesh)
    d = (b - a) / (np.linalg.norm(b - a) + 1e-9)
    side = np.cross(d, [0.0, 1.0, 0.0])
    if np.linalg.norm(side) < 1e-3:
        side = np.cross(d, [1.0, 0.0, 0.0])
    side /= np.linalg.norm(side)
    up = np.cross(side, d)
    rel = co - a
    ang = (np.arctan2(rel @ up, rel @ side) / (2 * math.pi)) % 1.0
    t = np.clip(rel @ d / (np.linalg.norm(b - a) + 1e-9), 0, 1)
    for poly in mesh.polygons:
        for li in poly.loop_indices:
            vi = mesh.loops[li].vertex_index
            layer.data[li].uv = uvf(0.05 + 0.9 * ang[vi], 0.05 + 0.9 * t[vi])


def planar_uv(obj, uvf):
    """Any UVs inside a region (the lacquer is the same all over)."""
    mesh = obj.data
    layer = mesh.uv_layers.new() if not mesh.uv_layers else mesh.uv_layers.active
    co = B.get_co(mesh)
    lo, hi = co.min(axis=0), co.max(axis=0)
    span = np.maximum(hi - lo, 1e-6)
    for poly in mesh.polygons:
        for li in poly.loop_indices:
            vi = mesh.loops[li].vertex_index
            q = (co[vi] - lo) / span
            layer.data[li].uv = uvf(0.1 + 0.8 * q[0], 0.1 + 0.8 * (0.5 * q[1] + 0.5 * q[2]))


def real_face_object(seed=3):
    """The face that was behind the card, as a sheet with its own texture, split at the mouth so
    the chin rides the jaw."""
    import real_face as RF
    import face_art as FA
    img, alpha = RF.real_face(seed=seed, jaw=0.0)
    th = FA.H + FA.EXTRA
    from PIL import Image
    rgba = np.zeros((th, FA.W, 4), np.uint8)
    rgba[..., :3] = np.clip(img, 0, 255).astype(np.uint8)
    rgba[..., 3] = np.where(alpha, 255, 0)
    Image.fromarray(rgba, "RGBA").resize((FA.W * 2, th * 2), Image.NEAREST).save(os.path.join(B.TEX_DIR, "creature_furious_face.png"))
    G = B.CARD_GEO
    Hc, Wc = G["card"].shape
    step = 4
    up_verts, up_faces, up_uvs = [], [], []
    lo_verts, lo_faces, lo_uvs = [], [], []
    mouth_v = FA.MOUTH_Y / FA.H

    def point(u, v):
        relief = B.relief_at(u * Wc, v * Hc) * 1.6
        return B.card_point(u, v, relief) + np.array([0.0, 0.004, 0.0])

    for py in range(0, Hc - step, step):
        for px in range(0, Wc - step, step):
            u0, u1 = px / Wc, (px + step) / Wc
            v0, v1 = py / Hc, (py + step) / Hc
            ax = int(min(FA.W - 1, (u0 + u1) / 2 * FA.W))
            ay = int(min(FA.H - 1, (v0 + v1) / 2 * FA.H))
            if not alpha[ay, ax]:
                continue
            lower = (v0 + v1) / 2 >= mouth_v and abs((u0 + u1) / 2 - 0.5) < 0.17
            verts, faces, uvs = (lo_verts, lo_faces, lo_uvs) if lower else (up_verts, up_faces, up_uvs)
            base = len(verts)
            for (uu, vv) in ((u0, v0), (u1, v0), (u1, v1), (u0, v1)):
                verts.append(point(uu, vv))
                uvs.append((uu, 1.0 - vv * FA.H / th))
            faces.append((base, base + 3, base + 2, base + 1))
    upper = new_mesh_object("realface", up_verts, up_faces, [up_uvs[i] for f in up_faces for i in f])
    lower = new_mesh_object("realchin", lo_verts, lo_faces, [lo_uvs[i] for f in lo_faces for i in f])
    return upper, lower


# ---------------------------------------------------------------------------------------------
# The build
# ---------------------------------------------------------------------------------------------

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("form", choices=list(FM.FORMS))
    ap.add_argument("--quick", action="store_true")
    ap.add_argument("--previews", action="store_true")
    ap.add_argument("--previews-only", default="", help="comma-separated clip:frame list to render from the export")
    args = ap.parse_args(sys.argv[1:])
    name = args.form
    form = FM.FORMS[name]
    if args.previews_only:
        B.render_previews(args.previews_only.split(","), model_path=os.path.join(B.MODEL_DIR, f"creature_{name}.bin.gz"), prefix=name + "_")
        return
    bpy.ops.wm.read_factory_settings(use_empty=True)
    joints = form.skeleton()
    furious = name == "furious"
    angry = name == "angry"
    clips = form.clips()
    if furious:
        B.card_sheet = strip_card_sheet(B.card_sheet)
    print("sculpting", name)
    body_joints = [j for j in joints if form.material(j.name) is not None or j.parent is None]
    body = B.build_body(body_joints)
    balls = B.ball_joints(body_joints)
    head_all, head_parts = B.build_head()
    for obj, mat_name in ((body, "body_skin"), (balls, "balls")):
        mat = bpy.data.materials.new(mat_name)
        mat.use_nodes = True
        obj.data.materials.append(mat)
    body = B.join([body, balls], "body")

    print("texturing")
    tex = 512 if args.quick else 1024
    B.unwrap([body], 0.003)
    B.unwrap([head_all], 0.004)
    B.make_texture(f"creature_{name}_body", body, {"body_skin": B.PART_SKIN, "balls": B.PART_BALL}, tex, args.quick,
                   body_painter(form, body_joints), 0.08)
    calm = B.CARD_FRONT
    B.CARD_FRONT = card_mod.dress(B.CARD_TEX, FORM_INDEX[name])
    B.make_texture(f"creature_{name}_head", head_all, {n: p for n, p, _ in head_parts}, tex, args.quick, B.paint_head, 0.03)
    B.CARD_FRONT = calm

    print("decimating")
    body = B.decimate(body, 0.26)
    B.set_part_attribute(body, B.PART_SKIN)
    pieces = B.separate_by_material(head_all)
    groups_of = {}
    for mat_name, part, group in head_parts:
        obj = pieces[mat_name]
        if furious and mat_name == "jaw_card":
            bpy.data.objects.remove(obj)
            continue
        if part == B.PART_SKIN:
            obj = B.decimate(obj, 0.3 if group == "head" else 0.4)
        elif part == B.PART_MOUTH:
            obj = B.decimate(obj, 0.5)
        B.set_part_attribute(obj, part)
        if furious and mat_name == "card":
            fan_strips(form, obj)
        groups_of.setdefault(group, []).append(obj)
    rigid = {g: B.join(objs, g) for g, objs in groups_of.items()}

    # This form keeps its head somewhere else: move it there (textures are already baked).
    offset = form.head_center() - P.HEAD_CENTER
    for obj in rigid.values():
        co = B.get_co(obj.data)
        B.set_co(obj.data, co + offset)

    extras = []  # (group name, object, texture, joint, thin)
    if furious:
        face_up, face_lo = real_face_object()
        for o in (face_up, face_lo):
            B.set_part_attribute(o, B.PART_SKIN)
            B.set_co(o.data, B.get_co(o.data) + offset)
        extras.append(("realface", face_up, "creature_furious_face", "neck2", False))
        extras.append(("realchin", face_lo, "creature_furious_face", "jaw", False))
        extras += furious_parts(form)
    if angry:
        extras += lacquer_neck(form, form.rig())
    if furious or angry:
        from PIL import Image
        Image.fromarray(paint_parts().astype(np.uint8), "RGBA").save(os.path.join(B.TEX_DIR, f"creature_{name}_parts.png"))

    print("rigging")
    rig = B.build_rig(body_joints, body)
    ejoints = form.rig()
    names = [j.name for j in ejoints]
    index = {n: i for i, n in enumerate(names)}
    parent_of = {j.name: j.parent for j in ejoints if j.parent}
    rides = {"head": "neck2", "jaw": "jaw", "eyeL": "eye_L", "eyeR": "eye_R", "lidL": "lid_L", "lidR": "lid_R"}
    groups = []
    for gname, obj in [("body", body)] + [(k, rigid[k]) for k in ("head", "jaw", "eyeL", "eyeR", "lidL", "lidR") if k in rigid]:
        vidx, uv, part, tris = B.mesh_render_data(obj)
        texture = f"thewake:textures/entity/creature_{name}_" + ("body" if gname == "body" else "head") + ".png"
        rest = B.to_mc(B.get_co(obj.data)[vidx])
        g = {"name": gname, "texture": texture, "uv": uv, "part": part, "tris": tris, "rigid": gname != "body",
             "rest": rest, "rest_normals": B.smooth_normals_by_position(rest, tris)}
        if gname == "body":
            g["joints"], g["weights"] = export_v3.deform_weights(body, names, parent_of, vidx)
            g["vidx"] = vidx
        else:
            g["joint"] = index[rides[gname]]
        groups.append(g)
    for gname, obj, texture, joint, thin in extras:
        vidx, uv, part, tris = B.mesh_render_data(obj)
        rest = B.to_mc(B.get_co(obj.data)[vidx])
        if thin:
            part = np.full(len(vidx), B.PART_NEEDLE, dtype=np.int32)
        groups.append({"name": gname, "texture": f"thewake:textures/entity/{texture}.png", "uv": uv, "part": part, "tris": tris,
                       "rigid": True, "rest": rest, "rest_normals": B.smooth_normals_by_position(rest, tris), "joint": index[joint]})

    def point(pname, joint, p):
        return (pname, index[joint], 0, B.to_mc(p)[0])

    def direction(pname, joint, d):
        return (pname, index[joint], 1, B.to_mc(np.asarray(d, float))[0])

    hc = form.head_center()
    attachments = [point("head", "neck2", hc), direction("headRight", "neck2", (1.0, 0.0, 0.0)),
                   direction("headUp", "neck2", (0.0, 0.0, 1.0)), direction("headForward", "neck2", (0.0, -1.0, 0.0)),
                   point("eyeLeft", "eye_L", hc + P.EYES_LOCAL[0] + P.EYE_BACK),
                   point("eyeRight", "eye_R", hc + P.EYES_LOCAL[1] + P.EYE_BACK),
                   point("jawHinge", "neck2", hc + P.JAW_HINGE_LOCAL), point("mouth", "jaw", hc + P.MOUTH_LOCAL)]

    body_g = groups[0]
    stand = clips["stand"][2][0]
    B.apply_pose(rig, body_joints, stand)
    depsgraph = bpy.context.evaluated_depsgraph_get()
    ev = body.evaluated_get(depsgraph)
    m = ev.to_mesh()
    blender_co = B.to_mc(B.get_co(m)[body_g["vidx"]])
    ev.to_mesh_clear()
    fr = export_v3.frame_transforms(P.joint_transforms(ejoints, stand), names)
    ours, _ = export_v3.dqs(body_g["rest"], body_g["rest_normals"], body_g["joints"], body_g["weights"], fr)
    err = np.linalg.norm(ours - blender_co, axis=1)
    print(f"  skinning check (stand): mean {err.mean() * 1000:.2f} mm, 99% {np.percentile(err, 99) * 1000:.2f} mm")

    thin = {B.PART_NEEDLE}
    clips_out = []
    summary = {}
    for cname, (fps, loop, frames) in clips.items():
        frs, heights = [], []
        for pose in frames:
            f = export_v3.frame_transforms(P.joint_transforms(ejoints, pose), names)
            frs.append(f)
            top = 0.0
            for g in groups:
                if g["rigid"]:
                    R = export_v3.quat_to_matrix(f[g["joint"], :4])
                    pos = g["rest"] @ R.T + f[g["joint"], 4:]
                else:
                    pos, _ = export_v3.dqs(g["rest"], g["rest_normals"], g["joints"], g["weights"], f)
                solid = pos[~np.isin(g["part"], list(thin))]
                if len(solid):
                    top = max(top, float(solid[:, 1].max()))
            heights.append(top)
        clips_out.append({"name": cname, "fps": fps, "loop": loop, "frames": np.array(frs), "heights": heights})
        summary[cname] = {"frames": len(frs), "fps": fps, "loop": loop, "height": round(max(heights), 3)}
        print(f"  clip {cname}: {len(frs)} frame(s), tallest {max(heights):.2f}")
    joint_list = [(j.name, index[j.parent] if j.parent else -1, B.to_mc(j.pos)[0]) for j in ejoints]
    os.makedirs(B.MODEL_DIR, exist_ok=True)
    path = os.path.join(B.MODEL_DIR, f"creature_{name}.bin.gz")
    size = export_v3.write_bin(path, joint_list, attachments, groups, clips_out)
    with open(os.path.join(B.MODEL_DIR, f"clips_{name}.json"), "w") as fh:
        json.dump({"version": 3, "form": name, "joints": len(names), "clips": summary}, fh, indent=1)
    tri_total = sum(g["tris"].shape[0] for g in groups)
    vert_total = sum(len(g["uv"]) for g in groups)
    print(f"exported {path}: {len(clips_out)} clips, {len(names)} joints, {vert_total} vertices, {tri_total} triangles, "
          f"{size / 1e6:.2f} MB raw")
    if args.previews:
        B.render_previews(["stand", "walk:2", "run:2", "lunge:9", "hold"] + [c + ":7" for c in form.extra_clips() if c not in ("walk", "run", "lunge")],
                          model_path=path, prefix=name + "_")


def fan_strips(form, obj):
    """FURIOUS: each strip of the card turned the way its piece is in form_models (shredder
    output, fanned and curled off the face), by which strip a vertex is in."""
    card = form.cards()[0]
    co = B.get_co(obj.data)
    local = co - P.HEAD_CENTER
    u = local[:, 0] / (2.0 * P.CARD[0]) + 0.5
    n = len(card.pieces)
    out = co.copy()
    for i, pc in enumerate(card.pieces):
        m = (u >= i / n) & (u < (i + 1) / n)
        if not m.any():
            continue
        pts = np.c_[local[m], np.ones(m.sum())] @ pc.T.T
        out[m] = pts[:, :3] + P.HEAD_CENTER
    B.set_co(obj.data, out)


def furious_parts(form):
    """Rigid parts riding joints: tape strips, hubs, wing nuts, the shards of the black."""
    out = []
    joints = form.rig()
    by = {j.name: j for j in joints}
    for r, (joint, at, n) in enumerate(FM.TAPES):
        out.append((f"hub{r}", disc(f"hub{r}", at, (0.0, 1.0, 0.0) if r < 2 else (1.0 if r == 2 else -1.0, 0.0, 0.0),
                                    0.028, 0.012, HUB_UV), "creature_furious_parts", joint, False))
        names = [f"tape{r}k{k}" for k in range(n)] + [f"tape{r}end"]
        for k in range(n):
            a, b = by[names[k]].pos, by[names[k + 1]].pos
            tw = np.array([math.cos(k * 1.1 + r), math.sin(k * 1.1 + r), 0.0])
            out.append((f"tape{r}_{k}", strip(f"tape{r}_{k}", a, b, 0.022, tw, TAPE_UV, k, n), "creature_furious_parts", names[k], True))
    for k in range(FM.MOUTH_TAPE):
        a, b = by[f"mtape{k}"].pos, by[f"mtape{k + 1}"].pos
        out.append((f"mtape_{k}", strip(f"mtape_{k}", a, b, 0.02, np.array([1.0, 0.0, 0.0]), TAPE_UV, k, FM.MOUTH_TAPE),
                    "creature_furious_parts", f"mtape{k}", True))
    # a wing nut on each big joint
    for jn in ("shoulder_L", "shoulder_R", "elbow_L", "elbow_R", "hip_L", "hip_R", "knee_L", "knee_R", "neck0", "neck1b"):
        j = by[jn]
        c = j.pos + np.array([0.0, 0.0, 0.0])
        side = np.array([1.0 if j.pos[0] >= 0 else -1.0, 0.0, 0.0])
        out.append((f"nut_{jn}", box(f"nut_{jn}", c + side * (j.radius[0] * 1.3 + 0.006), (0.012, 0.05, 0.016), NUT_UV),
                    "creature_furious_parts", jn, False))
    # shards of the black, split and hanging off the limbs like sleeves
    for k, (pa, pb, ang) in enumerate((("shoulder_L", "elbow_L", 0.6), ("shoulder_R", "elbow_R", 2.4), ("elbow_L", "wrist_L", 1.2),
                                       ("hip_L", "knee_L", 2.0), ("hip_R", "knee_R", 0.9), ("knee_R", "ankle_R", 3.2),
                                       ("spine2", "chest", 4.0), ("chest", "upchest", 1.5))):
        a, b = by[pa].pos, by[pb].pos
        out.append((f"shard{k}", shard(f"shard{k}", a, b, max(by[pa].radius[0], 0.02), ang, SHARD_UV, 70 + k),
                    "creature_furious_parts", pa, False))
    return out


if __name__ == "__main__":
    main()
