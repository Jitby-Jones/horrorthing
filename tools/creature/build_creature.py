#!/usr/bin/env python3
"""
Builds The Wake's creature in headless Blender and exports it for the mod.

    pip install bpy==5.2.3 numpy pillow      # Blender as a Python module (Python 3.13)
    python tools/creature/build_creature.py [--quick] [--previews] [--obj]
    python tools/creature/build_creature.py --previews-only --poses stand,lunge

The design (see docs/DESIGN.md, pass 10) takes its visual language from Doctor Nowhere's analogue
horror: a flat black body too thin to be alive, the waist nothing but a bare jointed spine, limbs
like sticks; and for a head, a pale bent card with a real human face pressed into it, pins driven
through it, wet strings hanging from its edge, cut straight across at the mouth so that the lower
part of the card can drop open.

Pipeline: a skin-modifier skeleton for the body, sculpted in numpy; the head built from two dense
rounded slabs (the card and its jaw) with the face sculpted into the front, plus eyes, teeth, pins
and strings as tubes; position/normal/AO/part maps baked with Cycles and painted in numpy;
decimated; rigged with bone-heat weights and posed with dual-quaternion skinning.

Writes into common/src/main/resources/assets/thewake/:
    models/creature/creature.bin.gz    every pose, groups body/head/jaw (format in write_bin)
    models/creature/poses.json         per pose: head/jaw pivots and axes, eyes, height (readable copy)
    textures/entity/creature_body.png
    textures/entity/creature_head.png
--obj also writes tools/creature/export/<pose>.obj for editing elsewhere.
--previews renders every pose with Cycles into tools/creature/previews/.

Everything is seeded: re-running produces the same creature.
Coordinates: Blender space is Z up, the creature faces -Y, units are blocks. The mod's model space
is Minecraft's: Y up, facing +Z, feet at the origin: mc = (x, z, -y).
"""
import argparse
import gzip
import json
import math
import os
import struct
import sys

import bpy  # noqa: I001  (bpy must be imported before bmesh and mathutils)
import bmesh
import numpy as np
from mathutils import Matrix

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import export_v3  # noqa: E402
import poses  # noqa: E402
from poses import POSES, about, joint_transforms, rot  # noqa: E402,F401

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
ASSETS = os.path.join(ROOT, "common", "src", "main", "resources", "assets", "thewake")
MODEL_DIR = os.path.join(ASSETS, "models", "creature")
TEX_DIR = os.path.join(ASSETS, "textures", "entity")
PREVIEW_DIR = os.path.join(ROOT, "tools", "creature", "previews")
OBJ_DIR = os.path.join(ROOT, "tools", "creature", "export")

# The head is an index card cut to a face too long, a real face pressed into it from behind,
# eyes behind holes torn in it, cut like a ventriloquist's dummy from the corners of the mouth down
# (tools/creature/card.py; the reference is docs/concepts/final_*.png). Head-local space: origin at
# HEAD_CENTER, x to the creature's left, y back, z up; the face is on the -y side.
from poses import CARD, EYE_BACK, EYE_RADIUS, EYES_LOCAL, HEAD_CENTER, JAW_HINGE_LOCAL, MOUTH_LOCAL, Joint, skeleton  # noqa: E402
import card as card_mod  # noqa: E402

PART_SKIN, PART_TEETH, PART_EYE, PART_MOUTH, PART_NEEDLE, PART_LID, PART_SOCKET, PART_BALL = 0, 1, 2, 3, 4, 5, 6, 7
DEPTH = 0.022  # how far the face behind pushes the card out, at most


def rng(seed):
    return np.random.default_rng(seed)


def smoothstep(e0, e1, x):
    t = np.clip((x - e0) / (e1 - e0), 0.0, 1.0)
    return t * t * (3.0 - 2.0 * t)


def gauss(x, sigma):
    return np.exp(-(x * x) / (2.0 * sigma * sigma))


# ---------------------------------------------------------------------------------------------
# Skeleton. Some joints only shape the skin (no pose ever rotates them): they make the limbs
# thin between knobbed joints, and the waist nothing but spine.
# ---------------------------------------------------------------------------------------------

def link(obj):
    bpy.context.scene.collection.objects.link(obj)
    return obj


def select_only(objs):
    for o in bpy.context.scene.objects:
        o.select_set(False)
    for o in objs:
        o.select_set(True)
    bpy.context.view_layer.objects.active = objs[0]


def evaluated_copy(obj, name):
    """Applies every modifier, returning a new plain mesh object in place of `obj`."""
    depsgraph = bpy.context.evaluated_depsgraph_get()
    mesh = bpy.data.meshes.new_from_object(obj.evaluated_get(depsgraph))
    bpy.data.objects.remove(obj)
    out = link(bpy.data.objects.new(name, mesh))
    return out


def get_co(mesh):
    co = np.zeros(len(mesh.vertices) * 3)
    mesh.vertices.foreach_get("co", co)
    return co.reshape(-1, 3)


def set_co(mesh, co):
    mesh.vertices.foreach_set("co", np.ascontiguousarray(co, dtype=np.float32).reshape(-1))
    mesh.update()


def get_normals(mesh):
    nor = np.zeros(len(mesh.vertices) * 3)
    mesh.vertices.foreach_get("normal", nor)
    return nor.reshape(-1, 3)


# ---------------------------------------------------------------------------------------------
# Body
# ---------------------------------------------------------------------------------------------

def build_body(joints):
    index = {j.name: i for i, j in enumerate(joints)}
    verts = [tuple(j.pos) for j in joints]
    edges = [(index[j.parent], index[j.name]) for j in joints if j.parent]
    mesh = bpy.data.meshes.new("body_skeleton")
    mesh.from_pydata(verts, edges, [])
    obj = link(bpy.data.objects.new("body", mesh))
    skin = obj.modifiers.new("Skin", "SKIN")
    skin.branch_smoothing = 0.7
    skin.use_smooth_shade = True
    for i, j in enumerate(joints):
        sv = mesh.skin_vertices[0].data[i]
        sv.radius = j.radius
        sv.use_root = j.name == "pelvis"
    sub = obj.modifiers.new("Subdivision", "SUBSURF")
    sub.levels = 2
    sub.render_levels = 2
    body = evaluated_copy(obj, "body")
    for poly in body.data.polygons:
        poly.use_smooth = True
    sculpt_body(body, joints)
    return body


def nearest_segment(joints, co):
    """Index into `segments` of the bone each vertex is closest to (surface distance)."""
    by_name = {j.name: j for j in joints}
    segments = [(j.parent, j.name) for j in joints if j.parent]
    best = np.full(len(co), np.inf)
    label = np.zeros(len(co), dtype=np.int64)
    for i, (a_name, b_name) in enumerate(segments):
        a, b = by_name[a_name], by_name[b_name]
        ab = b.pos - a.pos
        t = np.clip(((co - a.pos) @ ab) / max(ab @ ab, 1e-9), 0.0, 1.0)
        closest = a.pos + t[:, None] * ab
        radius = a.radius[0] + (b.radius[0] - a.radius[0]) * t
        d = np.linalg.norm(co - closest, axis=1) - radius
        better = d < best
        best[better] = d[better]
        label[better] = i
    return label, segments


