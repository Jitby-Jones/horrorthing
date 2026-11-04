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

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
ASSETS = os.path.join(ROOT, "common", "src", "main", "resources", "assets", "thewake")
MODEL_DIR = os.path.join(ASSETS, "models", "creature")
TEX_DIR = os.path.join(ASSETS, "textures", "entity")
PREVIEW_DIR = os.path.join(ROOT, "tools", "creature", "previews")
OBJ_DIR = os.path.join(ROOT, "tools", "creature", "export")

# The head is a card: a pale slab, slightly bent, with a face pressed into its front, cut straight
# across at the mouth. Head-local space: origin at HEAD_CENTER, x to the creature's left, y back,
# z up; the face is on the -y side.
HEAD_CENTER = np.array([0.0, -0.090, 3.125])
SLAB = np.array([0.135, 0.020, 0.245])
SPLIT_Z = -0.096
JAW_HINGE_LOCAL = np.array([0.0, 0.016, SPLIT_Z])
# Not level: one eye sits lower than the other.
EYES_LOCAL = [np.array([-0.050, -0.020, 0.086]), np.array([0.053, -0.020, 0.068])]
MOUTH_LOCAL = np.array([0.0, -0.022, SPLIT_Z])

PART_SKIN, PART_TEETH, PART_EYE, PART_MOUTH, PART_NEEDLE, PART_STRAND = 0, 1, 2, 3, 4, 5


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

class Joint:
    def __init__(self, name, pos, radius, parent):
        self.name = name
        self.pos = np.array(pos, dtype=float)
        self.radius = radius if isinstance(radius, tuple) else (radius, radius)
        self.parent = parent


def skeleton():
    joints = []

    def add(name, pos, radius, parent):
        joints.append(Joint(name, pos, radius, parent))

    add("pelvis", (0, 0.000, 1.56), (0.078, 0.058), None)
    # No belly: between the hips and the ribs there is only the spine.
    add("spine1", (0, 0.026, 1.70), 0.027, "pelvis")
    add("spine1b", (0, 0.033, 1.84), 0.024, "spine1")
    add("spine2", (0, 0.033, 1.98), 0.027, "spine1b")
    add("chest", (0, 0.028, 2.15), (0.090, 0.066), "spine2")
    add("upchest", (0, 0.034, 2.35), (0.104, 0.062), "chest")
    add("neck0", (0, 0.040, 2.49), 0.026, "upchest")
    add("neck1", (0, 0.012, 2.69), 0.020, "neck0")
    add("neck2", (0, -0.030, 2.87), 0.019, "neck1")
    add("skull", (0, -0.066, 2.975), 0.016, "neck2")

    for side, s in (("L", 1.0), ("R", -1.0)):
        add(f"clav_{side}", (0.074 * s, 0.040, 2.39), (0.030, 0.028), "upchest")
        add(f"shoulder_{side}", (0.176 * s, 0.050, 2.36), 0.032, f"clav_{side}")
        add(f"uarm_{side}", (0.188 * s, 0.058, 2.06), 0.0145, f"shoulder_{side}")
        add(f"elbow_{side}", (0.200 * s, 0.066, 1.77), 0.024, f"uarm_{side}")
        add(f"farm_{side}", (0.210 * s, 0.050, 1.45), 0.0125, f"elbow_{side}")
        add(f"wrist_{side}", (0.218 * s, 0.030, 1.14), (0.017, 0.014), f"farm_{side}")
        add(f"palm_{side}", (0.224 * s, 0.018, 1.03), (0.028, 0.010), f"wrist_{side}")
        # Fingers far too long, the joints knotted, the bones between them thread-thin.
        for f, (dy, scale) in enumerate(((-0.030, 0.94), (-0.010, 1.0), (0.010, 0.96), (0.029, 0.82))):
            base = np.array([0.226 * s, 0.018 + dy, 0.995])
            spread = dy * 0.3
            segs = (0.175 * scale, 0.150 * scale, 0.115 * scale)
            prev = f"palm_{side}"
            p = base
            add(f"f{f}k0_{side}", p, 0.0080, prev)
            prev = f"f{f}k0_{side}"
            for k, seg in enumerate(segs):
                d = np.array([0.0, spread, -1.0])
                d /= np.linalg.norm(d)
                mid = p + d * seg * 0.5
                end = p + d * seg
                add(f"f{f}m{k}_{side}", mid, 0.0050 - k * 0.0007, prev)
                tip = k == len(segs) - 1
                add(f"f{f}k{k + 1}_{side}", end, 0.0020 if tip else 0.0074 - k * 0.0010, f"f{f}m{k}_{side}")
                prev = f"f{f}k{k + 1}_{side}"
                p = end
        add(f"thumb0_{side}", (0.212 * s, -0.016, 1.10), 0.0078, f"wrist_{side}")
        add(f"thumb1_{side}", (0.208 * s, -0.040, 1.00), 0.0068, f"thumb0_{side}")
        add(f"thumb2_{side}", (0.203 * s, -0.052, 0.91), 0.0050, f"thumb1_{side}")
        add(f"thumb3_{side}", (0.199 * s, -0.058, 0.84), 0.0020, f"thumb2_{side}")

        add(f"hip_{side}", (0.076 * s, 0.000, 1.52), (0.036, 0.034), "pelvis")
        add(f"thigh_{side}", (0.082 * s, -0.010, 1.19), 0.022, f"hip_{side}")
        add(f"knee_{side}", (0.086 * s, -0.020, 0.84), (0.030, 0.028), f"thigh_{side}")
        add(f"shin_{side}", (0.088 * s, 0.000, 0.48), 0.0155, f"knee_{side}")
        add(f"ankle_{side}", (0.089 * s, 0.022, 0.10), (0.018, 0.017), f"shin_{side}")
        add(f"heel_{side}", (0.089 * s, 0.044, 0.030), (0.017, 0.016), f"ankle_{side}")
        add(f"foot_{side}", (0.089 * s, -0.100, 0.028), (0.024, 0.012), f"ankle_{side}")
        add(f"toe_{side}", (0.089 * s, -0.235, 0.016), (0.020, 0.007), f"foot_{side}")
    return joints


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
    axis_y = np.interp(z, [1.56, 1.70, 1.84, 1.98, 2.15, 2.35, 2.49, 2.69, 2.87],
                       [0.0, 0.026, 0.033, 0.033, 0.028, 0.034, 0.040, 0.012, -0.030])
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


