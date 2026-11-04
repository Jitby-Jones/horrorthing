"""Skeletal export for the creature: joints, skin weights, rest mesh and baked animation clips.

Binary format v3, big-endian (Java DataInputStream), gzip-compressed. Model space throughout:
blocks, +Y up, facing +Z, feet at the origin.

    "WAKEMESH" int version(3)
    int jointCount
        per joint: utf name, int parent (-1 for none), float[3] rest position
    int attachmentCount
        per attachment: utf name, int joint, byte kind (0 point, 1 direction), float[3] rest value
    int groupCount
        per group: utf name, utf texture, byte kind (0 skinned, 1 rigid), int vertexCount n,
                   int indexCount, float[2n] uv, (ushort|int)[indexCount] indices (ushort if n < 65536),
                   rest positions: float[3] min, float[3] max, ushort[3n] quantised; byte[3n] normals (x127)
                   rigid:   int joint
                   skinned: ubyte[4n] joints, ubyte[4n] weights (each vertex's four sum to 255)
    int clipCount
        per clip: utf name, float fps, byte flags (1 loop), int frameCount
                  per frame: float height; per joint: short[4] rotation quaternion (x, y, z, w, x32767),
                             float[3] translation
                  A joint's frame transform maps rest-pose points to posed points: p' = R p + t.
                  A vertex weighted to joint j moves with joint j's transform.

Weights refer to the joint whose transform moves the vertex. In the Blender rig a bone runs from
a parent joint to a child joint and moves with the parent's transform, so a bone's weight is
credited to its parent joint.
"""
import gzip
import struct

import numpy as np

TO_MC = np.array([[1.0, 0.0, 0.0, 0.0], [0.0, 0.0, 1.0, 0.0], [0.0, -1.0, 0.0, 0.0], [0.0, 0.0, 0.0, 1.0]])


def to_mc(p):
    p = np.atleast_2d(p)
    return np.stack([p[:, 0], p[:, 2], -p[:, 1]], axis=1)


def matrix_to_mc(m):
    return TO_MC @ m @ TO_MC.T


def utf(s):
    b = s.encode("utf-8")
    return struct.pack(">H", len(b)) + b


def quat_from_matrix(R):
    """Rotation matrix -> quaternion (x, y, z, w), w >= 0."""
    m = R
    t = np.trace(m)
    if t > 0:
        s = np.sqrt(t + 1.0) * 2
        w = 0.25 * s
        x = (m[2, 1] - m[1, 2]) / s
        y = (m[0, 2] - m[2, 0]) / s
        z = (m[1, 0] - m[0, 1]) / s
    elif m[0, 0] > m[1, 1] and m[0, 0] > m[2, 2]:
        s = np.sqrt(1.0 + m[0, 0] - m[1, 1] - m[2, 2]) * 2
        w = (m[2, 1] - m[1, 2]) / s
        x = 0.25 * s
        y = (m[0, 1] + m[1, 0]) / s
        z = (m[0, 2] + m[2, 0]) / s
    elif m[1, 1] > m[2, 2]:
        s = np.sqrt(1.0 + m[1, 1] - m[0, 0] - m[2, 2]) * 2
        w = (m[0, 2] - m[2, 0]) / s
        x = (m[0, 1] + m[1, 0]) / s
        y = 0.25 * s
        z = (m[1, 2] + m[2, 1]) / s
    else:
        s = np.sqrt(1.0 + m[2, 2] - m[0, 0] - m[1, 1]) * 2
        w = (m[1, 0] - m[0, 1]) / s
        x = (m[0, 2] + m[2, 0]) / s
        y = (m[1, 2] + m[2, 1]) / s
        z = 0.25 * s
    q = np.array([x, y, z, w])
    q /= np.linalg.norm(q)
    return q if q[3] >= 0 else -q


def quat_to_matrix(q):
    x, y, z, w = q
    return np.array([[1 - 2 * (y * y + z * z), 2 * (x * y - z * w), 2 * (x * z + y * w)],
                     [2 * (x * y + z * w), 1 - 2 * (x * x + z * z), 2 * (y * z - x * w)],
                     [2 * (x * z - y * w), 2 * (y * z + x * w), 1 - 2 * (x * x + y * y)]])