def sculpt_body(body, joints):
    """Bone under black skin: ribs, a bare knotted spine for a waist, hip bones, shoulder points."""
    co = get_co(body.data)
    nor = get_normals(body.data)
    label, segments = nearest_segment(joints, co)
    seg_child = np.array([b for (_, b) in segments])
    child = seg_child[label]
    ribcage = np.isin(child, ["chest", "upchest", "clav_L", "clav_R"])
    column = np.isin(child, ["spine1", "spine1b", "spine2"])
    neck = np.isin(child, ["neck0", "neck1", "neck2", "skull"])
    hips = np.isin(child, ["hip_L", "hip_R"]) & (co[:, 2] > 1.42)
    x, y, z = co[:, 0], co[:, 1], co[:, 2]
    axis_y = np.interp(z, [1.56, 1.70, 1.84, 1.98, 2.15, 2.35, 2.49, 2.72, 2.95],
                       [0.0, 0.026, 0.033, 0.033, 0.028, 0.034, 0.040, 0.020, -0.020])
    theta = np.arctan2(x, -(y - axis_y))
    front = np.cos(theta)
    d = np.zeros(len(co))

    # The spine where a waist should be: knotted all the way round, vertebra after vertebra.
    knots = np.clip(np.cos(2.0 * math.pi * (z - 1.62) / 0.046), 0.0, None) ** 3
    d += column * (0.011 * knots - 0.003)
    # Spines sticking back off each vertebra.
    d += column * 0.010 * gauss(np.abs(theta) - math.pi, 0.35) * knots

    # Ribs: hard ridges sloping down toward the front, deep between.
    rc = z + 0.06 * front
    ridge = (0.5 + 0.5 * np.cos(2.0 * math.pi * (rc - 2.02) / 0.046)) ** 4
    zone = ribcage * smoothstep(2.0, 2.06, rc) * smoothstep(2.42, 2.36, rc) * smoothstep(0.08, 0.25, np.abs(theta))
    d += zone * (0.0075 * ridge - 0.0045 * (1.0 - ridge))
    # The bottom edge of the ribcage, flaring over nothing.
    d += 0.008 * ribcage * gauss(z - 2.06, 0.018) * (front > -0.3)
    # Sternum and the vertebrae down the back of the chest.
    d += 0.004 * ribcage * gauss(theta, 0.09) * smoothstep(2.08, 2.14, z)
    back = gauss(np.abs(theta) - math.pi, 0.15)
    d += 0.009 * back * np.clip(np.cos(2.0 * math.pi * (z - 2.0) / 0.05), 0.0, None) ** 2 * (ribcage | neck)
    # Shoulder blades standing out of the back.
    for s in (-1.0, 1.0):
        d += 0.010 * ribcage * gauss(theta - s * 2.4, 0.25) * gauss(z - 2.26, 0.06)
    # Hip bones.
    for s in (-1.0, 1.0):
        d += 0.012 * gauss(theta - s * 1.0, 0.3) * gauss(z - 1.60, 0.03) * (hips | (child == "spine1"))
    # Lumps under the skin.
    d += 0.0012 * (Noise(11).fbm(co * 45.0, 3) - 0.5)
    set_co(body.data, co + nor * d[:, None])


BALLS = {"shoulder": 1.25, "elbow": 1.25, "wrist": 1.3, "hip_": 1.2, "knee": 1.25, "ankle": 1.3, "neck0": 1.35,
         "neck1": 1.45, "neck2": 1.5, "spine1": 1.35, "spine1b": 1.45, "spine2": 1.35}


def ball_joints(joints):
    """The armature showing through: a lacquered ball at every joint, like a stop-motion puppet."""
    bm = bmesh.new()
    for j in joints:
        key = next((k for k in BALLS if j.name.startswith(k)), None)
        if key is None:
            continue
        r = max(j.radius) * BALLS[key]
        if j.name.startswith(("hip_", "shoulder")):
            r = max(j.radius) * 1.1
        geom = bmesh.ops.create_uvsphere(bm, u_segments=12, v_segments=8, radius=r)
        bmesh.ops.translate(bm, verts=[v for v in geom["verts"]], vec=tuple(j.pos))
    return to_object(bm, "balls")


# ---------------------------------------------------------------------------------------------
# Head: a card with a face in it
# ---------------------------------------------------------------------------------------------

def tube(bm, points, r0, r1, sides=5):
    """A thin tube along a polyline, tapering from r0 to r1, closed at both ends."""
    pts = np.asarray(points, dtype=float)
    n = len(pts)
    rings = []
    prev_u = None
    for i in range(n):
        t = pts[min(i + 1, n - 1)] - pts[max(i - 1, 0)]
        t /= max(np.linalg.norm(t), 1e-9)
        a = np.array([0.0, 0.0, 1.0]) if abs(t[2]) < 0.9 else np.array([1.0, 0.0, 0.0])
        u = np.cross(t, a) if prev_u is None else prev_u - t * (prev_u @ t)
        u /= max(np.linalg.norm(u), 1e-9)
        prev_u = u
        w = np.cross(t, u)
        radius = r0 + (r1 - r0) * i / max(1, n - 1)
        rings.append([bm.verts.new(pts[i] + (u * math.cos(k * 2 * math.pi / sides) + w * math.sin(k * 2 * math.pi / sides)) * radius)
                      for k in range(sides)])
    for i in range(n - 1):
        for k in range(sides):
            bm.faces.new([rings[i][k], rings[i][(k + 1) % sides], rings[i + 1][(k + 1) % sides], rings[i + 1][k]])
    bm.faces.new(rings[0][::-1])
    bm.faces.new(rings[-1])


def to_object(bm, name):
    for f in bm.faces:
        f.smooth = True
    mesh = bpy.data.meshes.new(name)
    bm.to_mesh(mesh)
    bm.free()
    return link(bpy.data.objects.new(name, mesh))


CARD_GEO = card_mod.layers(264)
CARD_TEX = card_mod.layers(528)
CARD_FRONT = CARD_TEX["paint"]()
CARD_BACK = card_mod.back(CARD_TEX["W"], CARD_TEX["H"], CARD_TEX["card"])


def card_point(u, v, relief):
    """Card-local (u across from the creature's right, v down) to Blender space at rest."""
    x = (np.asarray(u) - 0.5) * 2.0 * CARD[0]
    z = (0.5 - np.asarray(v)) * 2.0 * CARD[2]
    y = -CARD[1] - np.asarray(relief) * DEPTH
    return HEAD_CENTER + np.stack([x, y, z], axis=-1)


def relief_at(px, py):
    r = CARD_GEO["relief"]
    H, W = r.shape
    x = np.clip(px, 0, W - 1.001)
    y = np.clip(py, 0, H - 1.001)
    x0, y0 = int(x), int(y)
    fx, fy = x - x0, y - y0
    return (r[y0, x0] * (1 - fx) * (1 - fy) + r[y0, x0 + 1] * fx * (1 - fy) + r[y0 + 1, x0] * (1 - fx) * fy
            + r[y0 + 1, x0 + 1] * fx * fy)