def body_pins(joints, seed=17):
    """Long pins driven into it: through a shoulder, the back, an arm. It does not notice them."""
    r = rng(seed)
    by_name = {j.name: j for j in joints}
    bm = bmesh.new()
    anchors = [("shoulder_L", (0.9, 0.5, 0.7), 0.42), ("upchest", (-0.4, 1.0, 0.3), 0.36), ("uarm_R", (-1.0, 0.3, 0.1), 0.30),
               ("chest", (0.6, 0.9, -0.2), 0.30), ("thigh_L", (0.8, 0.4, 0.5), 0.26), ("neck1", (-0.7, 0.8, 0.6), 0.28)]
    for name, direction, length in anchors:
        j = by_name[name]
        parent = by_name[j.parent] if j.parent else j
        t = r.uniform(0.2, 0.8)
        at = parent.pos + (j.pos - parent.pos) * t
        dvec = np.array(direction, dtype=float) + r.normal(0.0, 0.15, 3)
        dvec /= np.linalg.norm(dvec)
        tube(bm, [at - dvec * 0.03, at + dvec * length * 0.5, at + dvec * length], 0.0040, 0.0010, 5)
    mesh = bpy.data.meshes.new("body_pins")
    bm.to_mesh(mesh)
    bm.free()
    return link(bpy.data.objects.new("body_pins", mesh))


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


def card_warp(p):
    """Head-local points -> the card as it is: wider at the top, its sides curling back, the top
    leaning forward over you."""
    p = np.atleast_2d(p).astype(float).copy()
    zn = p[:, 2] / SLAB[2]
    xn = p[:, 0] / SLAB[0]
    p[:, 0] *= 1.0 + 0.11 * zn
    p[:, 1] += 0.016 * xn * xn - 0.022 * np.clip(zn, 0.0, None) ** 2
    return p


def face_relief(x, z):
    """How far (blocks) the face stands out of the card at head-local (x, z); negative is a hollow."""
    d = np.zeros_like(x)
    # A long, real nose: a thin bridge from between the eyes, widening to the tip and wings.
    t = np.clip((0.074 - z) / 0.112, 0.0, 1.0)
    width = 0.0080 + 0.013 * t
    height = 0.005 + 0.021 * t ** 1.6
    along = smoothstep(0.088, 0.066, z) * smoothstep(-0.058, -0.036, z)
    d += height * along * np.exp(-(x / width) ** 2)
    for s in (-1.0, 1.0):
        # Nostril wings, and the nostrils themselves tucked under them.
        d += 0.0075 * np.exp(-((x - s * 0.017) / 0.0095) ** 2 - ((z + 0.038) / 0.009) ** 2)
        d -= 0.0055 * np.exp(-((x - s * 0.0095) / 0.0036) ** 2 - ((z + 0.048) / 0.0028) ** 2)
        # Folds from the nose down past the corners of the mouth.
        fold_x = s * (0.024 + 0.32 * np.clip(-0.040 - z, 0.0, 0.08))
        d -= 0.0024 * np.exp(-((x - fold_x) / 0.0045) ** 2) * smoothstep(-0.036, -0.046, z) * smoothstep(-0.114, -0.098, z)
    # Philtrum, and thin lips either side of the cut, slightly apart.
    d -= 0.0020 * np.exp(-(x / 0.0065) ** 2) * smoothstep(-0.054, -0.062, z) * smoothstep(-0.092, -0.084, z)
    lips = smoothstep(0.048, 0.032, np.abs(x))
    d += 0.0034 * lips * np.exp(-((z - (SPLIT_Z + 0.0068)) / 0.0042) ** 2)
    d += 0.0046 * lips * np.exp(-((z - (SPLIT_Z - 0.0090)) / 0.0050) ** 2)
    # Eyes: deep sockets, wide open, the lids pulled back off them.
    for e in EYES_LOCAL:
        q = np.sqrt(((x - e[0]) / 0.024) ** 2 + ((z - e[2]) / 0.0165) ** 2)
        d -= 0.0135 * smoothstep(1.0, 0.15, q)
        d += 0.0030 * np.exp(-((q - 1.12) / 0.20) ** 2)
        # A brow above; dark bags pouched under.
        d += 0.0042 * np.exp(-((x - e[0]) / 0.030) ** 2 - ((z - e[2] - 0.027) / 0.008) ** 2)
        d += 0.0030 * np.exp(-((x - e[0]) / 0.024) ** 2 - ((z - e[2] + 0.024) / 0.0065) ** 2)
    # Cheekbones, hollows under them, a long chin.
    for s in (-1.0, 1.0):
        d += 0.0038 * np.exp(-((x - s * 0.062) / 0.028) ** 2 - ((z - 0.016) / 0.026) ** 2)
        d -= 0.0032 * np.exp(-((x - s * 0.056) / 0.024) ** 2 - ((z + 0.048) / 0.028) ** 2)
    d += 0.0050 * np.exp(-(x / 0.036) ** 2 - ((z + 0.192) / 0.030) ** 2)
    # The face is pressed into the card, not stuck on: it fades out into flat card.
    region = smoothstep(0.112, 0.086, np.abs(x)) * smoothstep(0.155, 0.125, z) * smoothstep(-0.236, -0.212, z)
    return d * region


def slab(name, z0, z1, cuts, mouth_side):
    """One rounded block of the card between heights z0 and z1, with the face relief on its front.
    The faces on the cut (the inside of the mouth) get material slot 1."""
    bm = bmesh.new()
    bmesh.ops.create_cube(bm, size=2.0)
    bmesh.ops.subdivide_edges(bm, edges=bm.edges[:], cuts=cuts, use_grid_fill=True)
    half = np.array([SLAB[0], SLAB[1], (z1 - z0) / 2.0])
    center = np.array([0.0, 0.0, (z0 + z1) / 2.0])
    radius = np.array([0.012, 0.009, 0.007])
    bm.verts.ensure_lookup_table()
    raw = np.array([v.co[:] for v in bm.verts])
    p = raw * half + center
    lo = center - half + radius
    hi = center + half - radius
    q = np.clip(p, lo, hi)
    dvec = (p - q) / radius
    n = np.linalg.norm(dvec, axis=1)
    rounded = np.where(n[:, None] > 1e-9, q + dvec / np.maximum(n, 1e-9)[:, None] * radius, p)
    # Face relief on the front (-y) surface only.
    front = smoothstep(-SLAB[1] + 0.008, -SLAB[1] + 0.001, rounded[:, 1])
    rounded[:, 1] -= face_relief(rounded[:, 0], rounded[:, 2]) * front
    final = card_warp(rounded)
    for v, c in zip(bm.verts, final):
        v.co = c + HEAD_CENTER
    bm.normal_update()
    bm.faces.ensure_lookup_table()
    for f in bm.faces:
        f.smooth = True
        c = np.array(f.calc_center_median()[:]) - HEAD_CENTER
        nz = f.normal[2]
        on_cut = abs(c[2] - SPLIT_Z) < 0.012
        if on_cut and ((mouth_side == "down" and nz < -0.6) or (mouth_side == "up" and nz > 0.6)):
            f.material_index = 1
    mesh = bpy.data.meshes.new(name)
    bm.to_mesh(mesh)
    bm.free()
    return link(bpy.data.objects.new(name, mesh))