def deform_weights(obj, joint_names, parent_of, render_vidx, max_influences=4):
    """Per render vertex: up to four (joint index, weight) pairs, weights summing to 255.

    obj is the skinned Blender mesh with bone vertex groups; parent_of maps a bone (child joint)
    name to the joint whose transform moves it.
    """
    index = {n: i for i, n in enumerate(joint_names)}
    groups = {g.index: g.name for g in obj.vertex_groups}
    nv = len(obj.data.vertices)
    per_vertex = [dict() for _ in range(nv)]
    for v in obj.data.vertices:
        acc = per_vertex[v.index]
        for g in v.groups:
            name = groups.get(g.group)
            if name is None or name == "root" or g.weight <= 0.0:
                continue
            joint = parent_of.get(name)
            if joint is None:
                continue
            acc[index[joint]] = acc.get(index[joint], 0.0) + g.weight
    joints = np.zeros((len(render_vidx), max_influences), dtype=np.uint8)
    weights = np.zeros((len(render_vidx), max_influences), dtype=np.uint8)
    for r, vi in enumerate(render_vidx):
        items = sorted(per_vertex[vi].items(), key=lambda kv: -kv[1])[:max_influences]
        if not items:
            raise ValueError(f"vertex {vi} has no weights")
        total = sum(w for _, w in items)
        q = [int(round(w / total * 255)) for _, w in items]
        q[0] += 255 - sum(q)
        for k, ((j, _), w) in enumerate(zip(items, q)):
            joints[r, k] = j
            weights[r, k] = w
    return joints, weights


def frame_transforms(mats, joint_names):
    """mats: joint name -> 4x4 rest->posed (Blender space). Returns [n, 7] (qx, qy, qz, qw, tx, ty, tz) in MC space."""
    out = np.zeros((len(joint_names), 7))
    for i, n in enumerate(joint_names):
        m = matrix_to_mc(mats[n])
        out[i, :4] = quat_from_matrix(m[:3, :3])
        out[i, 4:] = m[:3, 3]
    return out


def dqs(rest, normals, joints, weights, frame):
    """Dual-quaternion skinning, the same as the game does it. frame: [n_joints, 7] in MC space."""
    q = frame[:, :4]
    t = frame[:, 4:]
    # dual part: 0.5 * (t, 0) * q
    def qmul(a, b):
        ax, ay, az, aw = a[..., 0], a[..., 1], a[..., 2], a[..., 3]
        bx, by, bz, bw = b[..., 0], b[..., 1], b[..., 2], b[..., 3]
        return np.stack([aw * bx + ax * bw + ay * bz - az * by,
                         aw * by - ax * bz + ay * bw + az * bx,
                         aw * bz + ax * by - ay * bx + az * bw,
                         aw * bw - ax * bx - ay * by - az * bz], -1)
    tq = np.concatenate([t, np.zeros((len(t), 1))], 1)
    d = 0.5 * qmul(tq, q)
    w = weights.astype(float) / 255.0
    q0 = q[joints[:, 0]]
    br = np.zeros((len(rest), 4))
    bd = np.zeros((len(rest), 4))
    for k in range(joints.shape[1]):
        qk = q[joints[:, k]]
        dk = d[joints[:, k]]
        sign = np.where(np.sum(qk * q0, 1) < 0, -1.0, 1.0)
        br += (w[:, k] * sign)[:, None] * qk
        bd += (w[:, k] * sign)[:, None] * dk
    norm = np.linalg.norm(br, axis=1, keepdims=True)
    br /= norm
    bd /= norm
    v = br[:, :3]
    s = br[:, 3:4]
    def rotate(p):
        return p + 2.0 * np.cross(v, np.cross(v, p) + s * p)
    pos = rotate(rest)
    # translation = 2 * (s * bd.xyz - bd.w * v + cross(v, bd.xyz))
    trans = 2.0 * (s * bd[:, :3] - bd[:, 3:4] * v + np.cross(v, bd[:, :3]))
    pos = pos + trans
    nor = rotate(normals)
    return pos, nor


def pack_positions(pos, nor):
    lo = pos.min(axis=0) - 1e-4
    hi = pos.max(axis=0) + 1e-4
    q = np.round((pos - lo) / (hi - lo) * 65535.0).astype(">u2")
    return (np.asarray(lo, dtype=">f4").tobytes() + np.asarray(hi, dtype=">f4").tobytes() + q.tobytes()
            + np.clip(np.round(nor * 127.0), -127, 127).astype(np.int8).tobytes())


