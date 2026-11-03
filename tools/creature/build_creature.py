#!/usr/bin/env python3
"""
Builds The Wake's creature in headless Blender and exports it for the mod.

    pip install bpy==5.2.3 numpy pillow      # Blender as a Python module (Python 3.13)
    python tools/creature/build_creature.py [--quick] [--previews] [--obj]
    python tools/creature/build_creature.py --previews-only --poses stand,lunge

Pipeline: a skin-modifier skeleton for the body, sculpted in numpy (ribs, a sunken belly,
vertebrae, collarbones, shoulder blades, hip bones, neck tendons, knobbed joints); a head sculpted
from a dense sphere (deep sockets, no nose, a lipless ear-to-ear grin, a hinged jaw, two rows of
teeth); position/normal/AO maps baked with Cycles and painted in numpy; decimated; rigged with
bone-heat weights and posed with dual-quaternion skinning.

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

HEAD_CENTER = np.array([0.0, -0.080, 3.13])
HEAD_SCALE = np.array([0.146, 0.165, 0.222])
MOUTH_HALF = 0.66
JAW_HINGE_UNIT = np.array([0.0, 0.10, -0.06])

PART_SKIN, PART_TEETH, PART_EYE, PART_MOUTH = 0, 1, 2, 3


def rng(seed):
    return np.random.default_rng(seed)


def smoothstep(e0, e1, x):
    t = np.clip((x - e0) / (e1 - e0), 0.0, 1.0)
    return t * t * (3.0 - 2.0 * t)


def gauss(x, sigma):
    return np.exp(-(x * x) / (2.0 * sigma * sigma))


# ---------------------------------------------------------------------------------------------
# Skeleton. Some joints only shape the skin (no pose ever rotates them): they make the limbs
# thin between knobbed joints, the way a starved arm looks.
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

    add("pelvis", (0, 0.000, 1.60), (0.112, 0.080), None)
    add("spine1", (0, 0.016, 1.78), (0.066, 0.050), "pelvis")
    add("spine2", (0, 0.024, 1.98), (0.106, 0.076), "spine1")
    add("chest", (0, 0.030, 2.21), (0.130, 0.090), "spine2")
    add("upchest", (0, 0.040, 2.43), (0.118, 0.080), "chest")
    add("neck0", (0, 0.038, 2.59), 0.048, "upchest")
    add("neck1", (0, 0.008, 2.77), 0.036, "neck0")
    add("neck2", (0, -0.028, 2.93), 0.035, "neck1")
    add("skull", (0, -0.060, 3.04), 0.028, "neck2")

    for side, s in (("L", 1.0), ("R", -1.0)):
        add(f"clav_{side}", (0.085 * s, 0.046, 2.46), (0.044, 0.040), "upchest")
        add(f"shoulder_{side}", (0.212 * s, 0.058, 2.42), 0.050, f"clav_{side}")
        add(f"uarm_{side}", (0.228 * s, 0.066, 2.12), 0.027, f"shoulder_{side}")
        add(f"elbow_{side}", (0.243 * s, 0.078, 1.83), 0.039, f"uarm_{side}")
        add(f"farm_{side}", (0.258 * s, 0.060, 1.52), 0.023, f"elbow_{side}")
        add(f"wrist_{side}", (0.270 * s, 0.034, 1.22), (0.027, 0.023), f"farm_{side}")
        add(f"palm_{side}", (0.280 * s, 0.020, 1.10), (0.040, 0.015), f"wrist_{side}")
        # Fingers hang down from the palm, lined up front (index) to back (little finger). Far
        # too long; knuckles swollen, the bones between them thin.
        for f, (dy, scale) in enumerate(((-0.040, 0.95), (-0.013, 1.0), (0.014, 0.96), (0.040, 0.80))):
            base = np.array([0.283 * s, 0.020 + dy, 1.062])
            spread = dy * 0.25
            segs = (0.165 * scale, 0.140 * scale, 0.105 * scale)
            prev = f"palm_{side}"
            p = base
            add(f"f{f}k0_{side}", p, 0.0118, prev)
            prev = f"f{f}k0_{side}"
            for k, seg in enumerate(segs):
                d = np.array([0.0, spread, -1.0])
                d /= np.linalg.norm(d)
                mid = p + d * seg * 0.5
                end = p + d * seg
                add(f"f{f}m{k}_{side}", mid, 0.0084 - k * 0.0012, prev)
                tip = k == len(segs) - 1
                add(f"f{f}k{k + 1}_{side}", end, 0.0030 if tip else 0.0108 - k * 0.0014, f"f{f}m{k}_{side}")
                prev = f"f{f}k{k + 1}_{side}"
                p = end
        add(f"thumb0_{side}", (0.266 * s, -0.018, 1.17), 0.0118, f"wrist_{side}")
        add(f"thumb1_{side}", (0.262 * s, -0.046, 1.07), 0.0105, f"thumb0_{side}")
        add(f"thumb2_{side}", (0.256 * s, -0.060, 0.985), 0.0080, f"thumb1_{side}")
        add(f"thumb3_{side}", (0.252 * s, -0.068, 0.915), 0.0030, f"thumb2_{side}")

        add(f"hip_{side}", (0.106 * s, 0.000, 1.55), (0.064, 0.060), "pelvis")
        add(f"thigh_{side}", (0.114 * s, -0.012, 1.21), 0.045, f"hip_{side}")
        add(f"knee_{side}", (0.120 * s, -0.026, 0.86), (0.048, 0.046), f"thigh_{side}")
        add(f"shin_{side}", (0.122 * s, 0.000, 0.50), 0.029, f"knee_{side}")
        add(f"ankle_{side}", (0.123 * s, 0.026, 0.11), (0.030, 0.028), f"shin_{side}")
        add(f"heel_{side}", (0.123 * s, 0.052, 0.034), (0.028, 0.026), f"ankle_{side}")
        add(f"foot_{side}", (0.123 * s, -0.110, 0.032), (0.040, 0.020), f"ankle_{side}")
        add(f"toe_{side}", (0.123 * s, -0.250, 0.020), (0.034, 0.010), f"foot_{side}")
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
    co = get_co(body.data)
    nor = get_normals(body.data)
    label, segments = nearest_segment(joints, co)
    seg_child = np.array([b for (_, b) in segments])
    child = seg_child[label]
    torso = np.isin(child, ["spine1", "spine2", "chest", "upchest", "clav_L", "clav_R"])
    neck = np.isin(child, ["neck0", "neck1", "neck2"])
    pelvis = (np.isin(child, ["hip_L", "hip_R"]) & (co[:, 2] > 1.45)) | ((child == "spine1") & (co[:, 2] < 1.70))
    x, y, z = co[:, 0], co[:, 1], co[:, 2]
    # Angle around the spine: 0 at the front, pi at the back.
    axis_y = np.interp(z, [1.6, 1.78, 1.98, 2.21, 2.43, 2.59, 2.77, 2.93], [0.0, 0.016, 0.024, 0.030, 0.040, 0.038, 0.008, -0.028])
    theta = np.arctan2(x, -(y - axis_y))
    front = np.cos(theta)
    d = np.zeros(len(co))

    # Ribs: sharp ridges sloping down toward the front, sunken between.
    rc = z + 0.07 * front
    ribs_zone = torso * smoothstep(1.93, 2.02, rc) * smoothstep(2.50, 2.42, rc)
    costal = (front > 0.0) & (z < 2.06 + 0.30 * np.abs(theta))
    near_sternum = smoothstep(0.10, 0.30, np.abs(theta))
    ridge = (0.5 + 0.5 * np.cos(2.0 * math.pi * (rc - 1.95) / 0.052)) ** 4
    ribs = ribs_zone * near_sternum * (~costal)
    d += ribs * (0.0085 * ridge - 0.0050 * (1.0 - ridge))
    # The costal margin: the bottom edge of the ribcage stands out over a belly sucked in under it.
    margin_line = 2.06 + 0.30 * np.abs(theta)
    d += 0.007 * torso * (front > 0.0) * gauss(z - margin_line, 0.012)
    belly = torso * smoothstep(0.0, 0.5, front) * smoothstep(1.70, 1.80, z) * smoothstep(0.02, -0.05, z - margin_line)
    d -= 0.026 * belly
    # Sternum.
    d += 0.005 * torso * gauss(theta, 0.10) * smoothstep(2.08, 2.16, z) * smoothstep(2.48, 2.42, z)
    # Vertebrae down the back.
    back = gauss(np.abs(theta) - math.pi, 0.16)
    knobs = np.clip(np.cos(2.0 * math.pi * (z - 1.66) / 0.062), 0.0, None) ** 2
    d += 0.011 * back * knobs * (torso | pelvis | neck) * smoothstep(1.62, 1.70, z)
    # Shoulder blades: raised plates either side of the spine.
    for s in (-1.0, 1.0):
        blade = gauss(theta - s * 2.35, 0.32) * gauss(z - 2.30, 0.075) * torso
        edge = gauss(theta - s * 2.62, 0.08) * gauss(z - 2.30, 0.09) * torso
        d += 0.012 * blade + 0.006 * edge
    # Collarbones, the hollows above them.
    clav_line = 2.455 + 0.05 * np.abs(np.sin(theta))
    cl = (torso | neck) * (front > 0.3) * smoothstep(0.12, 0.35, np.abs(theta))
    d += 0.010 * cl * gauss(z - clav_line, 0.010)
    d -= 0.010 * cl * gauss(z - clav_line - 0.045, 0.018)
    # Hip bones.
    for s in (-1.0, 1.0):
        d += 0.014 * gauss(theta - s * 0.95, 0.22) * gauss(z - 1.66, 0.035) * (torso | pelvis)
    # Neck: two tendons in a V down to the sternum, and the windpipe.
    tz = np.clip((z - 2.60) / 0.33, 0.0, 1.0)
    tendon_theta = 0.18 + 1.0 * tz
    d += 0.0065 * neck * gauss(np.abs(theta) - tendon_theta, 0.14)
    d += 0.0060 * neck * gauss(theta, 0.18) * gauss(z - 2.76, 0.03)
    d -= 0.0050 * neck * gauss(theta, 0.15) * gauss(z - 2.63, 0.02)
    # Skin that is not smooth: shallow lumps.
    lumps = Noise(11)
    d += 0.0016 * (lumps.fbm(co * 38.0, 3) - 0.5)
    set_co(body.data, co + nor * d[:, None])


# ---------------------------------------------------------------------------------------------
# Head
# ---------------------------------------------------------------------------------------------

def mouth_line(x):
    return -0.33 + 0.36 * x * x


def head_radius(d):
    """Displacement of the unit-sphere direction `d` (n,3): the radius of the head surface."""
    x, y, z = d[:, 0], d[:, 1], d[:, 2]
    front = smoothstep(-0.05, -0.55, y)
    r = 1.0 - 0.16 * smoothstep(0.55, 1.0, -y)  # a flatter, mask-like face
    for s in (-1.0, 1.0):
        q = np.sqrt(((x - s * 0.33) / 0.165) ** 2 + ((z - 0.13) / 0.140) ** 2)
        r -= 0.34 * front * smoothstep(1.0, 0.20, q)
        r += 0.030 * front * gauss(q - 1.12, 0.12)
        # Cheekbones, with the cheeks sunk in under them.
        r += 0.050 * front * np.exp(-(((x - s * 0.50) / 0.10) ** 2 + ((z + 0.04) / 0.07) ** 2))
        r -= 0.090 * front * np.exp(-(((x - s * 0.47) / 0.12) ** 2 + ((z + 0.25) / 0.11) ** 2))
        # Temples.
        r -= 0.060 * np.exp(-(((x - s * 0.82) / 0.13) ** 2 + ((z - 0.20) / 0.15) ** 2)) * smoothstep(0.35, -0.25, y)
        # The angle of the jaw, under the skin.
        r += 0.040 * np.exp(-(((x - s * 0.62) / 0.12) ** 2 + ((z + 0.55) / 0.10) ** 2)) * smoothstep(0.3, -0.3, y)
        # No nose: two slits.
        r -= 0.085 * front * np.exp(-(((x - s * 0.055) / 0.026) ** 2 + ((z + 0.07) / 0.060) ** 2))
    # Brow.
    r += 0.045 * front * gauss(z - 0.31, 0.05) * smoothstep(0.62, 0.40, np.abs(x))
    # The grin: a recessed band, ear to ear, the lips pulled back off it.
    zm = mouth_line(np.clip(x, -0.75, 0.75))
    span = smoothstep(MOUTH_HALF + 0.05, MOUTH_HALF - 0.07, np.abs(x)) * front
    band = smoothstep(0.090, 0.055, np.abs(z - zm))
    r -= 0.11 * span * band
    r += 0.022 * span * gauss(np.abs(z - zm) - 0.100, 0.025)
    # A long, narrow chin.
    r += 0.03 * front * gauss(z + 0.78, 0.10) * gauss(x, 0.25)
    return r


def head_point(d):
    """Unit directions -> head-space points (blocks)."""
    p = d * head_radius(d)[:, None]
    taper = np.where(p[:, 2] < 0.0, 1.0 + 0.30 * p[:, 2], 1.0 + 0.04 * p[:, 2])
    p[:, 0] *= taper
    p[:, 2] *= np.where(p[:, 2] < 0.0, 1.14, 1.0 - 0.20 * smoothstep(0.2, 0.95, p[:, 2]))
    return HEAD_CENTER + p * HEAD_SCALE


def face_point(x, z, inset=1.0):
    """Head-space point on the face at front coordinates (x, z), pushed in by `inset`."""
    y = -math.sqrt(max(0.0, 1.0 - x * x - z * z))
    d = np.array([[x, y, z]])
    d /= np.linalg.norm(d)
    p = head_point(d)[0]
    return HEAD_CENTER + (p - HEAD_CENTER) * inset


def in_jaw(d):
    """The jaw: below the grin, and from the corners of the mouth back to a hinge by the ear."""
    x, y, z = d[:, 0], d[:, 1], d[:, 2]
    phi = np.abs(np.arctan2(x, -y))
    corner_phi = 0.74
    split = np.where(phi < corner_phi, mouth_line(np.clip(x, -0.75, 0.75)), 0.0)
    corner_z = mouth_line(MOUTH_HALF)
    side = np.interp(phi, [corner_phi, 1.35, 1.55, 1.85], [corner_z, -0.08, -0.55, -0.98])
    split = np.where(phi >= corner_phi, side, split)
    return z < split


def build_head():
    bm = bmesh.new()
    bmesh.ops.create_uvsphere(bm, u_segments=176, v_segments=112, radius=1.0)
    bm.verts.ensure_lookup_table()
    dirs = np.array([v.co[:] for v in bm.verts])
    dirs /= np.linalg.norm(dirs, axis=1)[:, None]
    shaped = head_point(dirs)
    jaw_mask = in_jaw(dirs)
    for v, p in zip(bm.verts, shaped):
        v.co = p
    jaw_faces = {f.index for f in bm.faces if sum(jaw_mask[v.index] for v in f.verts) >= 3}
    jaw_bm = bm.copy()
    bm.faces.ensure_lookup_table()
    jaw_bm.faces.ensure_lookup_table()
    bmesh.ops.delete(bm, geom=[f for f in bm.faces if f.index in jaw_faces], context="FACES")
    bmesh.ops.delete(jaw_bm, geom=[f for f in jaw_bm.faces if f.index not in jaw_faces], context="FACES")
    head = to_object(bm, "head_skin")
    jaw = to_object(jaw_bm, "jaw_skin")
    teeth_upper = build_teeth("teeth_upper", upper=True, seed=5)
    teeth_lower = build_teeth("teeth_lower", upper=False, seed=6)
    eyes = build_eyes()
    mouth_upper = ellipsoid("mouth_upper", HEAD_CENTER + np.array([0.0, -0.032, -0.032]), (0.084, 0.100, 0.056), (20, 14))
    mouth_lower = ellipsoid("mouth_lower", HEAD_CENTER + np.array([0.0, -0.028, -0.110]), (0.074, 0.086, 0.046), (20, 14))
    # Every part gets its own material (its identity through baking), then one object, so a
    # single unwrap packs all of them into one texture without overlaps.
    parts = []
    for obj, part, group in ((head, PART_SKIN, "head"), (jaw, PART_SKIN, "jaw"), (teeth_upper, PART_TEETH, "head"),
                             (teeth_lower, PART_TEETH, "jaw"), (eyes, PART_EYE, "head"), (mouth_upper, PART_MOUTH, "head"),
                             (mouth_lower, PART_MOUTH, "jaw")):
        mat = bpy.data.materials.new(obj.name)
        mat.use_nodes = True
        obj.data.materials.append(mat)
        parts.append((obj.name, part, group))
    joined = join([head, jaw, teeth_upper, teeth_lower, eyes, mouth_upper, mouth_lower], "head_all")
    return joined, parts


def to_object(bm, name):
    for f in bm.faces:
        f.smooth = True
    mesh = bpy.data.meshes.new(name)
    bm.to_mesh(mesh)
    bm.free()
    return link(bpy.data.objects.new(name, mesh))


def ellipsoid(name, center, radii, segments):
    bm = bmesh.new()
    bmesh.ops.create_uvsphere(bm, u_segments=segments[0], v_segments=segments[1], radius=1.0)
    for v in bm.verts:
        v.co = center + np.array(v.co[:]) * np.array(radii)
    return to_object(bm, name)


def add_box(bm, center, size, right, up, out, taper=0.8):
    """A tooth: a box narrowing toward its biting edge (the -up end)."""
    corners = []
    for sz in (-1, 1):
        w = 1.0 if sz > 0 else taper
        for sx in (-1, 1):
            for sy in (-1, 1):
                corners.append(center + right * sx * size[0] * 0.5 * w + out * sy * size[1] * 0.5 * (0.7 + 0.3 * w)
                               + up * sz * size[2] * 0.5)
    vs = [bm.verts.new(c) for c in corners]
    for face in ((0, 1, 3, 2), (4, 6, 7, 5), (0, 4, 5, 1), (2, 3, 7, 6), (0, 2, 6, 4), (1, 5, 7, 3)):
        bm.faces.new([vs[i] for i in face])


def build_teeth(name, upper, seed):
    """Small, flat, human teeth, far too many of them, packed into the grin."""
    r = rng(seed)
    bm = bmesh.new()
    count = 30
    missing = set(r.choice(count, 2, replace=False).tolist())
    for i in range(count):
        if i in missing and 3 < i < count - 3:
            continue
        fx = -0.60 + 1.20 * (i + 0.5) / count
        zm = mouth_line(fx)
        fz = zm + (0.042 if upper else -0.042)
        p = face_point(fx, fz)
        # Local frame: along the grin, up, and out of the face.
        p2 = face_point(fx + 0.01, mouth_line(fx + 0.01) + (0.042 if upper else -0.042))
        right = p2 - p
        right /= np.linalg.norm(right)
        out = p - HEAD_CENTER
        out[2] = 0.0
        out -= right * (out @ right)
        out /= np.linalg.norm(out)
        up = np.cross(out, right)
        up /= np.linalg.norm(up)
        if up[2] < 0:
            up = -up
        if not upper:
            up = -up  # lower teeth point up: "up" here means toward the root
        height = 0.0175 + r.uniform(-0.0025, 0.0035)
        width = 1.20 / count * HEAD_SCALE[0] * (0.86 + r.uniform(-0.08, 0.06))
        center = p + out * 0.002 + r.normal(0.0, 0.0005, 3)
        tilt = r.normal(0.0, 0.10)
        right_t = right * math.cos(tilt) + up * math.sin(tilt)
        up_t = up * math.cos(tilt) - right * math.sin(tilt)
        add_box(bm, center, (width, 0.010, height), right_t, up_t, out, taper=0.78 if upper else 0.85)
    obj = to_object(bm, name)
    for poly in obj.data.polygons:
        poly.use_smooth = False
    return obj


def build_eyes():
    """Small wet eyes at the bottom of the sockets."""
    bm = bmesh.new()
    for c in eye_centers():
        res = bmesh.ops.create_uvsphere(bm, u_segments=20, v_segments=14, radius=0.020)
        for v in res["verts"]:
            v.co = np.array(v.co[:]) + c
    return to_object(bm, "eyes")


def eye_centers():
    return [face_point(0.33 * s, 0.13, inset=1.0) + np.array([0.0, -0.004, 0.0]) for s in (1.0, -1.0)]


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


def paint_skin(p, n, ao, face_unit=None, ao_power=1.5):
    """Grey-yellow skin, mottled and bruised, dirtier toward the hands and feet."""
    N = len(p)
    big = Noise(21).fbm(p * 3.0, 4)
    mid = Noise(22).fbm(p * 11.0, 4)
    fine = Noise(23).fbm(p * 60.0, 3)
    bruise_n = Noise(24).fbm(p * 4.5 + 3.0, 4)
    vein_n = Noise(25).fbm(p * 7.0, 5)
    skin = srgb_to_linear((160, 152, 136))
    sallow = srgb_to_linear((156, 146, 108))
    grey = srgb_to_linear((128, 128, 124))
    bruise = srgb_to_linear((100, 76, 90))
    yellow_bruise = srgb_to_linear((150, 140, 92))
    red = srgb_to_linear((150, 98, 92))
    dirt = srgb_to_linear((58, 48, 38))
    col = np.tile(skin, (N, 1))
    col = mix(col, sallow, smoothstep(0.45, 0.75, big) * 0.7)
    col = mix(col, grey, smoothstep(0.55, 0.30, big) * 0.6)
    col = mix(col, bruise, smoothstep(0.58, 0.74, bruise_n) * 0.75)
    col = mix(col, yellow_bruise, smoothstep(0.55, 0.62, bruise_n) * smoothstep(0.66, 0.60, bruise_n) * 0.5)
    col *= (0.78 + 0.44 * mid)[:, None]
    col *= (0.93 + 0.14 * fine)[:, None]
    # Veins: thin, branching, faintly blue-grey, under the thinnest skin.
    vein = smoothstep(0.018, 0.0, np.abs(vein_n - 0.5)) * smoothstep(0.35, 0.65, Noise(26).fbm(p * 2.0, 2))
    col = mix(col, col * srgb_to_linear((120, 132, 160)) / skin, vein * 0.55)
    # Cavities: everything deep is darker and redder (thin skin, no light).
    cavity = 1.0 - ao
    col = mix(col, col * red / skin, smoothstep(0.15, 0.6, cavity) * 0.5)
    col *= (np.clip(ao, 0.0, 1.0) ** ao_power)[:, None]
    # Filth: hands and feet, darkening up the limbs.
    if face_unit is None:
        z = p[:, 2]
        hands = smoothstep(1.25, 0.70, z) * smoothstep(0.15, 0.24, np.abs(p[:, 0]))
        feet = smoothstep(0.30, 0.04, z)
        filth = np.clip(hands + feet, 0.0, 1.0) * (0.55 + 0.45 * Noise(27).fbm(p * 9.0, 3))
        col = mix(col, dirt, filth * 0.85)
        # Knees and elbows: rough and dark.
        for jz, jx in ((0.86, 0.12), (1.83, 0.245)):
            knob = gauss(z - jz, 0.04) * gauss(np.abs(p[:, 0]) - jx, 0.05)
            col = mix(col, col * red / skin * 0.8, knob * 0.6)
        # Nails: the very tips of the fingers and toes.
        tips = smoothstep(0.66, 0.60, z) * smoothstep(0.2, 0.25, np.abs(p[:, 0])) + smoothstep(-0.20, -0.25, p[:, 1]) * smoothstep(0.05, 0.02, z)
        col = mix(col, srgb_to_linear((92, 80, 52)), np.clip(tips, 0, 1) * 0.8)
    return col


def paint_head(p, n, ao, part):
    u = (p - HEAD_CENTER) / HEAD_SCALE
    x, z = u[:, 0], u[:, 2]
    col = paint_skin(p * 1.8, n, ao, face_unit=u, ao_power=1.0)
    # The face is the palest thing about it: it has to catch what little light there is.
    pale = srgb_to_linear((190, 185, 172))
    front = smoothstep(-0.05, -0.6, u[:, 1])
    col = mix(col, pale * (0.72 + 0.28 * ao[:, None]), front * 0.55)
    # Bruised rings around the sockets.
    for s in (-1.0, 1.0):
        q = np.sqrt(((x - s * 0.33) / 0.165) ** 2 + ((z - 0.13) / 0.140) ** 2)
        ring = smoothstep(1.7, 1.0, q) * front
        col = mix(col, srgb_to_linear((84, 66, 72)), ring * 0.5)
        col = mix(col, srgb_to_linear((4, 3, 3)), smoothstep(1.0, 0.6, q) * front)
    # The nose slits.
    for s in (-1.0, 1.0):
        slit = np.exp(-(((x - s * 0.055) / 0.014) ** 2 + ((z + 0.07) / 0.05) ** 2))
        col = mix(col, srgb_to_linear((20, 10, 10)), np.clip(slit * 1.4, 0, 1) * front)
    # Gums, and lips that are barely there.
    zm = mouth_line(np.clip(x, -0.75, 0.75))
    span = smoothstep(MOUTH_HALF + 0.04, MOUTH_HALF - 0.05, np.abs(x)) * front
    band = smoothstep(0.090, 0.060, np.abs(z - zm))
    col = mix(col, srgb_to_linear((62, 28, 28)), band * span)
    lips = gauss(np.abs(z - zm) - 0.098, 0.016) * span
    col = mix(col, srgb_to_linear((112, 84, 78)), lips * 0.6)
    # Mouth corners split a little further than they should.
    corner = gauss(np.abs(x) - MOUTH_HALF - 0.02, 0.03) * gauss(z - mouth_line(MOUTH_HALF), 0.02) * front
    col = mix(col, srgb_to_linear((60, 20, 22)), np.clip(corner * 1.5, 0, 1))
    # Teeth: yellowed, darker at the gumline and between each tooth.
    teeth = part == PART_TEETH
    if teeth.any():
        t_col = np.tile(srgb_to_linear((206, 196, 160)), (teeth.sum(), 1))
        stain = Noise(31).fbm(p[teeth] * 200.0, 3)
        t_col = mix(t_col, srgb_to_linear((150, 128, 84)), smoothstep(0.45, 0.75, stain) * 0.8)
        t_col *= (np.clip(ao[teeth], 0, 1) ** 2.2)[:, None]
        col[teeth] = t_col
    eye = part == PART_EYE
    if eye.any():
        col[eye] = srgb_to_linear((8, 6, 6)) * (0.6 + 0.4 * ao[eye, None])
    mouth = part == PART_MOUTH
    if mouth.any():
        col[mouth] = srgb_to_linear((26, 8, 9)) * (0.5 + 0.5 * ao[mouth, None])
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
HUNCH = {"spine2": (8, 0, 0), "chest": (12, 0, 0), "upchest": (14, 0, 0), "neck0": (34, 0, 0), "neck1": (18, 0, 0),
         "neck2": (-58, 0, 0), "clav_L": (0, 0, -12), "clav_R": (0, 0, 12)}

POSES = {
    # Upright: too tall, the head hung forward on a long neck, tilted. Rarely seen like this indoors.
    "stand": merge(HUNCH, finger_curl(22, "L"), finger_curl(34, "R"),
                   {"neck2": (-56, 14, 6), "shoulder_L": (-8, -3, 0), "shoulder_R": (-6, 2, 0),
                    "elbow_L": (-12, 0, 0), "elbow_R": (-6, 0, 0), "upchest": (14, -3, 0),
                    "hip_L": (-6, 0, 0), "hip_R": (-6, 0, 0), "knee_L": (12, 0, 0), "knee_R": (10, 0, 0),
                    "ankle_L": (-5, 0, 0), "ankle_R": (-4, 0, 0)}),
    # Under a ceiling: bent double, knees bent, neck folded, face lifted to keep looking at you.
    "stoop": merge(finger_curl(26, "L"), finger_curl(30, "R"),
                   {"spine1": (14, 0, 0), "spine2": (16, 0, 0), "chest": (16, 0, 0), "upchest": (14, 0, 0),
                    "neck0": (16, 0, 0), "neck1": (4, 0, 0), "neck2": (-74, -10, 0),
                    "hip_L": (-34, -4, 0), "hip_R": (-34, 4, 0), "knee_L": (58, 0, 0), "knee_R": (58, 0, 0),
                    "ankle_L": (-24, 0, 0), "ankle_R": (-24, 0, 0),
                    "shoulder_L": (-24, 0, 0), "shoulder_R": (-20, 0, 0), "elbow_L": (-14, 0, 0), "elbow_R": (-10, 0, 0),
                    "root_shift": (0.0, 0.12, -0.20)}),
    # Squatting, knees by its ears, arms hanging between them to the floor, face tilted up at you.
    "crouch": merge(finger_curl(36, "L"), finger_curl(30, "R"),
                    {"hip_L": (-118, -14, 0), "hip_R": (-118, 14, 0), "knee_L": (150, 0, 0), "knee_R": (150, 0, 0),
                     "ankle_L": (-30, 0, 0), "ankle_R": (-30, 0, 0),
                     "spine1": (22, 0, 0), "spine2": (16, 0, 0), "chest": (8, 0, 0),
                     "neck0": (8, 0, 0), "neck2": (-64, 14, 0),
                     "shoulder_L": (-8, 6, 0), "shoulder_R": (-6, -6, 0), "elbow_L": (-6, 0, 0),
                     "root_shift": (0.0, 0.18, -0.86)}),
    # On all fours, belly low, head raised and turned to look at you.
    "crawl_a": merge(finger_curl(-8, "L", 6), finger_curl(-8, "R", 6),
                     {"root": (80, 0, 0), "root_pivot": (0.0, 0.0, 1.6), "root_shift": (0.0, 0.70, -1.08),
                      "hip_L": (-64, 0, 0), "hip_R": (-84, 0, 0), "knee_L": (84, 0, 0), "knee_R": (96, 0, 0),
                      "ankle_L": (-20, 0, 0), "ankle_R": (-20, 0, 0),
                      "shoulder_L": (-70, 0, 0), "shoulder_R": (-88, 0, 0), "elbow_L": (14, 0, 0), "elbow_R": (24, 0, 0),
                      "wrist_L": (46, 0, 0), "wrist_R": (50, 0, 0),
                      "neck0": (-34, 0, 0), "neck1": (-26, 0, 0), "neck2": (-40, 0, 16)}),
    "crawl_b": merge(finger_curl(-8, "L", 6), finger_curl(-8, "R", 6),
                     {"root": (80, 0, 0), "root_pivot": (0.0, 0.0, 1.6), "root_shift": (0.0, 0.70, -1.08),
                      "hip_L": (-84, 0, 0), "hip_R": (-64, 0, 0), "knee_L": (96, 0, 0), "knee_R": (84, 0, 0),
                      "ankle_L": (-20, 0, 0), "ankle_R": (-20, 0, 0),
                      "shoulder_L": (-88, 0, 0), "shoulder_R": (-70, 0, 0), "elbow_L": (24, 0, 0), "elbow_R": (14, 0, 0),
                      "wrist_L": (50, 0, 0), "wrist_R": (46, 0, 0),
                      "neck0": (-34, 0, 0), "neck1": (-26, 0, 0), "neck2": (-40, 0, -16)}),
    # Leaning out from behind a wall on its right, the long fingers of its right hand curled round
    # the edge at head height.
    "peek_right": merge(finger_curl(64, "R"), finger_curl(20, "L"),
                        {"spine1": (4, -12, 0), "spine2": (4, -14, 0), "chest": (4, -12, 0), "upchest": (2, -8, 0),
                         "neck0": (14, -10, 0), "neck1": (6, 0, 0), "neck2": (-44, -18, 0),
                         "hip_L": (0, 6, 0), "hip_R": (0, 10, 0),
                         "shoulder_R": (-40, 50, 0), "elbow_R": (-96, 0, 0), "wrist_R": (0, 30, 0),
                         "shoulder_L": (8, 0, 0)}),
    "peek_left": merge(finger_curl(64, "L"), finger_curl(20, "R"),
                       {"spine1": (4, 12, 0), "spine2": (4, 14, 0), "chest": (4, 12, 0), "upchest": (2, 8, 0),
                        "neck0": (14, 10, 0), "neck1": (6, 0, 0), "neck2": (-44, 18, 0),
                        "hip_R": (0, -6, 0), "hip_L": (0, -10, 0),
                        "shoulder_L": (-40, -50, 0), "elbow_L": (-96, 0, 0), "wrist_L": (0, -30, 0),
                        "shoulder_R": (8, 0, 0)}),
    # One arm out toward you, fingers loose.
    "reach": merge(HUNCH, finger_curl(6, "L", 8), finger_curl(30, "R"),
                   {"chest": (14, 0, 0), "shoulder_L": (-84, 4, 0), "elbow_L": (-12, 0, 0), "wrist_L": (-8, 0, 0),
                    "shoulder_R": (6, 2, 0), "neck2": (-44, -8, 0)}),
    # Bent at the waist over a bed, its face hanging just above where yours is.
    "bedside": merge(finger_curl(30, "L"), finger_curl(30, "R"),
                     {"spine1": (30, 0, 0), "spine2": (24, 0, 0), "chest": (18, 0, 0), "upchest": (10, 0, 0),
                      "neck0": (8, 0, 0), "neck2": (2, 8, 0),
                      "shoulder_L": (-20, 0, 0), "shoulder_R": (-24, 0, 0), "elbow_L": (-6, 0, 0), "elbow_R": (-6, 0, 0),
                      "hip_L": (-6, 0, 0), "hip_R": (-6, 0, 0)}),
    # The attack: arms wide, everything forward, the jaw open far past where a jaw stops.
    "lunge": merge(finger_curl(-14, "L", 10), finger_curl(-14, "R", 10),
                   {"spine1": (12, 0, 0), "spine2": (10, 0, 0), "chest": (8, 0, 0), "neck0": (18, 0, 0), "neck2": (-62, 0, 0),
                    "shoulder_L": (-80, -40, 0), "shoulder_R": (-80, 40, 0), "elbow_L": (-24, 0, 0), "elbow_R": (-24, 0, 0),
                    "hip_L": (-24, 0, 0), "knee_L": (34, 0, 0), "hip_R": (12, 0, 0), "jaw": 52}),
    # Flat on its back on the floor beside your bed, head turned toward you.
    "lie": merge(finger_curl(14, "L"), finger_curl(18, "R"),
                 {"root": (-90, 0, 0), "root_pivot": (0.0, 0.0, 0.0), "root_shift": (0.0, 0.0, 0.11),
                  "neck2": (-6, 0, 70), "shoulder_L": (0, -8, 0), "shoulder_R": (0, 8, 0)}),
    # Stop-motion walking frames.
    "walk_a": merge(HUNCH, finger_curl(24, "L"), finger_curl(28, "R"),
                    {"chest": (14, 0, 0), "hip_L": (-28, 0, 0), "knee_L": (34, 0, 0), "hip_R": (18, 0, 0), "knee_R": (22, 0, 0),
                     "ankle_R": (-12, 0, 0), "shoulder_L": (16, 0, 0), "shoulder_R": (-14, 0, 0), "root_shift": (0.0, 0.0, -0.07)}),
    "walk_b": merge(HUNCH, finger_curl(24, "L"), finger_curl(28, "R"),
                    {"chest": (14, 0, 0), "hip_R": (-28, 0, 0), "knee_R": (34, 0, 0), "hip_L": (18, 0, 0), "knee_L": (22, 0, 0),
                     "ankle_L": (-12, 0, 0), "shoulder_R": (16, 0, 0), "shoulder_L": (-14, 0, 0), "root_shift": (0.0, 0.0, -0.07)}),
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
    make_texture("creature_body", body, {"body_skin": PART_SKIN}, tex_size, args.quick,
                 lambda p, n, a, pt: paint_skin(p, n, a), 0.10)
    make_texture("creature_head", head_all, {name: part for name, part, _ in head_parts}, tex_size, args.quick,
                 paint_head, 0.035)

    print("decimating")
    body = decimate(body, 0.24)
    set_part_attribute(body, PART_SKIN)
    pieces = separate_by_material(head_all)
    groups_of = {"head": [], "jaw": []}
    for name, part, group in head_parts:
        obj = pieces[name]
        if part == PART_SKIN:
            obj = decimate(obj, 0.34 if group == "head" else 0.42)
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
    jaw_hinge = HEAD_CENTER + JAW_HINGE_UNIT * HEAD_SCALE
    eyes_rest = eye_centers()
    mouth_rest = face_point(0.0, mouth_line(0.0), inset=0.95)
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
            J = about(jaw_hinge, rot("x", pose["jaw"]))  # + opens: the chin swings down and back
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
        height = float(np.vstack(all_pos)[:, 1].max())
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