def surface_y(x, z):
    """Head-local y of the front of the card at (x, z), before the warp."""
    return -SLAB[1] - face_relief(np.array([x]), np.array([z]))[0]


def eye_centers():
    out = []
    for e in EYES_LOCAL:
        local = np.array([e[0], surface_y(e[0], e[2]) + 0.0080, e[2]])
        out.append(card_warp(local)[0] + HEAD_CENTER)
    return out


def build_eyes():
    """Small, black, wet. They catch the light; nothing else in the face does."""
    bm = bmesh.new()
    for c in eye_centers():
        res = bmesh.ops.create_uvsphere(bm, u_segments=24, v_segments=16, radius=0.0115)
        for v in res["verts"]:
            v.co = np.array(v.co[:]) + c
    obj = to_object(bm, "eyes")
    return obj


def build_teeth(name, upper, seed):
    """Small, flat, too many: only seen when the card comes apart."""
    r = rng(seed)
    bm = bmesh.new()
    count = 12 if upper else 14
    for i in range(count):
        x = -0.044 + 0.088 * (i + 0.5) / count + r.normal(0.0, 0.0008)
        h = 0.0105 + r.uniform(-0.002, 0.003)
        w = 0.088 / count * 0.82
        y = surface_y(x, SPLIT_Z) + 0.0085
        zc = SPLIT_Z - h * 0.5 + 0.001 if upper else SPLIT_Z + h * 0.5 - 0.001
        tilt = r.normal(0.0, 0.08)
        local = []
        for sx in (-1, 1):
            for sy in (-1, 1):
                for sz in (-1, 1):
                    taper = 0.75 if (sz < 0) == upper else 1.0
                    local.append([x + sx * w * 0.5 * taper + sz * tilt * h * 0.5, y + sy * 0.003, zc + sz * h * 0.5])
        world = card_warp(np.array(local)) + HEAD_CENTER
        vs = [bm.verts.new(c) for c in world]
        for face in ((0, 1, 3, 2), (4, 6, 7, 5), (0, 4, 5, 1), (2, 3, 7, 6), (0, 2, 6, 4), (1, 5, 7, 3)):
            bm.faces.new([vs[k] for k in face])
    obj = to_object(bm, name)
    for poly in obj.data.polygons:
        poly.use_smooth = False
    return obj


def build_needles(seed=23):
    """Pins driven through the card, radiating out of it at every angle."""
    r = rng(seed)
    bm = bmesh.new()
    count = 11
    for i in range(count):
        a = (i + r.uniform(-0.3, 0.3)) / count * 2.0 * math.pi
        # A point near the edge of the card, and a direction out of it, mostly in its plane.
        ex, ez = math.cos(a), math.sin(a)
        start = np.array([ex * SLAB[0] * r.uniform(0.35, 0.8), r.uniform(-0.02, 0.02), 0.04 + ez * SLAB[2] * r.uniform(0.3, 0.75)])
        direction = np.array([ex, r.uniform(-0.6, 0.6), ez * 1.1])
        direction /= np.linalg.norm(direction)
        length = r.uniform(0.17, 0.42)
        pts = [start - direction * 0.02, start + direction * length * 0.5, start + direction * length]
        world = card_warp(np.array(pts)) + HEAD_CENTER
        tube(bm, world, 0.0042, 0.0010, 5)
    # Two driven straight through the face: one high on the forehead, one through a cheek.
    for start, direction, length in ((np.array([0.030, 0.0, 0.170]), np.array([0.25, -1.0, 0.35]), 0.24),
                                     (np.array([-0.072, 0.0, -0.030]), np.array([-0.45, -1.0, -0.25]), 0.20)):
        direction = direction / np.linalg.norm(direction)
        pts = [start - direction * 0.05, start + direction * length * 0.5, start + direction * length]
        tube(bm, card_warp(np.array(pts)) + HEAD_CENTER, 0.0034, 0.0009, 5)
    mesh = bpy.data.meshes.new("needles")
    bm.to_mesh(mesh)
    bm.free()
    return link(bpy.data.objects.new("needles", mesh))


def build_strands(name, z_from, count, length_range, seed, edge_only=False):
    """Pale wet strings hanging off the card's lower edge, too many to be hair."""
    r = rng(seed)
    bm = bmesh.new()
    for i in range(count):
        if edge_only:
            side = 1.0 if i % 2 == 0 else -1.0
            x = side * SLAB[0] * r.uniform(0.85, 0.98)
            z = r.uniform(z_from, z_from + 0.15)
        else:
            x = r.uniform(-0.115, 0.115)
            z = z_from + 0.004
        y = r.uniform(-SLAB[1] * 0.9, SLAB[1] * 0.6)
        length = r.uniform(*length_range)
        n = 7
        pts = []
        sway = r.normal(0.0, 0.012, 2)
        for k in range(n):
            t = k / (n - 1)
            pts.append([x + sway[0] * t * t + r.normal(0.0, 0.002) * t, y + sway[1] * t * t - 0.01 * t, z - length * t])
        world = card_warp(np.array(pts)) + HEAD_CENTER
        tube(bm, world, r.uniform(0.0018, 0.0032), 0.0012, 4)
    mesh = bpy.data.meshes.new(name)
    bm.to_mesh(mesh)
    bm.free()
    return link(bpy.data.objects.new(name, mesh))


def to_object(bm, name):
    for f in bm.faces:
        f.smooth = True
    mesh = bpy.data.meshes.new(name)
    bm.to_mesh(mesh)
    bm.free()
    return link(bpy.data.objects.new(name, mesh))