def write_bin(path, joints, attachments, groups, clips):
    """joints: [(name, parent_index, rest_pos_mc)]; attachments: [(name, joint_index, kind, vec_mc)];
    groups: dicts with name, texture, rigid, uv, tris, rest, rest_normals, joint | joints+weights;
    clips: dicts with name, fps, loop, frames ([n_frames, n_joints, 7]), heights."""
    out = bytearray()
    out += b"WAKEMESH" + struct.pack(">i", 3)
    out += struct.pack(">i", len(joints))
    for name, parent, pos in joints:
        out += utf(name) + struct.pack(">i", parent) + np.asarray(pos, dtype=">f4").tobytes()
    out += struct.pack(">i", len(attachments))
    for name, joint, kind, vec in attachments:
        out += utf(name) + struct.pack(">ib", joint, kind) + np.asarray(vec, dtype=">f4").tobytes()
    out += struct.pack(">i", len(groups))
    for g in groups:
        out += utf(g["name"]) + utf(g["texture"]) + struct.pack(">b", 1 if g["rigid"] else 0)
        n = len(g["uv"])
        out += struct.pack(">ii", n, g["tris"].size)
        out += np.asarray(g["uv"], dtype=">f4").tobytes()
        idx_type = ">u2" if n < 65536 else ">i4"
        out += np.asarray(g["tris"].reshape(-1), dtype=idx_type).tobytes()
        out += pack_positions(g["rest"], g["rest_normals"])
        if g["rigid"]:
            out += struct.pack(">i", g["joint"])
        else:
            out += np.asarray(g["joints"], dtype=np.uint8).tobytes()
            out += np.asarray(g["weights"], dtype=np.uint8).tobytes()
    out += struct.pack(">i", len(clips))
    for c in clips:
        frames = np.asarray(c["frames"])
        out += utf(c["name"]) + struct.pack(">fbi", c["fps"], 1 if c.get("loop") else 0, len(frames))
        for f, h in zip(frames, c["heights"]):
            out += struct.pack(">f", h)
            q = np.clip(np.round(f[:, :4] * 32767.0), -32767, 32767).astype(">i2")
            t = f[:, 4:].astype(">f4")
            for qi, ti in zip(q, t):
                out += qi.tobytes() + ti.tobytes()
    with gzip.open(path, "wb", compresslevel=9) as fh:
        fh.write(bytes(out))
    return len(out)


def read_bin(path):
    """Reads a v3 file back (for previews and checks)."""
    with gzip.open(path, "rb") as fh:
        data = fh.read()
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

    assert data[:8] == b"WAKEMESH"
    off = 8
    (version,) = take(">i")
    assert version == 3, version
    (nj,) = take(">i")
    joints = []
    for _ in range(nj):
        name = take_utf()
        (parent,) = take(">i")
        joints.append((name, parent, take_arr(">f4", 3).astype(float)))
    (na,) = take(">i")
    attachments = {}
    for _ in range(na):
        name = take_utf()
        joint, kind = take(">ib")
        attachments[name] = (joint, kind, take_arr(">f4", 3).astype(float))
    (ng,) = take(">i")
    groups = []
    for _ in range(ng):
        name = take_utf()
        texture = take_utf()
        (rigid,) = take(">b")
        n, ni = take(">ii")
        uv = take_arr(">f4", n * 2).reshape(-1, 2)
        tris = take_arr(">u2" if n < 65536 else ">i4", ni).reshape(-1, 3)
        lo = take_arr(">f4", 3)
        hi = take_arr(">f4", 3)
        q = take_arr(">u2", n * 3).reshape(-1, 3).astype(np.float64)
        nor = take_arr(np.int8, n * 3).reshape(-1, 3) / 127.0
        g = {"name": name, "texture": texture, "rigid": bool(rigid), "uv": uv, "tris": tris,
             "rest": lo + q / 65535.0 * (hi - lo), "rest_normals": nor}
        if rigid:
            (g["joint"],) = take(">i")
        else:
            g["joints"] = take_arr(np.uint8, n * 4).reshape(-1, 4)
            g["weights"] = take_arr(np.uint8, n * 4).reshape(-1, 4)
        groups.append(g)
    (nc,) = take(">i")
    clips = {}
    for _ in range(nc):
        name = take_utf()
        fps, flags, nf = take(">fbi")
        frames, heights = [], []
        for _ in range(nf):
            (h,) = take(">f")
            heights.append(h)
            f = np.zeros((nj, 7))
            for j in range(nj):
                f[j, :4] = take_arr(">i2", 4) / 32767.0
                f[j, :4] /= np.linalg.norm(f[j, :4])
                f[j, 4:] = take_arr(">f4", 3)
            frames.append(f)
        clips[name] = {"fps": fps, "loop": bool(flags & 1), "frames": frames, "heights": heights}
    assert off == len(data), (off, len(data))
    return {"joints": joints, "attachments": attachments, "groups": groups, "clips": clips}


def posed(model, clip, frame):
    """[(group, positions, normals)] for one frame of a clip, in model space."""
    f = model["clips"][clip]["frames"][frame % len(model["clips"][clip]["frames"])]
    out = []
    for g in model["groups"]:
        if g["rigid"]:
            R = quat_to_matrix(f[g["joint"], :4])
            pos = g["rest"] @ R.T + f[g["joint"], 4:]
            nor = g["rest_normals"] @ R.T
        else:
            pos, nor = dqs(g["rest"], g["rest_normals"], g["joints"], g["weights"], f)
        out.append((g, pos, nor))
    return out


def attachment(model, clip, frame, name):
    f = model["clips"][clip]["frames"][frame % len(model["clips"][clip]["frames"])]
    joint, kind, v = model["attachments"][name]
    R = quat_to_matrix(f[joint, :4])
    return R @ v if kind == 1 else R @ v + f[joint, 4:]