def card_sheet(name, mask, step=4, thickness=0.011):
    """A grid over the mask, pushed out by the face behind it, given a paper's thickness."""
    H, W = mask.shape
    bm = bmesh.new()
    verts = {}

    def vert(px, py):
        key = (px, py)
        if key not in verts:
            verts[key] = bm.verts.new(tuple(card_point(px / W, py / H, relief_at(px, py))))
        return verts[key]

    for py in range(0, H - step, step):
        for px in range(0, W - step, step):
            if mask[min(py + step // 2, H - 1), min(px + step // 2, W - 1)]:
                bm.faces.new([vert(px, py), vert(px, py + step), vert(px + step, py + step), vert(px + step, py)])
    # Scissors, not pixels: smooth the outer edge (the torn eye holes stay ragged).
    holes = CARD_GEO["holes"]
    near_hole = {}
    for key, v in verts.items():
        px, py = key
        ys = slice(max(py - 3 * step, 0), min(py + 3 * step, H))
        xs = slice(max(px - 3 * step, 0), min(px + 3 * step, W))
        near_hole[v] = bool(holes[ys, xs].any())
    for _ in range(6):
        moves = {}
        for v in bm.verts:
            if not v.is_boundary or near_hole[v]:
                continue
            ring = [e.other_vert(v) for e in v.link_edges if e.is_boundary]
            if len(ring) != 2:
                continue
            avg = (ring[0].co + ring[1].co) * 0.5
            moves[v] = v.co * 0.5 + avg * 0.5
        for v, co in moves.items():
            u = (co.x - HEAD_CENTER[0]) / (2.0 * CARD[0]) + 0.5
            vv = 0.5 - (co.z - HEAD_CENTER[2]) / (2.0 * CARD[2])
            v.co = tuple(card_point(u, vv, relief_at(u * W, vv * H)))
    bm.normal_update()
    if np.mean([f.normal.y for f in bm.faces]) > 0:
        bmesh.ops.reverse_faces(bm, faces=list(bm.faces))
    obj = to_object(bm, name)
    mod = obj.modifiers.new("Solidify", "SOLIDIFY")
    mod.thickness = thickness
    mod.offset = -1.0
    mod.use_even_offset = True
    out = evaluated_copy(obj, name)
    for poly in out.data.polygons:
        poly.use_smooth = True
    return out


def eye_center(i):
    return HEAD_CENTER + EYES_LOCAL[i] + EYE_BACK


def sphere_part(name, center, radius, keep=None, scale=(1.0, 1.0, 1.0), segments=(20, 14), flip=False):
    bm = bmesh.new()
    bmesh.ops.create_uvsphere(bm, u_segments=segments[0], v_segments=segments[1], radius=radius)
    for v in bm.verts:
        v.co = (v.co.x * scale[0], v.co.y * scale[1], v.co.z * scale[2])
    if keep is not None:
        gone = [f for f in bm.faces if not keep(np.array(f.calc_center_median()[:]))]
        bmesh.ops.delete(bm, geom=gone, context="FACES")
    if flip:
        bmesh.ops.reverse_faces(bm, faces=list(bm.faces))
    bmesh.ops.translate(bm, verts=list(bm.verts), vec=tuple(center))
    obj = to_object(bm, name)
    for poly in obj.data.polygons:
        poly.use_smooth = True
    return obj


def build_mouth():
    """Behind the chin piece: a wet dark mouth, a row of small teeth above, the tongue below."""
    x0, x1 = [(c - 0.5) * 2.0 * CARD[0] + d for c, d in zip(card_mod.CUT_U, (0.002, -0.002))]
    zt = MOUTH_LOCAL[2] + 0.004
    zb = -CARD[2] + 0.006  # deep enough for the jaw at its lowest
    yf, yb = 0.0015, 0.030
    bm = bmesh.new()
    corners = {
        "back": [(x0, yb, zt), (x1, yb, zt), (x1, yb, zb), (x0, yb, zb)],
        "top": [(x0, yf, zt), (x1, yf, zt), (x1, yb, zt), (x0, yb, zt)],
        "bottom": [(x0, yb, zb), (x1, yb, zb), (x1, yf, zb), (x0, yf, zb)],
        "left": [(x0, yf, zt), (x0, yb, zt), (x0, yb, zb), (x0, yf, zb)],
        "right": [(x1, yb, zt), (x1, yf, zt), (x1, yf, zb), (x1, yb, zb)],
    }
    for quad in corners.values():
        bm.faces.new([bm.verts.new(tuple(HEAD_CENTER + np.array(c))) for c in quad])
    bmesh.ops.subdivide_edges(bm, edges=list(bm.edges), cuts=3, use_grid_fill=True)
    cavity = to_object(bm, "mouth")
    return cavity


def build_teeth(name, upper, seed):
    """Small worn human teeth in a row along the mouth line, just behind the card."""
    r = rng(seed)
    bm = bmesh.new()
    n = 12
    width = 0.078
    for i in range(n):
        if r.random() < 0.12:
            continue
        x = -width / 2 + (i + 0.5) * width / n + r.normal(0, 0.0006)
        w = width / n * r.uniform(0.75, 0.95)
        h = r.uniform(0.009, 0.014) * (1.0 - 0.35 * abs(x) / (width / 2))
        d = 0.004
        z0 = MOUTH_LOCAL[2] + (0.0015 if upper else -0.0015)
        z1 = z0 - h if upper else z0 + h
        y0 = -0.002 + 0.004 * (x / (width / 2)) ** 2
        geom = bmesh.ops.create_cube(bm, size=1.0)
        for v in geom["verts"]:
            v.co = (x + v.co.x * w, y0 + (v.co.y + 0.5) * d, (z0 + z1) / 2 + v.co.z * abs(z1 - z0))
            v.co = tuple(np.array(v.co[:]) + HEAD_CENTER)
    return to_object(bm, name)


def build_pins():
    """Four short pins: two top corners and a temple through the card, one through the chin piece."""
    spots = [((0.10, 0.06), (-1.0, 0.7)), ((0.91, 0.045), (1.0, 0.8)), ((0.95, 0.37), (1.0, 0.15)), ((0.43, 0.9), (-0.6, -0.8))]
    head, jaw = bmesh.new(), bmesh.new()
    for i, ((u, v), (du, dv)) in enumerate(spots):
        start = card_point(u, v, 0.0) + np.array([0.0, -0.002, 0.0])
        d = np.array([du * CARD[0] * 2.0, -0.35, -dv * CARD[2] * 2.0 * 0.5])
        d /= np.linalg.norm(d)
        length = 0.055 + 0.01 * (i % 2)
        end = start + d * length
        pts = [start - d * 0.012, start, end]
        target = jaw if v > card_mod.MOUTH_V and card_mod.CUT_U[0] < u < card_mod.CUT_U[1] else head
        tube(target, pts, 0.0016, 0.0013, 5)
        geom = bmesh.ops.create_uvsphere(target, u_segments=8, v_segments=6, radius=0.0042)
        bmesh.ops.translate(target, verts=list(geom["verts"]), vec=tuple(end))
    return to_object(head, "pins_head"), to_object(jaw, "pins_jaw")


def build_head():
    upper = card_sheet("card", CARD_GEO["card"] & ~CARD_GEO["holes"] & ~CARD_GEO["jaw"])
    chin = card_sheet("jaw_card", CARD_GEO["jaw"] & ~CARD_GEO["holes"])
    eyes, lids, sockets = [], [], []
    for i, side in enumerate(("L", "R")):
        c = eye_center(i)
        eyes.append(sphere_part(f"eye_{side}", c, EYE_RADIUS))
        # the upper lid: a cap over the top of the eye that the game can close
        lids.append(sphere_part(f"lid_{side}", c, EYE_RADIUS * 1.09,
                                keep=lambda q: q[2] > EYE_RADIUS * 0.45 and q[1] < EYE_RADIUS * 0.5,
                                segments=(20, 16)))
        # a dark socket behind the hole, so nothing shows through the head
        sockets.append(sphere_part(f"socket_{side}", c + np.array([0.0, 0.008, 0.0]), 0.022, scale=(1.45, 1.0, 1.05),
                                   keep=lambda q: q[1] > -0.012, flip=True, segments=(16, 10)))
    cavity = build_mouth()
    # Behind the card: the puppet's own black head it is pinned to. It hides the sockets and the
    # mouth from behind; from in front the card covers it.
    skull = sphere_part("skull", HEAD_CENTER + np.array([0.0, 0.016, -0.045]), 1.0, scale=(0.112, 0.036, 0.215),
                        keep=lambda q: q[1] > -0.004, segments=(24, 16))
    teeth_upper = build_teeth("teeth_upper", True, 5)
    teeth_lower = build_teeth("teeth_lower", False, 6)
    pins_head, pins_jaw = build_pins()
    parts = []
    objs = []
    specs = [(upper, "card", PART_SKIN, "head"), (chin, "jaw_card", PART_SKIN, "jaw"), (cavity, "mouth", PART_MOUTH, "head"),
             (skull, "skull", PART_SOCKET, "head"),
             (teeth_upper, "teeth_upper", PART_TEETH, "head"),
             (teeth_lower, "teeth_lower", PART_TEETH, "jaw"), (pins_head, "pins_head", PART_NEEDLE, "head"),
             (pins_jaw, "pins_jaw", PART_NEEDLE, "jaw")]
    for i, side in enumerate(("L", "R")):
        specs += [(eyes[i], f"eye_{side}", PART_EYE, f"eye{side}"), (lids[i], f"lid_{side}", PART_LID, f"lid{side}"),
                  (sockets[i], f"socket_{side}", PART_SOCKET, "head")]
    for obj, mat_name, part, group in specs:
        mat = bpy.data.materials.new(mat_name)
        mat.use_nodes = True
        obj.data.materials.append(mat)
        parts.append((mat_name, part, group))
        objs.append(obj)
    joined = join(objs, "head_all")
    return joined, parts


# ---------------------------------------------------------------------------------------------
# Noise (numpy): value noise and fBm on 3D points
# ---------------------------------------------------------------------------------------------

class Noise:
    def __init__(self, seed, size=64):
        self.grid = rng(seed).random((size, size, size)).astype(np.float32)
        self.size = size

    def __call__(self, p):
        s = self.size
        i = np.floor(p).astype(np.int64)
        f = (p - i).astype(np.float32)
        f = f * f * (3.0 - 2.0 * f)
        x0, y0, z0 = (i % s).T
        x1, y1, z1 = ((i + 1) % s).T
        fx, fy, fz = f.T
        g = self.grid
        c00 = g[x0, y0, z0] * (1 - fx) + g[x1, y0, z0] * fx
        c10 = g[x0, y1, z0] * (1 - fx) + g[x1, y1, z0] * fx
        c01 = g[x0, y0, z1] * (1 - fx) + g[x1, y0, z1] * fx
        c11 = g[x0, y1, z1] * (1 - fx) + g[x1, y1, z1] * fx
        return (c00 * (1 - fy) + c10 * fy) * (1 - fz) + (c01 * (1 - fy) + c11 * fy) * fz

    def fbm(self, p, octaves=5, gain=0.5):
        total = np.zeros(len(p), dtype=np.float32)
        amp = 0.5
        norm = 0.0
        for o in range(octaves):
            total += amp * self(p * (2.03 ** o) + o * 17.17)
            norm += amp
            amp *= gain
        return total / norm


# ---------------------------------------------------------------------------------------------
# Texturing: bake position / normal / AO / part maps with Cycles, paint them in numpy
# ---------------------------------------------------------------------------------------------

POS_OFFSET = np.array([1.0, 1.0, 0.1])
POS_SCALE = 4.0


def unwrap(objs, margin):
    select_only(objs)
    bpy.ops.object.mode_set(mode="EDIT")
    bpy.ops.mesh.select_all(action="SELECT")
    bpy.ops.uv.smart_project(angle_limit=math.radians(55), island_margin=margin, area_weight=0.0, scale_to_bounds=True)
    bpy.ops.object.mode_set(mode="OBJECT")


def set_emission(mat, mode, part, image):
    t = mat.node_tree
    for n in list(t.nodes):
        t.nodes.remove(n)
    out = t.nodes.new("ShaderNodeOutputMaterial")
    emit = t.nodes.new("ShaderNodeEmission")
    t.links.new(emit.outputs[0], out.inputs["Surface"])
    coord = t.nodes.new("ShaderNodeTexCoord")
    if mode == "pos":
        ma = t.nodes.new("ShaderNodeVectorMath")
        ma.operation = "MULTIPLY_ADD"
        ma.inputs[1].default_value = (1.0 / POS_SCALE,) * 3
        ma.inputs[2].default_value = tuple(POS_OFFSET / POS_SCALE)
        t.links.new(coord.outputs["Object"], ma.inputs[0])
        t.links.new(ma.outputs[0], emit.inputs["Color"])
    elif mode == "nor":
        ma = t.nodes.new("ShaderNodeVectorMath")
        ma.operation = "MULTIPLY_ADD"
        ma.inputs[1].default_value = (0.5, 0.5, 0.5)
        ma.inputs[2].default_value = (0.5, 0.5, 0.5)
        t.links.new(coord.outputs["Normal"], ma.inputs[0])
        t.links.new(ma.outputs[0], emit.inputs["Color"])
    else:
        emit.inputs["Color"].default_value = ((part + 0.5) / 8.0, 0.0, 0.0, 1.0)
    tex = t.nodes.new("ShaderNodeTexImage")
    tex.image = image
    t.nodes.active = tex


def float_image(name, size):
    img = bpy.data.images.new(name, size, size, alpha=True, float_buffer=True)
    img.colorspace_settings.name = "Non-Color"
    img.generated_color = (0.0, 0.0, 0.0, 0.0)
    return img


def read_image(img):
    w, h = img.size
    px = np.zeros(w * h * 4, dtype=np.float32)
    img.pixels.foreach_get(px)
    return px.reshape(h, w, 4)


def bake_maps(obj, parts, size, quick, ao_distance):
    """Returns position (blocks), normal, AO and part-id maps of one object. `parts` maps each of
    its material names to a part id."""
    scene = bpy.context.scene
    scene.render.engine = "CYCLES"
    scene.cycles.device = "CPU"
    if scene.world is None:
        scene.world = bpy.data.worlds.new("bake_world")
    scene.world.light_settings.distance = ao_distance
    maps = {}
    for mode in ("pos", "nor", "part", "ao"):
        img = float_image(f"{obj.name}_{mode}", size)
        for slot in obj.material_slots:
            set_emission(slot.material, mode if mode != "ao" else "part", parts.get(slot.material.name, PART_SKIN), img)
        scene.cycles.samples = 1 if mode != "ao" else (16 if quick else 96)
        select_only([obj])
        bpy.ops.object.bake(type="EMIT" if mode != "ao" else "AO", margin=4, use_clear=False)
        maps[mode] = read_image(img)
        bpy.data.images.remove(img)
    covered = maps["part"][:, :, 3] > 0.5
    pos = maps["pos"][:, :, :3] * POS_SCALE - POS_OFFSET
    nor = maps["nor"][:, :, :3] * 2.0 - 1.0
    part = np.clip(np.floor(maps["part"][:, :, 0] * 8.0), 0, 7).astype(np.int64)
    ao = np.clip(maps["ao"][:, :, 0], 0.0, 1.0)
    return pos, nor, ao, part, covered


def srgb_to_linear(c):
    c = np.asarray(c, dtype=np.float32) / 255.0
    return np.where(c <= 0.04045, c / 12.92, ((c + 0.055) / 1.055) ** 2.4)


def linear_to_srgb8(c):
    c = np.clip(c, 0.0, 1.0)
    s = np.where(c <= 0.0031308, c * 12.92, 1.055 * np.power(c, 1.0 / 2.4) - 0.055)
    return np.clip(np.round(s * 255.0), 0, 255).astype(np.uint8)


def mix(a, b, t):
    t = np.asarray(t)[..., None] if np.ndim(t) else t
    return a * (1.0 - t) + b * t


def paint_body(p, n, ao, part):
    """Black, like something wet that has dried; the ball joints lacquered; the hands pale skin,
    sewn on at the wrists, dirt in the creases, dark nails."""
    N = len(p)
    big = Noise(21).fbm(p * 3.0, 4)
    mid = Noise(22).fbm(p * 14.0, 4)
    fine = Noise(23).fbm(p * 70.0, 3)
    base = srgb_to_linear((17, 15, 16))
    col = base * (0.75 + 0.5 * big[:, None]) * (0.85 + 0.3 * mid[:, None]) * (0.9 + 0.2 * fine[:, None])
    col = col * (0.55 + 0.45 * ao[:, None])
    # lacquer: a dull highlight on the top of each ball joint
    ball = part == PART_BALL
    gloss = smoothstep(0.45, 0.9, n[ball, 2]) * (0.6 + 0.4 * (n[ball, 1] < 0))
    col[ball] = mix(col[ball], srgb_to_linear((74, 66, 72)), gloss * 0.7)
    # the hands, below the seam at the wrist
    x, y, z = p[:, 0], p[:, 1], p[:, 2]
    hand = (np.abs(x) > 0.16) & (z < 1.122)
    if hand.any():
        hp = p[hand]
        skin = srgb_to_linear((206, 184, 172)) * (0.82 + 0.3 * Noise(24).fbm(hp * 40.0, 3))[:, None]
        mottle = smoothstep(0.55, 0.75, Noise(25).fbm(hp * 18.0, 3))
        skin = mix(skin, srgb_to_linear((170, 128, 132)), mottle * 0.45)
        veins = smoothstep(0.6, 0.66, Noise(26).fbm(hp * 160.0, 3)) * (hp[:, 2] > 0.95)
        skin = mix(skin, srgb_to_linear((120, 128, 150)), veins * 0.35)
        # the extra knuckle: creases at every joint of every finger
        crease = np.zeros(len(hp))
        for k, zj in enumerate(np.cumsum((0.0,) + tuple(poses.FINGER_SEGMENTS))[1:4]):
            crease += np.exp(-((hp[:, 2] - (0.995 - zj)) / 0.004) ** 2)
        skin = mix(skin, srgb_to_linear((96, 70, 66)), np.clip(crease, 0, 1) * 0.55)
        # dark nails at the tips, dirt under them
        offs = np.array([-0.030, -0.010, 0.010, 0.029]) + 0.018
        lengths = np.array([0.94, 1.0, 0.96, 0.82]) * sum(poses.FINGER_SEGMENTS)
        which = np.argmin(np.abs(hp[:, 1][:, None] - offs[None, :]), axis=1)
        tip = 0.995 - lengths[which]
        nail = (hp[:, 2] < tip + 0.02) & (hp[:, 2] < 1.0)
        skin[nail] = mix(skin[nail], srgb_to_linear((70, 56, 44)), 0.8)
        skin *= (0.6 + 0.4 * ao[hand])[:, None]
        col[hand] = skin
    # the seam: thread round the wrist, stitches across it
    seam = (np.abs(x) > 0.16) & (np.abs(z - 1.124) < 0.006)
    if seam.any():
        ang = np.arctan2(y[seam] - 0.03, np.abs(x[seam]) - 0.218)
        stitch = (np.sin(ang * 14.0) > 0.4) | (np.abs(z[seam] - 1.124) < 0.0015)
        col[seam] = np.where(stitch[:, None], srgb_to_linear((44, 30, 24)), col[seam])
    return col


def sample(img, u, v):
    """Bilinear sample of an sRGB image at normalized (u, v); returns linear colour."""
    H, W = img.shape[:2]
    x = np.clip(u * W - 0.5, 0, W - 1.001)
    y = np.clip(v * H - 0.5, 0, H - 1.001)
    x0, y0 = x.astype(np.int64), y.astype(np.int64)
    fx, fy = (x - x0)[:, None], (y - y0)[:, None]
    c = (img[y0, x0] * (1 - fx) * (1 - fy) + img[y0, x0 + 1] * fx * (1 - fy) + img[y0 + 1, x0] * (1 - fx) * fy
         + img[y0 + 1, x0 + 1] * fx * fy)
    return srgb_to_linear(c)


def paint_head(p, n, ao, part):
    """The card (from tools/creature/card.py), the wet eyes behind it, the lids, the dark sockets,
    the mouth behind the chin piece, the teeth, the pins."""
    N = len(p)
    col = np.zeros((N, 3), dtype=np.float32)
    local = p - HEAD_CENTER
    u = local[:, 0] / (2.0 * CARD[0]) + 0.5
    v = 0.5 - local[:, 2] / (2.0 * CARD[2])
    fine = Noise(41).fbm(p * 90.0, 3)
    # the card: its painted front, its pencilled back, a paper edge
    card = part == PART_SKIN
    front = card & (n[:, 1] < -0.35)
    back = card & (n[:, 1] > 0.35)
    rim = card & ~front & ~back
    col[front] = sample(CARD_FRONT, u[front], v[front])
    col[back] = sample(CARD_BACK, 1.0 - u[back], v[back])
    col[rim] = srgb_to_linear((176, 164, 136)) * (0.85 + 0.3 * fine[rim, None])
    col[card] *= (0.62 + 0.38 * ao[card])[:, None]
    # the eyes: dingy, bloodshot, grey-green irises, wet
    eye = part == PART_EYE
    if eye.any():
        idx = (local[eye, 0] > 0).astype(np.int64)
        c = np.stack([eye_center(0), eye_center(1)])[idx]
        d = p[eye] - c
        d /= np.linalg.norm(d, axis=1, keepdims=True) + 1e-9
        cosang = -d[:, 1]
        ang = np.arccos(np.clip(cosang, -1, 1))
        around = np.arctan2(d[:, 2], d[:, 0])
        sclera = srgb_to_linear((214, 204, 186)) * (0.85 - 0.25 * smoothstep(0.6, 1.6, ang))[:, None]
        veins = smoothstep(0.62, 0.7, Noise(43).fbm(p[eye] * 900.0, 3)) * smoothstep(0.55, 1.4, ang)
        sclera = mix(sclera, srgb_to_linear((150, 32, 30)), veins * 0.8)
        spokes = 0.5 + 0.5 * np.sin(around * 23.0 + 3.0 * Noise(44).fbm(p[eye] * 600.0, 2))
        iris = mix(srgb_to_linear((60, 78, 70)), srgb_to_linear((120, 132, 108)), spokes * 0.6)
        iris = mix(iris, srgb_to_linear((22, 26, 24)), smoothstep(0.42, 0.52, ang))
        e = np.where((ang < 0.55)[:, None], iris, sclera)
        e = np.where((ang < 0.2)[:, None], srgb_to_linear((6, 6, 6)), e)
        hl = np.exp(-(((d[:, 0] + 0.28) / 0.12) ** 2 + ((d[:, 2] - 0.3) / 0.12) ** 2)) * (cosang > 0.6)
        e = mix(e, srgb_to_linear((250, 248, 240)), np.clip(hl * 1.2, 0, 1))
        col[eye] = e
    # the lids: pale skin darkening to the lash line at the lower edge
    lid = part == PART_LID
    if lid.any():
        idx = (local[lid, 0] > 0).astype(np.int64)
        c = np.stack([eye_center(0), eye_center(1)])[idx]
        h = (p[lid, 2] - c[:, 2]) / EYE_RADIUS
        skin = srgb_to_linear((196, 160, 150)) * (0.8 + 0.25 * fine[lid])[:, None]
        col[lid] = mix(skin, srgb_to_linear((40, 22, 20)), smoothstep(0.62, 0.45, h))
    # the sockets: dark, wet, red at the back
    sock = part == PART_SOCKET
    col[sock] = srgb_to_linear((22, 8, 8)) * (0.7 + 0.5 * fine[sock, None])
    # the mouth: gums and the wet inside; the tongue a little paler
    mouth = part == PART_MOUTH
    if mouth.any():
        depth = smoothstep(0.004, 0.03, local[mouth, 1])
        m = mix(srgb_to_linear((120, 40, 44)), srgb_to_linear((30, 8, 10)), depth)
        m *= (0.75 + 0.5 * Noise(45).fbm(p[mouth] * 300.0, 3))[:, None]
        col[mouth] = m
    teeth = part == PART_TEETH
    if teeth.any():
        dz = np.abs(local[teeth, 2] - MOUTH_LOCAL[2])
        t = mix(srgb_to_linear((214, 200, 160)), srgb_to_linear((120, 96, 60)), smoothstep(0.004, 0.0085, dz))
        col[teeth] = t * (0.8 + 0.3 * fine[teeth, None])
    pins = part == PART_NEEDLE
    col[pins] = srgb_to_linear((168, 164, 156)) * (0.7 + 0.4 * fine[pins, None])
    return col


def make_texture(name, obj, parts, size, quick, painter, ao_distance):
    """Bakes position, normal, occlusion and part maps for {obj} once, paints them, and returns the
    bake so variants (the temper faces) can be painted from it again."""
    baked = bake_maps(obj, parts, size, quick, ao_distance)
    paint_texture(name, baked, painter)
    return baked


def paint_texture(name, baked, painter):
    pos, nor, ao, part, covered = baked
    h, w = covered.shape
    flat = covered.reshape(-1)
    p = pos.reshape(-1, 3)[flat]
    n = nor.reshape(-1, 3)[flat]
    a = ao.reshape(-1)[flat]
    pt = part.reshape(-1)[flat]
    col = painter(p, n, a, pt)
    out = np.zeros((h * w, 3), dtype=np.float32)
    out[:] = col.mean(axis=0)
    out[flat] = col
    img = linear_to_srgb8(out).reshape(h, w, 3)
    img = img[::-1]  # Blender images start at the bottom row
    grain = rng(77).normal(0.0, 2.5, img.shape[:2])[:, :, None]
    img = np.clip(img.astype(np.float32) + grain, 0, 255).astype(np.uint8)
    from PIL import Image
    os.makedirs(TEX_DIR, exist_ok=True)
    Image.fromarray(img, "RGB").save(os.path.join(TEX_DIR, name + ".png"), optimize=True)


# ---------------------------------------------------------------------------------------------
# Decimation and joining
# ---------------------------------------------------------------------------------------------

def decimate(obj, ratio):
    mod = obj.modifiers.new("Decimate", "DECIMATE")
    mod.decimate_type = "COLLAPSE"
    mod.ratio = ratio
    mod.use_collapse_triangulate = True
    return evaluated_copy(obj, obj.name)


def join(objs, name):
    select_only(objs)
    bpy.ops.object.join()
    out = bpy.context.view_layer.objects.active
    out.name = name
    out.data.name = name
    return out


def separate_by_material(obj):
    select_only([obj])
    bpy.ops.object.mode_set(mode="EDIT")
    bpy.ops.mesh.select_all(action="SELECT")
    bpy.ops.mesh.separate(type="MATERIAL")
    bpy.ops.object.mode_set(mode="OBJECT")
    pieces = {}
    for o in list(bpy.context.scene.objects):
        if o.type != "MESH" or not o.data.polygons:
            continue
        slot = o.data.polygons[0].material_index
        mat = o.material_slots[slot].material if slot < len(o.material_slots) else None
        if mat is not None and o.name.startswith("head_all"):
            pieces[mat.name] = o
    return pieces


def set_part_attribute(obj, part):
    attr = obj.data.attributes.new("part", "INT", "POINT")
    attr.data.foreach_set("value", np.full(len(obj.data.vertices), part, dtype=np.int32))


# ---------------------------------------------------------------------------------------------
# Rig and poses
# ---------------------------------------------------------------------------------------------

def build_rig(joints, body):
    data = bpy.data.armatures.new("rig")
    rig = link(bpy.data.objects.new("rig", data))
    select_only([rig])
    bpy.ops.object.mode_set(mode="EDIT")
    eb = data.edit_bones
    by_name = {j.name: j for j in joints}
    root = eb.new("root")
    root.head = tuple(by_name["pelvis"].pos - np.array([0.0, 0.0, 0.12]))
    root.tail = tuple(by_name["pelvis"].pos)
    for j in joints:
        if j.parent is None:
            continue
        b = eb.new(j.name)
        b.head = tuple(by_name[j.parent].pos)
        tail = j.pos if np.linalg.norm(j.pos - by_name[j.parent].pos) > 1e-4 else j.pos + np.array([0, 0, 0.01])
        b.tail = tuple(tail)
    for j in joints:
        if j.parent is None:
            continue
        parent = by_name[j.parent]
        eb[j.name].parent = eb[parent.name] if parent.parent is not None else eb["root"]
    bpy.ops.object.mode_set(mode="OBJECT")
    data.bones["root"].use_deform = False
    # Bone-heat weights.
    select_only([body, rig])
    bpy.context.view_layer.objects.active = rig
    bpy.ops.object.parent_set(type="ARMATURE_AUTO")
    mod = next(m for m in body.modifiers if m.type == "ARMATURE")
    mod.use_deform_preserve_volume = True
    fill_missing_weights(body, joints)
    return rig


def fill_missing_weights(body, joints):
    """Bone heat can fail on thin parts; any vertex left without weights follows its nearest bone."""
    groups = {g.index: g.name for g in body.vertex_groups}
    totals = np.zeros(len(body.data.vertices))
    for v in body.data.vertices:
        totals[v.index] = sum(g.weight for g in v.groups if groups.get(g.group) not in (None, "root"))
    missing = np.where(totals < 1e-4)[0]
    if len(missing) == 0:
        return
    co = get_co(body.data)[missing]
    label, segments = nearest_segment(joints, co)
    for vi, li in zip(missing, label):
        name = segments[li][1]
        group = body.vertex_groups.get(name) or body.vertex_groups.new(name=name)
        group.add([int(vi)], 1.0, "REPLACE")
    print(f"  weights: {len(missing)} vertices assigned to their nearest bone")


def apply_pose(rig, joints, pose):
    mats = joint_transforms(joints, pose)
    deform = {"root": mats["pelvis"]}
    for j in joints:
        if j.parent is not None:
            deform[j.name] = mats[j.parent]
    for pb in rig.pose.bones:
        bone = pb.bone
        rest = np.array(bone.matrix_local)
        d = deform[pb.name]
        parent_d = deform[bone.parent.name] if bone.parent else np.eye(4)
        basis = np.linalg.inv(rest) @ np.linalg.inv(parent_d) @ d @ rest
        pb.matrix_basis = Matrix(basis.tolist())
    bpy.context.view_layer.update()
    return mats


def to_mc(p):
    """Blender (x, y, z), facing -Y, Z up  ->  Minecraft model space (x, y, z), facing +Z, Y up."""
    p = np.atleast_2d(p)
    return np.stack([p[:, 0], p[:, 2], -p[:, 1]], axis=1)


def transform_points(m, p):
    p = np.atleast_2d(p)
    return p @ m[:3, :3].T + m[:3, 3]


# ---------------------------------------------------------------------------------------------
# Export
# ---------------------------------------------------------------------------------------------

def mesh_render_data(obj):
    """Splits the mesh into render vertices (one per unique position+UV), triangulated.
    Returns (vertex_index[n] into obj vertices, uv[n,2], part[n], triangles[m,3])."""
    mesh = obj.data
    mesh.calc_loop_triangles()
    tris = mesh.loop_triangles
    loops = np.zeros(len(tris) * 3, dtype=np.int64)
    tris.foreach_get("loops", loops)
    loop_vert = np.zeros(len(mesh.loops), dtype=np.int64)
    mesh.loops.foreach_get("vertex_index", loop_vert)
    uv = np.zeros(len(mesh.loops) * 2)
    mesh.uv_layers.active.data.foreach_get("uv", uv)
    uv = uv.reshape(-1, 2)
    part_attr = mesh.attributes.get("part")
    vpart = np.zeros(len(mesh.vertices), dtype=np.int32)
    if part_attr is not None:
        part_attr.data.foreach_get("value", vpart)
    key = np.stack([loop_vert[loops], np.round(uv[loops, 0] * 65535).astype(np.int64),
                    np.round(uv[loops, 1] * 65535).astype(np.int64)], axis=1)
    uniq, inverse = np.unique(key, axis=0, return_inverse=True)
    inverse = inverse.reshape(-1)
    first = np.zeros(len(uniq), dtype=np.int64)
    first[inverse[::-1]] = np.arange(len(loops))[::-1]
    vidx = loop_vert[loops[first]]
    vuv = uv[loops[first]]
    return vidx, vuv, vpart[vidx], inverse.reshape(-1, 3)


def smooth_normals_by_position(co, tris):
    """Normals averaged across UV seams, so seams do not show as creases."""
    keys = np.round(co * 20000).astype(np.int64)
    _, inv = np.unique(keys, axis=0, return_inverse=True)
    inv = inv.reshape(-1)
    a, b, c = co[tris[:, 0]], co[tris[:, 1]], co[tris[:, 2]]
    fn = np.cross(b - a, c - a)
    acc = np.zeros((inv.max() + 1, 3))
    for k in range(3):
        np.add.at(acc, inv[tris[:, k]], fn)
    vn = acc[inv]
    norm = np.linalg.norm(vn, axis=1)
    norm[norm == 0] = 1.0
    return vn / norm[:, None]


def write_obj(path, groups):
    lines = ["# The Wake: creature pose, generated by tools/creature/build_creature.py",
             "# Minecraft model space: blocks, +Y up, facing +Z, feet at the origin."]
    off = 0
    for name, pos, nor, uv, tris in groups:
        lines.append(f"o {name}")
        lines += [f"v {p[0]:.5f} {p[1]:.5f} {p[2]:.5f}" for p in pos]
        lines += [f"vt {t[0]:.5f} {t[1]:.5f}" for t in uv]
        lines += [f"vn {n[0]:.4f} {n[1]:.4f} {n[2]:.4f}" for n in nor]
        for t in tris:
            a, b, c = t + 1 + off
            lines.append(f"f {a}/{a}/{a} {b}/{b}/{b} {c}/{c}/{c}")
        off += len(pos)
    with open(path, "w") as f:
        f.write("\n".join(lines) + "\n")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--previews", action="store_true", help="render every pose with Cycles afterwards")
    parser.add_argument("--previews-only", action="store_true", help="only render previews of the current export")
    parser.add_argument("--poses", default="", help="comma-separated poses to preview (default: all)")
    parser.add_argument("--quick", action="store_true", help="fewer AO samples, smaller textures")
    parser.add_argument("--obj", action="store_true", help="also export OBJs into tools/creature/export/")
    args = parser.parse_args(sys.argv[1:])
    preview_poses = [p for p in args.poses.split(",") if p] or list(POSES) + ["walk:0", "walk:2", "crawl:1", "lunge:9"]
    if args.previews_only:
        render_previews(preview_poses)
        return

    bpy.ops.wm.read_factory_settings(use_empty=True)
    joints = skeleton()
    print("sculpting")
    body = build_body(joints)
    balls = ball_joints(joints)
    head_all, head_parts = build_head()
    for obj, mat_name in ((body, "body_skin"), (balls, "balls")):
        mat = bpy.data.materials.new(mat_name)
        mat.use_nodes = True
        obj.data.materials.append(mat)
    body = join([body, balls], "body")

    print("texturing")
    tex_size = 512 if args.quick else 1024
    unwrap([body], 0.003)
    unwrap([head_all], 0.004)
    make_texture("creature_body", body, {"body_skin": PART_SKIN, "balls": PART_BALL}, tex_size, args.quick, paint_body, 0.08)
    head_bake = make_texture("creature_head", head_all, {name: part for name, part, _ in head_parts}, tex_size, args.quick, paint_head, 0.03)
    # Its temper, as faces (Reputation.Form in the mod): the same bake, a worse card each time.
    global CARD_FRONT
    calm = CARD_FRONT
    for form, suffix in ((1, "uneasy"), (2, "angry"), (3, "furious")):
        CARD_FRONT = card_mod.dress(CARD_TEX, form)
        paint_texture("creature_head_" + suffix, head_bake, paint_head)
    CARD_FRONT = calm

    print("decimating")
    body = decimate(body, 0.26)
    set_part_attribute(body, PART_SKIN)
    pieces = separate_by_material(head_all)
    groups_of = {}
    for name, part, group in head_parts:
        obj = pieces[name]
        if part == PART_SKIN:
            obj = decimate(obj, 0.3 if group == "head" else 0.4)
        elif part == PART_MOUTH:
            obj = decimate(obj, 0.5)
        set_part_attribute(obj, part)
        groups_of.setdefault(group, []).append(obj)
    rigid = {name: join(objs, name) for name, objs in groups_of.items()}

    print("rigging")
    rig = build_rig(joints, body)

    # The skeleton as the game sees it: the body's joints, then the head's (jaw, eyes, lids).
    ejoints = joints + poses.head_joints()
    names = [j.name for j in ejoints]
    index = {n: i for i, n in enumerate(names)}
    parent_of = {j.name: j.parent for j in joints if j.parent}
    rides = {"head": "neck2", "jaw": "jaw", "eyeL": "eye_L", "eyeR": "eye_R", "lidL": "lid_L", "lidR": "lid_R"}
    groups = []
    for name, obj in [("body", body)] + [(k, rigid[k]) for k in ("head", "jaw", "eyeL", "eyeR", "lidL", "lidR")]:
        vidx, uv, part, tris = mesh_render_data(obj)
        texture = "thewake:textures/entity/creature_" + ("body" if name == "body" else "head") + ".png"
        rest = to_mc(get_co(obj.data)[vidx])
        g = {"name": name, "texture": texture, "uv": uv, "part": part, "tris": tris, "rigid": name != "body",
             "rest": rest, "rest_normals": smooth_normals_by_position(rest, tris)}
        if name == "body":
            g["joints"], g["weights"] = export_v3.deform_weights(body, names, parent_of, vidx)
            g["vidx"] = vidx
        else:
            g["joint"] = index[rides[name]]
        groups.append(g)

    def point(name, joint, p):
        return (name, index[joint], 0, to_mc(p)[0])

    def direction(name, joint, d):
        return (name, index[joint], 1, to_mc(np.asarray(d, float))[0])

    jaw_hinge = HEAD_CENTER + JAW_HINGE_LOCAL
    mouth_rest = HEAD_CENTER + MOUTH_LOCAL
    attachments = [point("head", "neck2", HEAD_CENTER), direction("headRight", "neck2", (1.0, 0.0, 0.0)),
                   direction("headUp", "neck2", (0.0, 0.0, 1.0)), direction("headForward", "neck2", (0.0, -1.0, 0.0)),
                   point("eyeLeft", "eye_L", eye_center(0)), point("eyeRight", "eye_R", eye_center(1)),
                   point("jawHinge", "neck2", jaw_hinge), point("mouth", "jaw", mouth_rest)]

    # Does the game's skinning match Blender's? Same pose both ways.
    body_g = groups[0]
    apply_pose(rig, joints, POSES["stand"])
    depsgraph = bpy.context.evaluated_depsgraph_get()
    ev = body.evaluated_get(depsgraph)
    m = ev.to_mesh()
    blender_co = to_mc(get_co(m)[body_g["vidx"]])
    ev.to_mesh_clear()
    f = export_v3.frame_transforms(joint_transforms(ejoints, POSES["stand"]), names)
    ours, _ = export_v3.dqs(body_g["rest"], body_g["rest_normals"], body_g["joints"], body_g["weights"], f)
    err = np.linalg.norm(ours - blender_co, axis=1)
    print(f"  skinning check (stand): mean {err.mean() * 1000:.2f} mm, 99% {np.percentile(err, 99) * 1000:.2f} mm, "
          f"max {err.max() * 1000:.1f} mm")

    thin = {PART_NEEDLE}
    clips_out = []
    summary = {}
    for name, (fps, loop, frames) in poses.clips(ejoints).items():
        fr, heights = [], []
        for pose in frames:
            f = export_v3.frame_transforms(joint_transforms(ejoints, pose), names)
            fr.append(f)
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
        clips_out.append({"name": name, "fps": fps, "loop": loop, "frames": np.array(fr), "heights": heights})
        summary[name] = {"frames": len(fr), "fps": fps, "loop": loop, "height": round(max(heights), 3)}
        print(f"  clip {name}: {len(fr)} frame(s), tallest {max(heights):.2f}")
    joint_list = [(j.name, index[j.parent] if j.parent else -1, to_mc(j.pos)[0]) for j in ejoints]
    os.makedirs(MODEL_DIR, exist_ok=True)
    size = export_v3.write_bin(os.path.join(MODEL_DIR, "creature.bin.gz"), joint_list, attachments, groups, clips_out)
    with open(os.path.join(MODEL_DIR, "clips.json"), "w") as fh:
        json.dump({"version": 3, "joints": len(names), "clips": summary}, fh, indent=1)
    old = os.path.join(MODEL_DIR, "poses.json")
    if os.path.exists(old):
        os.remove(old)
    if args.obj:
        os.makedirs(OBJ_DIR, exist_ok=True)
        model = export_v3.read_bin(os.path.join(MODEL_DIR, "creature.bin.gz"))
        for name in POSES:
            write_obj(os.path.join(OBJ_DIR, name + ".obj"),
                      [(g["name"], pos, nor, g["uv"], g["tris"]) for g, pos, nor in export_v3.posed(model, name, 0)])
    tri_total = sum(g["tris"].shape[0] for g in groups)
    vert_total = sum(len(g["uv"]) for g in groups)
    print(f"exported {len(clips_out)} clips, {len(names)} joints, {vert_total} vertices, {tri_total} triangles, "
          f"{size / 1e6:.2f} MB raw")
    if args.previews:
        render_previews(preview_poses)


# ---------------------------------------------------------------------------------------------
# Previews: read the exported binary back and render it
# ---------------------------------------------------------------------------------------------

def render_previews(pose_names):
    """Cycles renders: a full view in a blocky room corner and a face close-up, lit by one harsh
    light beside the camera, like a flash photo in a dark room."""
    model = export_v3.read_bin(os.path.join(MODEL_DIR, "creature.bin.gz"))
    os.makedirs(PREVIEW_DIR, exist_ok=True)
    for pose_name in pose_names:
        clip, _, frame = pose_name.partition(":")
        frame = int(frame or 0)
        bpy.ops.wm.read_factory_settings(use_empty=True)
        scene = bpy.context.scene
        scene.render.engine = "CYCLES"
        scene.cycles.device = "CPU"
        scene.cycles.samples = 48
        scene.render.resolution_x = 600
        scene.render.resolution_y = 800
        scene.view_settings.view_transform = "Standard"
        images = {}
        for g, pos, nor in export_v3.posed(model, clip, frame):
            bl = np.stack([pos[:, 0], -pos[:, 2], pos[:, 1]], axis=1)  # mc -> blender
            mesh = bpy.data.meshes.new(g["name"])
            mesh.from_pydata(bl.tolist(), [], g["tris"].tolist())
            uv_layer = mesh.uv_layers.new()
            loop_v = np.zeros(len(mesh.loops), dtype=np.int64)
            mesh.loops.foreach_get("vertex_index", loop_v)
            uv_layer.data.foreach_set("uv", g["uv"][loop_v].reshape(-1).astype(np.float32))
            for poly in mesh.polygons:
                poly.use_smooth = True
            obj = link(bpy.data.objects.new(g["name"], mesh))
            tex = g["texture"].split("/")[-1]
            if tex not in images:
                images[tex] = bpy.data.images.load(os.path.join(TEX_DIR, tex))
            mat = bpy.data.materials.new("m_" + g["name"])
            mat.use_nodes = True
            bsdf = mat.node_tree.nodes["Principled BSDF"]
            t = mat.node_tree.nodes.new("ShaderNodeTexImage")
            t.image = images[tex]
            mat.node_tree.links.new(t.outputs["Color"], bsdf.inputs["Base Color"])
            bsdf.inputs["Roughness"].default_value = 0.45
            obj.data.materials.append(mat)
        for loc, rotation in (((0, 0, 0), (0, 0, 0)), ((0, 1.4, 1.5), (math.radians(90), 0, 0)),
                              ((-1.8, 0, 1.5), (0, math.radians(90), 0))):
            bpy.ops.mesh.primitive_plane_add(size=12, location=loc, rotation=rotation)
        floor_mat = bpy.data.materials.new("room")
        floor_mat.use_nodes = True
        floor_mat.node_tree.nodes["Principled BSDF"].inputs["Base Color"].default_value = (0.10, 0.075, 0.05, 1)
        for o in scene.objects:
            if o.name.startswith("Plane"):
                o.data.materials.append(floor_mat)
        world = bpy.data.worlds.new("w")
        world.use_nodes = True
        world.node_tree.nodes["Background"].inputs[0].default_value = (0.006, 0.006, 0.008, 1)
        scene.world = world
        flash = link(bpy.data.objects.new("flash", bpy.data.lights.new("flash", "SPOT")))
        flash.data.energy = 220
        flash.data.spot_size = math.radians(80)
        flash.data.shadow_soft_size = 0.04
        cam = link(bpy.data.objects.new("cam", bpy.data.cameras.new("cam")))
        scene.camera = cam
        hx, hy, hz = export_v3.attachment(model, clip, frame, "head")
        head = np.array([hx, -hz, hy])
        center_z = min(1.6, head[2] * 0.55)
        shots = [("", np.array([1.2, -5.2, 1.7]), np.array([0.0, 0.0, center_z]), 35),
                 ("_face", head + np.array([0.22, -0.95, 0.05]), head, 55)]
        for suffix, cam_pos, target, lens in shots:
            cam.location = tuple(cam_pos)
            cam.rotation_euler = look_rotation(target - cam_pos)
            cam.data.lens = lens
            light_pos = cam_pos + np.array([-0.12, 0.0, 0.18])
            flash.data.energy = 220.0 * (np.linalg.norm(target - light_pos) / 5.3) ** 2
            flash.location = tuple(light_pos)
            flash.rotation_euler = look_rotation(target - light_pos)
            scene.render.filepath = os.path.join(PREVIEW_DIR, pose_name.replace(":", "_") + suffix + ".png")
            bpy.ops.render.render(write_still=True)


def look_rotation(direction):
    """Euler rotation pointing a camera (which looks down its -Z) along `direction`."""
    d = np.array(direction, dtype=float)
    d /= np.linalg.norm(d)
    pitch = math.atan2(math.hypot(d[0], d[1]), -d[2])
    yaw = math.atan2(d[1], d[0]) - math.pi / 2
    return (pitch, 0.0, yaw)


if __name__ == "__main__":
    main()