def build_head():
    upper = slab("head_card", SPLIT_Z, SLAB[2], 56, "down")
    lower = slab("jaw_card", -SLAB[2], SPLIT_Z, 40, "up")
    eyes = build_eyes()
    teeth_upper = build_teeth("teeth_upper", True, 5)
    teeth_lower = build_teeth("teeth_lower", False, 6)
    needles = build_needles()
    strands_jaw = build_strands("strands_jaw", -SLAB[2], 22, (0.10, 0.55), 31)
    strands_head = build_strands("strands_head", SPLIT_Z + 0.02, 6, (0.18, 0.40), 37, edge_only=True)
    # Each part gets its own material (its identity through baking), then one object, so a single
    # unwrap packs them all into one texture without overlaps.
    parts = []
    specs = ((upper, "head_skin", PART_SKIN, "head"), (lower, "jaw_skin", PART_SKIN, "jaw"), (eyes, "eyes", PART_EYE, "head"),
             (teeth_upper, "teeth_upper", PART_TEETH, "head"), (teeth_lower, "teeth_lower", PART_TEETH, "jaw"),
             (needles, "needles", PART_NEEDLE, "head"), (strands_jaw, "strands_jaw", PART_STRAND, "jaw"),
             (strands_head, "strands_head", PART_STRAND, "head"))
    for obj, mat_name, part, group in specs:
        mat = bpy.data.materials.new(mat_name)
        mat.use_nodes = True
        obj.data.materials.append(mat)
        parts.append((mat_name, part, group))
        if obj in (upper, lower):
            inner = "mouth_upper" if obj is upper else "mouth_lower"
            mat2 = bpy.data.materials.new(inner)
            mat2.use_nodes = True
            obj.data.materials.append(mat2)
            parts.append((inner, PART_MOUTH, "head" if obj is upper else "jaw"))
    joined = join([upper, lower, eyes, teeth_upper, teeth_lower, needles, strands_jaw, strands_head], "head_all")
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
    """Black. Not shiny, not matte: like something wet that has dried. The bones show grey where
    they push the skin out; pins are dull steel."""
    N = len(p)
    big = Noise(21).fbm(p * 3.0, 4)
    mid = Noise(22).fbm(p * 14.0, 4)
    fine = Noise(23).fbm(p * 70.0, 3)
    base = srgb_to_linear((17, 15, 16))
    dust = srgb_to_linear((58, 52, 50))
    rot = srgb_to_linear((34, 18, 16))
    col = np.tile(base, (N, 1))
    col = mix(col, rot, smoothstep(0.55, 0.75, big) * 0.6)
    # Raised places (bone under skin) catch a grey film; hollows go to nothing.
    raised = smoothstep(0.86, 1.0, ao) * smoothstep(0.35, 0.75, mid)
    col = mix(col, dust, raised * 0.45)
    col *= (0.85 + 0.3 * fine)[:, None]
    col *= (np.clip(ao, 0.0, 1.0) ** 1.2)[:, None]
    pins = part == PART_NEEDLE
    if pins.any():
        col[pins] = srgb_to_linear((44, 44, 48)) * (0.7 + 0.3 * fine[pins, None])
    return col


def paint_head(p, n, ao, part):
    """An old card, stained and handled, with a face on it that looks like a photograph of a face:
    flesh where the features are, paper everywhere else. The eyes are the only wet thing on it."""
    N = len(p)
    local = p - HEAD_CENTER
    x, y, z = local[:, 0], local[:, 1], local[:, 2]
    big = Noise(41).fbm(p * 9.0, 4)
    mid = Noise(42).fbm(p * 35.0, 4)
    fine = Noise(43).fbm(p * 160.0, 3)
    stain_n = Noise(44).fbm(p * 6.0 + 2.0, 5)
    smudge_n = Noise(47).fbm(p * 18.0 + 5.0, 4)
    card = srgb_to_linear((206, 198, 178))
    yellowed = srgb_to_linear((186, 164, 116))
    grime = srgb_to_linear((104, 92, 76))
    flesh = srgb_to_linear((192, 156, 136))
    shadow_flesh = srgb_to_linear((118, 82, 76))
    col = np.tile(card, (N, 1))
    col = mix(col, yellowed, smoothstep(0.46, 0.70, stain_n) * 0.8)
    ring = smoothstep(0.018, 0.0, np.abs(stain_n - 0.6))
    col = mix(col, grime, ring * 0.45)
    col = mix(col, grime, smoothstep(0.62, 0.78, smudge_n) * 0.35)
    col *= (0.88 + 0.24 * mid)[:, None]
    # Handled edges, darker toward the bottom where it hangs.
    edge = np.maximum(np.abs(x) / (SLAB[0] * 1.12), np.abs(z) / SLAB[2])
    col = mix(col, grime, smoothstep(0.84, 1.0, edge) * 0.6)
    col = mix(col, grime, smoothstep(-0.05, -0.24, z) * 0.25)
    # The face, printed in: flesh fading into paper, strongest where the features are.
    front = smoothstep(-0.2, -0.7, n[:, 1])
    face = np.exp(-((x / 0.080) ** 2) - (((z + 0.025) / 0.150) ** 2)) * front
    col = mix(col, flesh * (0.9 + 0.2 * big[:, None]), np.clip(face * 1.3, 0.0, 0.92))
    # Sockets in deep shadow, bruised lids and bags.
    for e in EYES_LOCAL:
        q = np.sqrt(((x - e[0]) / 0.032) ** 2 + ((z - e[2]) / 0.024) ** 2)
        col = mix(col, shadow_flesh * 0.5, smoothstep(1.35, 0.55, q) * front * 0.85)
    # Nostrils: two dark holes, nothing joining them.
    for s in (-1.0, 1.0):
        hole = np.exp(-((x - s * 0.0095) / 0.0034) ** 2 - ((z + 0.0485) / 0.0024) ** 2)
        col = mix(col, srgb_to_linear((30, 16, 16)), np.clip(hole * 1.3, 0.0, 1.0) * front)
    # Thin lips; between them a sliver of dark.
    lips = smoothstep(0.046, 0.030, np.abs(x)) * smoothstep(0.016, 0.005, np.abs(z - SPLIT_Z)) * front
    col = mix(col, srgb_to_linear((140, 86, 82)), lips * 0.85)
    col = mix(col, srgb_to_linear((26, 8, 8)), smoothstep(0.0032, 0.0, np.abs(z - SPLIT_Z)) * front)
    col *= (0.93 + 0.14 * fine)[:, None]
    col *= (np.clip(ao, 0.0, 1.0) ** 1.1)[:, None]
    teeth = part == PART_TEETH
    if teeth.any():
        t = np.tile(srgb_to_linear((200, 186, 146)), (teeth.sum(), 1))
        t = mix(t, srgb_to_linear((140, 116, 72)), smoothstep(0.45, 0.75, Noise(31).fbm(p[teeth] * 300.0, 3)) * 0.8)
        col[teeth] = t * (np.clip(ao[teeth], 0, 1) ** 1.8)[:, None]
    eye = part == PART_EYE
    if eye.any():
        # A real eye, looking straight out: yellowed white, a small dark iris, a smaller pupil.
        pe = p[eye]
        centres = eye_centers()
        d0 = np.linalg.norm(pe - centres[0], axis=1)
        d1 = np.linalg.norm(pe - centres[1], axis=1)
        c = np.where((d0 < d1)[:, None], centres[0], centres[1])
        dirn = (pe - c) / np.maximum(np.linalg.norm(pe - c, axis=1), 1e-9)[:, None]
        # One eye does not quite look where the other does.
        gaze = np.where((d0 < d1)[:, None], np.array([0.0, -1.0, 0.0]), np.array([0.22, -0.97, 0.05]))
        look = np.sum(dirn * gaze, axis=1)
        sclera = mix(np.tile(srgb_to_linear((214, 204, 182)), (len(pe), 1)), srgb_to_linear((176, 92, 84)),
                     smoothstep(0.45, 0.05, look) * 0.7 * smoothstep(0.4, 0.7, Noise(48).fbm(pe * 400.0, 3)))
        iris = srgb_to_linear((52, 40, 30))
        pupil = srgb_to_linear((4, 3, 3))
        e_col = mix(sclera, iris, smoothstep(0.86, 0.90, look))
        e_col = mix(e_col, pupil, smoothstep(0.955, 0.965, look))
        col[eye] = e_col * (0.45 + 0.55 * ao[eye, None])
    mouth = part == PART_MOUTH
    if mouth.any():
        wet = Noise(45).fbm(p[mouth] * 80.0, 3)
        col[mouth] = srgb_to_linear((44, 10, 12)) * (0.55 + 0.6 * wet[:, None]) * (0.5 + 0.5 * ao[mouth, None])
    pins = part == PART_NEEDLE
    if pins.any():
        col[pins] = srgb_to_linear((64, 64, 68)) * (0.7 + 0.3 * fine[pins, None])
    strands = part == PART_STRAND
    if strands.any():
        wet = Noise(46).fbm(p[strands] * 60.0, 3)
        s_col = mix(np.tile(srgb_to_linear((206, 198, 176)), (strands.sum(), 1)), srgb_to_linear((120, 100, 84)),
                    smoothstep(-0.12, -0.45, z[strands]) * 0.7)
        col[strands] = s_col * (0.8 + 0.3 * wet[:, None])
    return col


def make_texture(name, obj, parts, size, quick, painter, ao_distance):
    pos, nor, ao, part, covered = bake_maps(obj, parts, size, quick, ao_distance)
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

def rot(axis, degrees):
    a = math.radians(degrees)
    c, s = math.cos(a), math.sin(a)
    if axis == "x":
        return np.array([[1, 0, 0], [0, c, -s], [0, s, c]])
    if axis == "y":
        return np.array([[c, 0, s], [0, 1, 0], [-s, 0, c]])
    return np.array([[c, -s, 0], [s, c, 0], [0, 0, 1]])


def euler(rx=0.0, ry=0.0, rz=0.0):
    return rot("z", rz) @ rot("y", ry) @ rot("x", rx)


def about(point, R):
    T = np.eye(4)
    T[:3, 3] = point
    Ti = np.eye(4)
    Ti[:3, 3] = -np.asarray(point)
    Rm = np.eye(4)
    Rm[:3, :3] = R
    return T @ Rm @ Ti


def root_matrix(pose):
    m = np.eye(4)
    if "root" in pose:
        m = about(pose.get("root_pivot", (0.0, 0.0, 1.6)), euler(*pose["root"]))
    if "root_shift" in pose:
        S = np.eye(4)
        S[:3, 3] = pose["root_shift"]
        m = S @ m
    return m


def joint_transforms(joints, pose):
    """Forward kinematics: a rigid transform per joint (rest -> posed), rotations applied about
    each joint at rest orientation, parents first. A bone parent->child moves with the parent."""
    mats = {}
    for j in joints:
        parent = mats[j.parent] if j.parent else root_matrix(pose)
        r = pose.get(j.name)
        local = about(j.pos, euler(*r)) if r is not None else np.eye(4)
        mats[j.name] = parent @ local
    return mats


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


def finger_curl(amount, side, spread=0.0):
    s = 1.0 if side == "L" else -1.0
    pose = {}
    for f in range(4):
        fan = (f - 1.5) * spread
        pose[f"f{f}k0_{side}"] = (fan, amount * 0.45 * s, 0.0)
        pose[f"f{f}k1_{side}"] = (0.0, amount * 0.85 * s, 0.0)
        pose[f"f{f}k2_{side}"] = (0.0, amount * s, 0.0)
    pose[f"thumb1_{side}"] = (0.0, amount * 0.5 * s, 0.0)
    return pose


def merge(*dicts):
    out = {}
    for d in dicts:
        out.update(d)
    return out


# Rotations are (rx, ry, rz) in degrees about the world axes at rest, applied at each joint.
#   rx +: spine/neck bend forward; a hanging limb swings BACK; - swings it forward.
#   ry +: tips toward +X (the creature's left). Left fingers curl with +ry, right with -ry.
#   rz +: turns counter-clockwise seen from above (head turns to its left).
# Extra keys: "jaw" (degrees the card's lower part drops open), "jaw_drop" (blocks it slides down).
HUNCH = {"spine2": (6, 0, 0), "chest": (10, 0, 0), "upchest": (12, 0, 0), "neck0": (34, 0, 0), "neck1": (20, 0, 0),
         "neck2": (-58, 0, 0), "clav_L": (0, 0, -10), "clav_R": (0, 0, 10)}

POSES = {
    # Upright: too tall, the card hung forward on the long neck, tilted. Rarely seen like this indoors.
    "stand": merge(HUNCH, finger_curl(18, "L"), finger_curl(30, "R"),
                   {"neck2": (-56, 16, 8), "shoulder_L": (-6, -3, 0), "shoulder_R": (-4, 2, 0),
                    "elbow_L": (-10, 0, 0), "elbow_R": (-5, 0, 0), "upchest": (12, -3, 0),
                    "hip_L": (-5, 0, 0), "hip_R": (-5, 0, 0), "knee_L": (10, 0, 0), "knee_R": (8, 0, 0),
                    "ankle_L": (-4, 0, 0), "ankle_R": (-3, 0, 0)}),
    # Under a ceiling: bent double, knees bent, neck folded, the card lifted to keep looking at you.
    "stoop": merge(finger_curl(24, "L"), finger_curl(28, "R"),
                   {"spine1": (12, 0, 0), "spine2": (14, 0, 0), "chest": (16, 0, 0), "upchest": (14, 0, 0),
                    "neck0": (18, 0, 0), "neck1": (6, 0, 0), "neck2": (-78, -12, 0),
                    "hip_L": (-34, -4, 0), "hip_R": (-34, 4, 0), "knee_L": (58, 0, 0), "knee_R": (58, 0, 0),
                    "ankle_L": (-24, 0, 0), "ankle_R": (-24, 0, 0),
                    "shoulder_L": (-24, 0, 0), "shoulder_R": (-20, 0, 0), "elbow_L": (-14, 0, 0), "elbow_R": (-10, 0, 0),
                    "root_shift": (0.0, 0.12, -0.22)}),
    # Squatting, knees by its ears, arms hanging between them to the floor, the face tilted up at you.
    "crouch": merge(finger_curl(34, "L"), finger_curl(28, "R"),
                    {"hip_L": (-118, -14, 0), "hip_R": (-118, 14, 0), "knee_L": (150, 0, 0), "knee_R": (150, 0, 0),
                     "ankle_L": (-30, 0, 0), "ankle_R": (-30, 0, 0),
                     "spine1": (22, 0, 0), "spine2": (16, 0, 0), "chest": (8, 0, 0),
                     "neck0": (10, 0, 0), "neck2": (-66, 16, 0),
                     "shoulder_L": (-8, 6, 0), "shoulder_R": (-6, -6, 0), "elbow_L": (-6, 0, 0),
                     "root_shift": (0.0, 0.18, -0.84)}),
    # On all fours, spine bowed, the card raised and turned to look at you.
    "crawl_a": merge(finger_curl(-8, "L", 6), finger_curl(-8, "R", 6),
                     {"root": (80, 0, 0), "root_pivot": (0.0, 0.0, 1.56), "root_shift": (0.0, 0.70, -1.10),
                      "hip_L": (-64, 0, 0), "hip_R": (-84, 0, 0), "knee_L": (84, 0, 0), "knee_R": (96, 0, 0),
                      "ankle_L": (-20, 0, 0), "ankle_R": (-20, 0, 0),
                      "shoulder_L": (-70, 0, 0), "shoulder_R": (-88, 0, 0), "elbow_L": (14, 0, 0), "elbow_R": (24, 0, 0),
                      "wrist_L": (46, 0, 0), "wrist_R": (50, 0, 0),
                      "neck0": (-34, 0, 0), "neck1": (-28, 0, 0), "neck2": (-38, 0, 16)}),
    "crawl_b": merge(finger_curl(-8, "L", 6), finger_curl(-8, "R", 6),
                     {"root": (80, 0, 0), "root_pivot": (0.0, 0.0, 1.56), "root_shift": (0.0, 0.70, -1.10),
                      "hip_L": (-84, 0, 0), "hip_R": (-64, 0, 0), "knee_L": (96, 0, 0), "knee_R": (84, 0, 0),
                      "ankle_L": (-20, 0, 0), "ankle_R": (-20, 0, 0),
                      "shoulder_L": (-88, 0, 0), "shoulder_R": (-70, 0, 0), "elbow_L": (24, 0, 0), "elbow_R": (14, 0, 0),
                      "wrist_L": (50, 0, 0), "wrist_R": (46, 0, 0),
                      "neck0": (-34, 0, 0), "neck1": (-28, 0, 0), "neck2": (-38, 0, -16)}),
    # Leaning out from behind a wall on its right, the long fingers of its right hand curled round
    # the edge at head height.
    "peek_right": merge(finger_curl(64, "R"), finger_curl(20, "L"),
                        {"spine1": (6, -6, 0), "spine2": (6, -7, 0), "chest": (6, -6, 0), "upchest": (4, -4, 0),
                         "neck0": (32, -4, 0), "neck1": (18, 0, 0), "neck2": (-70, -24, 0),
                         "hip_L": (-16, 3, 0), "hip_R": (-14, 5, 0), "knee_L": (34, 0, 0), "knee_R": (30, 0, 0),
                         "ankle_L": (-18, 0, 0), "ankle_R": (-16, 0, 0),
                         "shoulder_R": (-34, 34, 0), "elbow_R": (-108, 0, 0), "wrist_R": (0, 30, 0),
                         "shoulder_L": (8, 0, 0)}),
    "peek_left": merge(finger_curl(64, "L"), finger_curl(20, "R"),
                       {"spine1": (6, 6, 0), "spine2": (6, 7, 0), "chest": (6, 6, 0), "upchest": (4, 4, 0),
                        "neck0": (32, 4, 0), "neck1": (18, 0, 0), "neck2": (-70, 24, 0),
                        "hip_R": (-16, -3, 0), "hip_L": (-14, -5, 0), "knee_R": (34, 0, 0), "knee_L": (30, 0, 0),
                        "ankle_R": (-18, 0, 0), "ankle_L": (-16, 0, 0),
                        "shoulder_L": (-34, -34, 0), "elbow_L": (-108, 0, 0), "wrist_L": (0, -30, 0),
                        "shoulder_R": (8, 0, 0)}),
    # One arm out toward you, fingers loose.
    "reach": merge(HUNCH, finger_curl(4, "L", 8), finger_curl(28, "R"),
                   {"chest": (14, 0, 0), "shoulder_L": (-84, 4, 0), "elbow_L": (-12, 0, 0), "wrist_L": (-8, 0, 0),
                    "shoulder_R": (6, 2, 0), "neck2": (-46, -10, 0), "jaw": 6}),
    # Bent at the waist over a bed, the card hanging face-down just above where your face is.
    "bedside": merge(finger_curl(28, "L"), finger_curl(28, "R"),
                     {"spine1": (30, 0, 0), "spine2": (24, 0, 0), "chest": (18, 0, 0), "upchest": (10, 0, 0),
                      "neck0": (8, 0, 0), "neck2": (2, 10, 0),
                      "shoulder_L": (-20, 0, 0), "shoulder_R": (-24, 0, 0), "elbow_L": (-6, 0, 0), "elbow_R": (-6, 0, 0),
                      "hip_L": (-6, 0, 0), "hip_R": (-6, 0, 0)}),
    # The attack: arms wide, everything forward, and the card coming apart at the mouth.
    "lunge": merge(finger_curl(-14, "L", 10), finger_curl(-14, "R", 10),
                   {"spine1": (12, 0, 0), "spine2": (10, 0, 0), "chest": (8, 0, 0), "neck0": (18, 0, 0), "neck2": (-60, 0, 0),
                    "shoulder_L": (-80, -40, 0), "shoulder_R": (-80, 40, 0), "elbow_L": (-24, 0, 0), "elbow_R": (-24, 0, 0),
                    "hip_L": (-24, 0, 0), "knee_L": (34, 0, 0), "hip_R": (12, 0, 0), "jaw": 58, "jaw_drop": 0.035}),
    # Flat on its back, the card turned toward you.
    "lie": merge(finger_curl(12, "L"), finger_curl(16, "R"),
                 {"root": (-90, 0, 0), "root_pivot": (0.0, 0.0, 0.0), "root_shift": (0.0, 0.0, 0.08),
                  "neck2": (-6, 0, 70), "shoulder_L": (0, -8, 0), "shoulder_R": (0, 8, 0)}),
    # Stop-motion walking frames.
    "walk_a": merge(HUNCH, finger_curl(22, "L"), finger_curl(26, "R"),
                    {"spine2": (14, 0, 0), "chest": (22, 0, 0), "neck2": (-70, 0, 0), "hip_L": (-34, 0, 0), "knee_L": (44, 0, 0),
                     "hip_R": (10, 0, 0), "knee_R": (30, 0, 0), "ankle_R": (-12, 0, 0), "shoulder_L": (10, 0, 0),
                     "shoulder_R": (-20, 0, 0), "root_shift": (0.0, 0.0, -0.11)}),
    "walk_b": merge(HUNCH, finger_curl(22, "L"), finger_curl(26, "R"),
                    {"spine2": (14, 0, 0), "chest": (22, 0, 0), "neck2": (-70, 0, 0), "hip_R": (-34, 0, 0), "knee_R": (44, 0, 0),
                     "hip_L": (10, 0, 0), "knee_L": (30, 0, 0), "ankle_L": (-12, 0, 0), "shoulder_R": (10, 0, 0),
                     "shoulder_L": (-20, 0, 0), "root_shift": (0.0, 0.0, -0.11)}),
}


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


def write_bin(path, groups, poses):
    """Binary format, big-endian (Java DataInputStream), gzip-compressed:

        "WAKEMESH" int version(2) int groupCount
        per group: utf name, utf texture, byte rigid, int vertexCount, int indexCount,
                   float[2n] uv, byte[n] part, (ushort|int)[indexCount] indices (ushort if n < 65536),
                   if rigid: positions (as below) once, in rest pose
        int poseCount
        per pose:  utf name, float[PIVOT_FLOATS] pivots,
                   per group: rigid: float[12] its 3x4 transform (row-major)
                              else:  positions: float[3] min, float[3] max, ushort[3n] (quantised to
                                     the bounds), byte[3n] normals (x127)
    All in model space: blocks, +Y up, facing +Z, feet at the origin.
    """
    out = bytearray()
    out += b"WAKEMESH" + struct.pack(">ii", 2, len(groups))
    for g in groups:
        out += utf(g["name"]) + utf(g["texture"]) + struct.pack(">b", 1 if g["rigid"] else 0)
        n = len(g["uv"])
        out += struct.pack(">ii", n, g["tris"].size)
        out += np.asarray(g["uv"], dtype=">f4").tobytes()
        out += np.asarray(g["part"], dtype=np.int8).tobytes()
        idx_type = ">u2" if n < 65536 else ">i4"
        out += np.asarray(g["tris"].reshape(-1), dtype=idx_type).tobytes()
        if g["rigid"]:
            out += pack_positions(g["rest"], g["rest_normals"])
    out += struct.pack(">i", len(poses))
    for pose in poses:
        out += utf(pose["name"])
        out += np.asarray(pose["pivots"], dtype=">f4").tobytes()
        for g, data in zip(groups, pose["groups"]):
            if g["rigid"]:
                out += np.asarray(data[:3, :4], dtype=">f4").tobytes()
            else:
                out += pack_positions(*data)
    with gzip.open(path, "wb", compresslevel=9) as f:
        f.write(bytes(out))
    return len(out)


def pack_positions(pos, nor):
    lo = pos.min(axis=0) - 1e-4
    hi = pos.max(axis=0) + 1e-4
    q = np.round((pos - lo) / (hi - lo) * 65535.0).astype(">u2")
    return (np.asarray(lo, dtype=">f4").tobytes() + np.asarray(hi, dtype=">f4").tobytes() + q.tobytes()
            + np.clip(np.round(nor * 127.0), -127, 127).astype(np.int8).tobytes())


TO_MC = np.array([[1.0, 0.0, 0.0, 0.0], [0.0, 0.0, 1.0, 0.0], [0.0, -1.0, 0.0, 0.0], [0.0, 0.0, 0.0, 1.0]])


def matrix_to_mc(m):
    return TO_MC @ m @ TO_MC.T


def utf(s):
    b = s.encode("utf-8")
    return struct.pack(">H", len(b)) + b


PIVOT_NAMES = ["neck", "head", "headRight", "headUp", "headForward", "jawHinge", "eyeLeft", "eyeRight", "mouth"]


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
    preview_poses = [p for p in args.poses.split(",") if p] or list(POSES)
    if args.previews_only:
        render_previews(preview_poses)
        return

    bpy.ops.wm.read_factory_settings(use_empty=True)
    joints = skeleton()
    print("sculpting")
    body = build_body(joints)
    head_all, head_parts = build_head()
    body_mat = bpy.data.materials.new("body_skin")
    body_mat.use_nodes = True
    body.data.materials.append(body_mat)

    print("texturing")
    tex_size = 512 if args.quick else 1024
    unwrap([body], 0.003)
    unwrap([head_all], 0.004)
    make_texture("creature_body", body, {"body_skin": PART_SKIN}, tex_size, args.quick, paint_body, 0.08)
    make_texture("creature_head", head_all, {name: part for name, part, _ in head_parts}, tex_size, args.quick, paint_head, 0.03)

    print("decimating")
    body = decimate(body, 0.24)
    # Pins go in after decimation (it would eat them). Their UVs land on the body's black.
    pins = body_pins(joints)
    pins.data.uv_layers.new()
    body = join([body, pins], "body")
    set_part_attribute(body, PART_SKIN)
    pieces = separate_by_material(head_all)
    groups_of = {"head": [], "jaw": []}
    for name, part, group in head_parts:
        obj = pieces[name]
        if part == PART_SKIN:
            obj = decimate(obj, 0.16 if group == "head" else 0.22)
        elif part == PART_MOUTH:
            obj = decimate(obj, 0.1)
        set_part_attribute(obj, part)
        groups_of[group].append(obj)
    head = join(groups_of["head"], "head")
    jaw = join(groups_of["jaw"], "jaw")

    print("rigging")
    rig = build_rig(joints, body)

    render = {}
    for name, obj in (("body", body), ("head", head), ("jaw", jaw)):
        render[name] = mesh_render_data(obj)
    rest_co = {"head": get_co(head.data), "jaw": get_co(jaw.data)}
    groups = []
    for name in ("body", "head", "jaw"):
        vidx, uv, part, tris = render[name]
        texture = "thewake:textures/entity/creature_" + ("body" if name == "body" else "head") + ".png"
        g = {"name": name, "texture": texture, "uv": uv, "part": part, "tris": tris, "rigid": name != "body"}
        if g["rigid"]:
            g["rest"] = to_mc(rest_co[name][vidx])
            g["rest_normals"] = smooth_normals_by_position(g["rest"], tris)
        groups.append(g)

    by_name = {j.name: j for j in joints}
    jaw_hinge = HEAD_CENTER + JAW_HINGE_LOCAL
    eyes_rest = eye_centers()
    mouth_rest = card_warp(MOUTH_LOCAL)[0] + HEAD_CENTER
    exported = []
    pivots_json = {}
    os.makedirs(MODEL_DIR, exist_ok=True)
    if args.obj:
        os.makedirs(OBJ_DIR, exist_ok=True)
    for pose_name, pose in POSES.items():
        mats = apply_pose(rig, joints, pose)
        depsgraph = bpy.context.evaluated_depsgraph_get()
        ev = body.evaluated_get(depsgraph)
        m = ev.to_mesh()
        body_co = get_co(m)
        ev.to_mesh_clear()
        head_m = mats["neck2"]
        J = np.eye(4)
        if pose.get("jaw"):
            J = about(jaw_hinge, rot("x", pose["jaw"]))  # + opens: the lower card swings down and back
        if pose.get("jaw_drop"):
            S = np.eye(4)
            S[2, 3] = -pose["jaw_drop"]
            J = S @ J
        rigid = {"head": matrix_to_mc(head_m), "jaw": matrix_to_mc(head_m @ J)}
        pose_groups = []
        obj_groups = []
        all_pos = []
        for g, name in zip(groups, ("body", "head", "jaw")):
            if g["rigid"]:
                M = rigid[name]
                pose_groups.append(M)
                pos = transform_points(M, g["rest"])
                nor = g["rest_normals"] @ M[:3, :3].T
            else:
                vidx = render[name][0]
                pos = to_mc(body_co[vidx])
                nor = smooth_normals_by_position(pos, g["tris"])
                pose_groups.append((pos, nor))
            all_pos.append(pos)
            obj_groups.append((name, pos, nor, g["uv"], g["tris"]))
        R = head_m[:3, :3]
        piv = {
            "neck": to_mc(transform_points(head_m, by_name["neck2"].pos))[0],
            "head": to_mc(transform_points(head_m, HEAD_CENTER))[0],
            "headRight": to_mc(R @ np.array([1.0, 0.0, 0.0]))[0],
            "headUp": to_mc(R @ np.array([0.0, 0.0, 1.0]))[0],
            "headForward": to_mc(R @ np.array([0.0, -1.0, 0.0]))[0],
            "jawHinge": to_mc(transform_points(head_m, jaw_hinge))[0],
            "eyeLeft": to_mc(transform_points(head_m, eyes_rest[0]))[0],
            "eyeRight": to_mc(transform_points(head_m, eyes_rest[1]))[0],
            "mouth": to_mc(transform_points(head_m @ J, mouth_rest))[0],
        }
        # Height of the solid creature (pins and strings may poke into a ceiling; the rest may not).
        solid = [pos[(g["part"] != PART_NEEDLE) & (g["part"] != PART_STRAND)] for pos, g in zip(all_pos, groups)]
        height = float(np.vstack([q for q in solid if len(q)])[:, 1].max())
        flat = np.concatenate([piv[k] for k in PIVOT_NAMES] + [np.array([height])])
        exported.append({"name": pose_name, "pivots": flat, "groups": pose_groups})
        pivots_json[pose_name] = {k: np.round(v, 4).tolist() for k, v in piv.items()}
        pivots_json[pose_name]["height"] = round(height, 3)
        if args.obj:
            write_obj(os.path.join(OBJ_DIR, pose_name + ".obj"), obj_groups)
        print(f"  pose {pose_name}: height {height:.2f}")
    size = write_bin(os.path.join(MODEL_DIR, "creature.bin.gz"), groups, exported)
    with open(os.path.join(MODEL_DIR, "poses.json"), "w") as f:
        json.dump({"version": 1, "pivotOrder": PIVOT_NAMES + ["height"], "poses": pivots_json}, f, indent=1)
    tri_total = sum(g["tris"].shape[0] for g in groups)
    vert_total = sum(len(g["uv"]) for g in groups)
    print(f"exported {len(exported)} poses, {vert_total} vertices, {tri_total} triangles, {size / 1e6:.2f} MB raw")
    if args.previews:
        render_previews(preview_poses)


# ---------------------------------------------------------------------------------------------
# Previews: read the exported binary back and render it
# ---------------------------------------------------------------------------------------------

def read_bin(path):
    with gzip.open(path, "rb") as f:
        data = f.read()
    off = 0

    def take(fmt):
        nonlocal off
        v = struct.unpack_from(fmt, data, off)
        off += struct.calcsize(fmt)
        return v

    def take_utf():
        nonlocal off
        (n,) = take(">H")
        s = data[off:off + n].decode()
        off += n
        return s

    def take_arr(dtype, count):
        nonlocal off
        a = np.frombuffer(data, dtype=dtype, count=count, offset=off)
        off += a.nbytes
        return a

    def take_positions(n):
        lo = take_arr(">f4", 3)
        hi = take_arr(">f4", 3)
        q = take_arr(">u2", n * 3).reshape(-1, 3).astype(np.float64)
        nor = take_arr(np.int8, n * 3).reshape(-1, 3) / 127.0
        return lo + q / 65535.0 * (hi - lo), nor

    assert data[:8] == b"WAKEMESH"
    off = 8
    version, group_count = take(">ii")
    assert version == 2, version
    groups = []
    for _ in range(group_count):
        name = take_utf()
        texture = take_utf()
        (rigid,) = take(">b")
        n, ni = take(">ii")
        uv = take_arr(">f4", n * 2).reshape(-1, 2)
        part = take_arr(np.int8, n)
        tris = take_arr(">u2" if n < 65536 else ">i4", ni).reshape(-1, 3)
        g = {"name": name, "texture": texture, "uv": uv, "part": part, "tris": tris, "n": n, "rigid": bool(rigid)}
        if rigid:
            g["rest"], g["rest_normals"] = take_positions(n)
        groups.append(g)
    (pose_count,) = take(">i")
    poses = {}
    for _ in range(pose_count):
        name = take_utf()
        pivots = take_arr(">f4", 3 * len(PIVOT_NAMES) + 1)
        pg = []
        for g in groups:
            if g["rigid"]:
                M = np.eye(4)
                M[:3, :4] = take_arr(">f4", 12).reshape(3, 4)
                pg.append((transform_points(M, g["rest"]), g["rest_normals"] @ M[:3, :3].T))
            else:
                pg.append(take_positions(g["n"]))
        poses[name] = {"pivots": pivots, "groups": pg}
    assert off == len(data), (off, len(data))
    return groups, poses


def render_previews(pose_names):
    """Cycles renders: a full view in a blocky room corner and a face close-up, lit by one harsh
    light beside the camera, like a flash photo in a dark room."""
    groups, poses = read_bin(os.path.join(MODEL_DIR, "creature.bin.gz"))
    os.makedirs(PREVIEW_DIR, exist_ok=True)
    for pose_name in pose_names:
        bpy.ops.wm.read_factory_settings(use_empty=True)
        scene = bpy.context.scene
        scene.render.engine = "CYCLES"
        scene.cycles.device = "CPU"
        scene.cycles.samples = 48
        scene.render.resolution_x = 600
        scene.render.resolution_y = 800
        scene.view_settings.view_transform = "Standard"
        pose = poses[pose_name]
        images = {}
        for g, (pos, nor) in zip(groups, pose["groups"]):
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
        hx, hy, hz = pose["pivots"][3:6]
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
            scene.render.filepath = os.path.join(PREVIEW_DIR, pose_name + suffix + ".png")
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
