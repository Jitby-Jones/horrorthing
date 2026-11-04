"""The creature's poses and stop-motion clips, and the forward kinematics that turns them into joint
transforms. Pure numpy: no Blender needed, so clips can be checked and previewed anywhere.

A pose is a dict of joint name -> (rx, ry, rz) degrees about the world axes at rest, applied at
that joint (Blender space: Z up, the creature faces -Y):
    rx +: spine/neck bend forward; a hanging limb swings BACK; - swings it forward.
    ry +: tips toward +X (the creature's left). Left fingers curl with +ry, right with -ry.
    rz +: turns counter-clockwise seen from above (head turns to its left).
Extra keys: "root" (rx, ry, rz about "root_pivot"), "root_shift" (x, y, z blocks), "jaw" (degrees
the mouth drops open), "jaw_drop" (blocks the jaw slides down).

A clip is a list of poses, one per frame, played as stop-motion (no blending in the game).
"""
import math

import numpy as np

SPECIAL = ("root", "root_pivot", "root_shift", "jaw", "jaw_drop")


# --------------------------------------------------------------------------------------------
# The skeleton (Blender space: Z up, facing -Y, blocks)
# --------------------------------------------------------------------------------------------

class Joint:
    def __init__(self, name, pos, radius, parent):
        self.name = name
        self.pos = np.array(pos, dtype=float)
        self.radius = radius if isinstance(radius, tuple) else (radius, radius)
        self.parent = parent


# The head: an index card cut to a long face, hung on the neck. Head-local space: origin at
# HEAD_CENTER, x to the creature's left, y back, z up. CARD is the half-size (width at the
# cheekbones, thickness, height).
HEAD_CENTER = np.array([0.0, -0.088, 3.215])
CARD = np.array([0.150, 0.010, 0.260])
EYES_LOCAL = [np.array([-0.0627, -0.016, 0.0650]), np.array([0.0655, -0.016, 0.0572])]
MOUTH_LOCAL = np.array([0.0, -0.022, -0.1222])
JAW_HINGE_LOCAL = np.array([0.0, 0.012, -0.1222])
FINGER_SEGMENTS = (0.128, 0.112, 0.098, 0.082)  # one knuckle too many
EYE_BACK = np.array([0.0, 0.020, 0.0])  # the eyeballs sit back in the holes
EYE_RADIUS = 0.0125


def skeleton():
    """The body's joints: a starved puppet, all ball joints and sticks, the arms past its knees."""
    joints = []

    def add(name, pos, radius, parent):
        joints.append(Joint(name, pos, radius, parent))

    add("pelvis", (0, 0.000, 1.56), (0.080, 0.060), None)
    # No belly: between the hips and the ribs there is only the spine.
    add("spine1", (0, 0.026, 1.70), 0.029, "pelvis")
    add("spine1b", (0, 0.033, 1.84), 0.026, "spine1")
    add("spine2", (0, 0.033, 1.98), 0.029, "spine1b")
    add("chest", (0, 0.028, 2.15), (0.092, 0.068), "spine2")
    add("upchest", (0, 0.034, 2.35), (0.106, 0.064), "chest")
    # A long neck, a vertebra at each joint.
    add("neck0", (0, 0.040, 2.49), 0.027, "upchest")
    add("neck1", (0, 0.020, 2.72), 0.022, "neck0")
    add("neck2", (0, -0.020, 2.95), 0.020, "neck1")
    add("skull", (0, -0.050, 3.07), 0.018, "neck2")

    for side, s in (("L", 1.0), ("R", -1.0)):
        add(f"clav_{side}", (0.074 * s, 0.040, 2.39), (0.032, 0.030), "upchest")
        add(f"shoulder_{side}", (0.178 * s, 0.050, 2.36), 0.038, f"clav_{side}")
        add(f"uarm_{side}", (0.190 * s, 0.058, 2.06), 0.019, f"shoulder_{side}")
        add(f"elbow_{side}", (0.200 * s, 0.066, 1.77), 0.029, f"uarm_{side}")
        add(f"farm_{side}", (0.210 * s, 0.050, 1.45), 0.017, f"elbow_{side}")
        add(f"wrist_{side}", (0.218 * s, 0.030, 1.14), (0.021, 0.018), f"farm_{side}")
        add(f"palm_{side}", (0.224 * s, 0.018, 1.03), (0.030, 0.012), f"wrist_{side}")
        # Pale fingers far too long, with one knuckle too many.
        for f, (dy, scale) in enumerate(((-0.030, 0.94), (-0.010, 1.0), (0.010, 0.96), (0.029, 0.82))):
            base = np.array([0.226 * s, 0.018 + dy, 0.995])
            spread = dy * 0.3
            prev = f"palm_{side}"
            p = base
            add(f"f{f}k0_{side}", p, 0.0088, prev)
            prev = f"f{f}k0_{side}"
            for k, seg in enumerate(FINGER_SEGMENTS):
                seg *= scale
                d = np.array([0.0, spread, -1.0])
                d /= np.linalg.norm(d)
                mid = p + d * seg * 0.5
                end = p + d * seg
                add(f"f{f}m{k}_{side}", mid, 0.0058 - k * 0.0006, prev)
                tip = k == len(FINGER_SEGMENTS) - 1
                add(f"f{f}k{k + 1}_{side}", end, 0.0024 if tip else 0.0082 - k * 0.0008, f"f{f}m{k}_{side}")
                prev = f"f{f}k{k + 1}_{side}"
                p = end
        add(f"thumb0_{side}", (0.212 * s, -0.016, 1.10), 0.0086, f"wrist_{side}")
        add(f"thumb1_{side}", (0.208 * s, -0.040, 1.00), 0.0076, f"thumb0_{side}")
        add(f"thumb2_{side}", (0.203 * s, -0.052, 0.91), 0.0058, f"thumb1_{side}")
        add(f"thumb3_{side}", (0.199 * s, -0.058, 0.84), 0.0024, f"thumb2_{side}")

        add(f"hip_{side}", (0.076 * s, 0.000, 1.52), (0.040, 0.037), "pelvis")
        add(f"thigh_{side}", (0.082 * s, -0.010, 1.19), 0.027, f"hip_{side}")
        add(f"knee_{side}", (0.086 * s, -0.020, 0.84), (0.034, 0.031), f"thigh_{side}")
        add(f"shin_{side}", (0.088 * s, 0.000, 0.48), 0.021, f"knee_{side}")
        add(f"ankle_{side}", (0.089 * s, 0.022, 0.10), (0.022, 0.020), f"shin_{side}")
        add(f"heel_{side}", (0.089 * s, 0.044, 0.030), (0.019, 0.018), f"ankle_{side}")
        add(f"foot_{side}", (0.089 * s, -0.100, 0.028), (0.026, 0.013), f"ankle_{side}")
        add(f"toe_{side}", (0.089 * s, -0.235, 0.016), (0.021, 0.008), f"foot_{side}")
    return joints


def head_joints():
    """Joints the head parts ride on, not part of the body's skin: the dummy jaw on its hinge, the
    eyes behind their holes, and the lids over them."""
    out = [Joint("jaw", HEAD_CENTER + JAW_HINGE_LOCAL, 0.01, "neck2")]
    for side, e in (("L", EYES_LOCAL[0]), ("R", EYES_LOCAL[1])):
        out.append(Joint(f"eye_{side}", HEAD_CENTER + e + EYE_BACK, 0.01, "neck2"))
        out.append(Joint(f"lid_{side}", HEAD_CENTER + e + EYE_BACK, 0.01, "neck2"))
    return out


def rig():
    """Every joint the game knows, parents first."""
    return skeleton() + head_joints()


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


def joint_transforms(joints, pose, jaw_hinge=None):
    """Forward kinematics: a rigid transform per joint (rest -> posed), rotations applied about each
    joint at rest orientation, parents first. A bone parent->child moves with the parent.
    The "jaw" joint, if present, opens about the hinge by pose["jaw"] and slides by "jaw_drop"."""
    mats = {}
    for j in joints:
        parent = mats[j.parent] if j.parent else root_matrix(pose)
        if j.name == "jaw":
            # the dummy's chin piece: drops straight down its cuts, tipping a little
            local = about(j.pos, rot("x", pose.get("jaw", 0.0) * 0.25))
            drop = pose.get("jaw_drop", 0.0) + pose.get("jaw", 0.0) / 60.0 * 0.07
            if drop:
                S = np.eye(4)
                S[2, 3] = -drop
                local = S @ local
        else:
            r = pose.get(j.name)
            local = about(j.pos, euler(*r)) if r is not None else np.eye(4)
        mats[j.name] = parent @ local
    return mats


def merge(*dicts):
    out = {}
    for d in dicts:
        out.update(d)
    return out


def finger_curl(amount, side, spread=0.0):
    """Curls all four fingers; amount spreads over the extra knuckle too."""
    s = 1.0 if side == "L" else -1.0
    pose = {}
    for f in range(4):
        fan = (f - 1.5) * spread
        pose[f"f{f}k0_{side}"] = (fan, amount * 0.4 * s, 0.0)
        pose[f"f{f}k1_{side}"] = (0.0, amount * 0.7 * s, 0.0)
        pose[f"f{f}k2_{side}"] = (0.0, amount * 0.85 * s, 0.0)
        pose[f"f{f}k3_{side}"] = (0.0, amount * 0.75 * s, 0.0)
    pose[f"thumb1_{side}"] = (0.0, amount * 0.5 * s, 0.0)
    return pose


def lerp_pose(a, b, t):
    """Joint-wise linear blend of two poses (euler angles; fine for authoring stop-motion keys)."""
    out = {}
    for k in set(a) | set(b):
        if k == "root_pivot":
            out[k] = a.get(k, b.get(k))
            continue
        va = a.get(k)
        vb = b.get(k)
        if va is None:
            va = 0.0 if np.isscalar(vb) else tuple(0.0 for _ in vb)
        if vb is None:
            vb = 0.0 if np.isscalar(va) else tuple(0.0 for _ in va)
        if np.isscalar(va):
            out[k] = va + (vb - va) * t
        else:
            out[k] = tuple(x + (y - x) * t for x, y in zip(va, vb))
    return out


def ease(t):
    return t * t * (3 - 2 * t)


def keyed(keys, frames, loop=False, easing=ease):
    """Frames from keyframes [(frame, pose)], in-betweened. A looping clip wraps last to first."""
    keys = sorted(keys, key=lambda kv: kv[0])
    if loop:
        keys = keys + [(frames, keys[0][1])]
    out = []
    for f in range(frames):
        for (fa, pa), (fb, pb) in zip(keys[:-1], keys[1:]):
            if fa <= f <= fb:
                t = 0.0 if fb == fa else (f - fa) / (fb - fa)
                out.append(lerp_pose(pa, pb, easing(t)))
                break
        else:
            out.append(dict(keys[-1][1]))
    return out


# --------------------------------------------------------------------------------------------
# Poses
# --------------------------------------------------------------------------------------------

HUNCH = {"spine2": (6, 0, 0), "chest": (10, 0, 0), "upchest": (12, 0, 0), "neck0": (34, 0, 0), "neck1": (20, 0, 0),
         "neck2": (-58, 0, 0), "clav_L": (0, 0, -10), "clav_R": (0, 0, 10)}

POSES = {
    # Upright: too tall, the head hung forward on the long neck, tilted. Rarely seen like this indoors.
    "stand": merge(HUNCH, finger_curl(18, "L"), finger_curl(30, "R"),
                   {"neck2": (-56, 16, 8), "shoulder_L": (-6, -3, 0), "shoulder_R": (-4, 2, 0),
                    "elbow_L": (-10, 0, 0), "elbow_R": (-5, 0, 0), "upchest": (12, -3, 0),
                    "hip_L": (-5, 0, 0), "hip_R": (-5, 0, 0), "knee_L": (10, 0, 0), "knee_R": (8, 0, 0),
                    "ankle_L": (-4, 0, 0), "ankle_R": (-3, 0, 0)}),
    # Under a ceiling: bent double, knees bent, neck folded, the face lifted to keep looking at you.
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
    # Bent at the waist over a bed, the face hanging just above where your face is.
    "bedside": merge(finger_curl(28, "L"), finger_curl(28, "R"),
                     {"spine1": (30, 0, 0), "spine2": (24, 0, 0), "chest": (18, 0, 0), "upchest": (10, 0, 0),
                      "neck0": (8, 0, 0), "neck2": (2, 10, 0),
                      "shoulder_L": (-20, 0, 0), "shoulder_R": (-24, 0, 0), "elbow_L": (-6, 0, 0), "elbow_R": (-6, 0, 0),
                      "hip_L": (-6, 0, 0), "hip_R": (-6, 0, 0)}),
    # Flat on its back, the face turned toward you.
    "lie": merge(finger_curl(12, "L"), finger_curl(16, "R"),
                 {"root": (-90, 0, 0), "root_pivot": (0.0, 0.0, 0.0), "root_shift": (0.0, 0.0, 0.08),
                  "neck2": (-6, 0, 70), "shoulder_L": (0, -8, 0), "shoulder_R": (0, 8, 0)}),
}


# --------------------------------------------------------------------------------------------
# Clips
# --------------------------------------------------------------------------------------------

def walk_key(lead, phase):
    """One key of the walk. lead: 'L' or 'R' leg forward. phase: 'contact' or 'passing'.

    It does not walk like a person: it lifts each knee too high, like a marionette, the foot
    hanging; the shoulders hitch with each step; the arms barely swing and the fingers trail;
    the head stays level and on you."""
    back = "R" if lead == "L" else "L"
    s = 1.0 if lead == "L" else -1.0
    if phase == "contact":
        legs = {f"hip_{lead}": (-30, 0, 0), f"knee_{lead}": (12, 0, 0), f"ankle_{lead}": (10, 0, 0),
                f"hip_{back}": (18, 0, 0), f"knee_{back}": (22, 0, 0), f"ankle_{back}": (-24, 0, 0)}
        shift = (0.0, 0.0, -0.12)
        hitch = {"upchest": (12, -4 * s, 3 * s)}
    else:
        legs = {f"hip_{back}": (-58, 0, 0), f"knee_{back}": (96, 0, 0), f"ankle_{back}": (-40, 0, 0),
                f"hip_{lead}": (2, 0, 0), f"knee_{lead}": (4, 0, 0), f"ankle_{lead}": (0, 0, 0)}
        shift = (0.0, 0.0, -0.02)
        hitch = {"upchest": (12, 3 * s, -2 * s)}
    arms = {f"shoulder_{lead}": (8, 0, 0), f"shoulder_{back}": (-12, 0, 0), "elbow_L": (-8, 0, 0), "elbow_R": (-8, 0, 0)}
    return merge(HUNCH, finger_curl(22, "L"), finger_curl(26, "R"),
                 {"spine2": (14, 0, 0), "chest": (20, 0, 0), "neck2": (-68, 0, 0), "root_shift": shift},
                 legs, arms, hitch)


def stoop_key(lead, phase):
    """Bent double under a low ceiling: knees deep, the hands hanging almost to the floor."""
    base = walk_key(lead, phase)
    s = 1.0 if lead == "L" else -1.0
    back = "R" if lead == "L" else "L"
    deep = {"spine1": (14, 0, 0), "spine2": (18, 0, 0), "chest": (20, 0, 0), "upchest": (16, -3 * s, 0),
            "neck0": (16, 0, 0), "neck1": (4, 0, 0), "neck2": (-84, 0, 0),
            "shoulder_L": (-30, 0, 0), "shoulder_R": (-30, 0, 0), "elbow_L": (-10, 0, 0), "elbow_R": (-10, 0, 0),
            "root_shift": (0.0, 0.14, -0.3 if phase == "contact" else -0.22)}
    if phase == "contact":
        legs = {f"hip_{lead}": (-58, 0, 0), f"knee_{lead}": (64, 0, 0), f"ankle_{lead}": (-6, 0, 0),
                f"hip_{back}": (-10, 0, 0), f"knee_{back}": (60, 0, 0), f"ankle_{back}": (-46, 0, 0)}
    else:
        legs = {f"hip_{back}": (-74, 0, 0), f"knee_{back}": (110, 0, 0), f"ankle_{back}": (-40, 0, 0),
                f"hip_{lead}": (-30, 0, 0), f"knee_{lead}": (56, 0, 0), f"ankle_{lead}": (-26, 0, 0)}
    out = merge(base, deep, legs)
    out.update(finger_curl(30, "L"))
    out.update(finger_curl(30, "R"))
    return out


def crawl_key(i):
    """On all fours, low, like something much flatter than it should be: elbows and knees splayed
    out to the sides, belly a hand above the floor, lateral sequence (left hind, left fore, right
    hind, right fore). The spine rolls; the face stays up and on you."""
    phases = [("L", "L"), ("L", "R"), ("R", "R"), ("R", "L")]
    hind, fore = phases[i % 4]
    out = merge(finger_curl(-10, "L", 8), finger_curl(-10, "R", 8),
                {"root": (86, 0, 0), "root_pivot": (0.0, 0.0, 1.56), "root_shift": (0.0, 0.62, -1.18),
                 "neck0": (-40, 0, 0), "neck1": (-30, 0, 0), "neck2": (-34, 0, 8 if i % 2 else -8),
                 "spine2": (0, 5 if hind == "L" else -5, 3 if hind == "L" else -3),
                 "chest": (0, -5 if hind == "L" else 5, 0)})
    for side in ("L", "R"):
        s = 1.0 if side == "L" else -1.0
        fwd_h = side == hind
        out[f"hip_{side}"] = (-100 if fwd_h else -70, 34 * s, 0)
        out[f"knee_{side}"] = (120 if fwd_h else 100, 0, 0)
        out[f"ankle_{side}"] = (-30, 0, 0)
        fwd_f = side == fore
        out[f"shoulder_{side}"] = (-84 if fwd_f else -58, 52 * s, 0)
        out[f"elbow_{side}"] = (0, -96 * s, 0)
        out[f"wrist_{side}"] = (40 if fwd_f else 30, 30 * s, 0)
    return out


def lean_clip(side):
    """Six frames: standing hidden behind a wall edge, then leaning out from it a little more each
    frame, the head first, the fingers coming round the edge last."""
    peek = POSES["peek_right" if side == "R" else "peek_left"]
    hidden = merge(POSES["stand"], {"neck2": (-62, 0, 0)})
    frames = []
    for i in range(6):
        t = i / 5.0
        head_t = min(1.0, t * 1.4)
        pose = lerp_pose(hidden, peek, ease(t))
        # the head arrives before the body
        n2 = lerp_pose({"neck2": hidden["neck2"]}, {"neck2": peek["neck2"]}, ease(head_t))
        pose.update(n2)
        frames.append(pose)
    return frames


def lunge_clip():
    """Ten frames: it comes at you. Everything goes forward, the arms come up and wide, the hands
    open, the mouth opens and keeps opening, and slides down."""
    start = merge(POSES["stand"], {"jaw": 0.0})
    mid = merge(finger_curl(-6, "L", 6), finger_curl(-6, "R", 6),
                {"spine1": (8, 0, 0), "spine2": (8, 0, 0), "chest": (6, 0, 0), "neck0": (24, 0, 0), "neck2": (-64, 0, 0),
                 "shoulder_L": (-50, -20, 0), "shoulder_R": (-50, 20, 0), "elbow_L": (-30, 0, 0), "elbow_R": (-30, 0, 0),
                 "hip_L": (-30, 0, 0), "knee_L": (40, 0, 0), "hip_R": (14, 0, 0), "jaw": 22.0})
    end = merge(finger_curl(-16, "L", 12), finger_curl(-16, "R", 12),
                {"spine1": (12, 0, 0), "spine2": (10, 0, 0), "chest": (8, 0, 0), "neck0": (18, 0, 0), "neck2": (-60, 0, 0),
                 "shoulder_L": (-84, -44, 0), "shoulder_R": (-84, 44, 0), "elbow_L": (-22, 0, 0), "elbow_R": (-22, 0, 0),
                 "hip_L": (-24, 0, 0), "knee_L": (34, 0, 0), "hip_R": (12, 0, 0), "jaw": 62.0, "jaw_drop": 0.04})
    return keyed([(0, start), (4, mid), (9, end)], 10)


def rise_clip():
    """Twelve frames out of a bed: it sits up from the waist with its back straight, the way nothing
    with muscles sits up, turns, puts its feet down and stands."""
    lie = POSES["lie"]
    sit = merge(finger_curl(12, "L"), finger_curl(16, "R"),
                {"root": (-90, 0, 0), "root_pivot": (0.0, 0.0, 0.0), "root_shift": (0.0, 0.0, 0.08),
                 "pelvis": (0, 0, 0), "spine1": (84, 0, 0), "neck2": (-30, 0, 30),
                 "hip_L": (0, 0, 0), "hip_R": (0, 0, 0), "shoulder_L": (-10, -8, 0), "shoulder_R": (-10, 8, 0)})
    turn = merge(finger_curl(20, "L"), finger_curl(20, "R"),
                 {"root": (0, 0, 0), "root_pivot": (0.0, 0.0, 0.0), "root_shift": (0.0, 0.0, -0.9),
                  "hip_L": (-92, 0, 0), "hip_R": (-92, 0, 0), "knee_L": (92, 0, 0), "knee_R": (92, 0, 0),
                  "neck2": (-50, 0, 20), "shoulder_L": (-10, 0, 0), "shoulder_R": (-10, 0, 0)})
    stand = POSES["stand"]
    return keyed([(0, lie), (4, sit), (8, turn), (11, stand)], 12)


# --------------------------------------------------------------------------------------------
# Its body, out after you: the chase clips
# --------------------------------------------------------------------------------------------

def run_key(lead, phase):
    """One key of the run. Pitched forward from the hips like something falling and catching
    itself, the knees coming up far too high, the arms trailing behind it with the fingers loose,
    and the face held level and on you whatever the body is doing."""
    back = "R" if lead == "L" else "L"
    s = 1.0 if lead == "L" else -1.0
    if phase == "contact":
        legs = {f"hip_{lead}": (-62, 0, 0), f"knee_{lead}": (24, 0, 0), f"ankle_{lead}": (14, 0, 0),
                f"hip_{back}": (34, 0, 0), f"knee_{back}": (46, 0, 0), f"ankle_{back}": (-34, 0, 0)}
        shift = (0.0, 0.0, -0.20)
    else:
        legs = {f"hip_{back}": (-96, 0, 0), f"knee_{back}": (128, 0, 0), f"ankle_{back}": (-44, 0, 0),
                f"hip_{lead}": (6, 0, 0), f"knee_{lead}": (14, 0, 0), f"ankle_{lead}": (-6, 0, 0)}
        shift = (0.0, 0.0, -0.04)
    arms = {f"shoulder_{lead}": (30, 8 * s, 0), f"shoulder_{back}": (12, -8 * s, 0), f"elbow_{lead}": (-18, 0, 0),
            f"elbow_{back}": (-30, 0, 0), "wrist_L": (14, 0, 0), "wrist_R": (14, 0, 0)}
    return merge(finger_curl(-4, "L", 10), finger_curl(-4, "R", 10),
                 {"spine1": (16, 0, 0), "spine2": (16, 0, 0), "chest": (14, 0, 0), "upchest": (10, -5 * s, 6 * s),
                  "neck0": (6, 0, 0), "neck1": (-8, 0, 0), "neck2": (-62, 0, 0), "root_shift": shift, "jaw": 18},
                 legs, arms)


def climb_key(i):
    """Up a wall, flat against it: the knees out to the sides like a spider's, one long arm up over
    its head and the other pulling down, hand over hand. The wall is in front of it (-Y)."""
    up = "L" if i in (0, 1) else "R"
    down = "R" if up == "L" else "L"
    reach = i in (0, 2)
    out = merge(finger_curl(40, up), finger_curl(55, down),
                {"root": (10, 0, 0), "root_pivot": (0.0, 0.0, 1.56), "root_shift": (0.0, -0.12, 0.0),
                 "spine2": (-4, 0, 0), "chest": (-6, 0, 0), "neck0": (-10, 0, 0), "neck1": (-6, 0, 0), "neck2": (-30, 0, 0)})
    su = 1.0 if up == "L" else -1.0
    out[f"shoulder_{up}"] = (-172 if reach else -150, -12 * su, 0)
    out[f"elbow_{up}"] = (-8 if reach else -36, 0, 0)
    out[f"shoulder_{down}"] = (-118 if reach else -138, 10 * su, 0)
    out[f"elbow_{down}"] = (-62 if reach else -28, 0, 0)
    for side in ("L", "R"):
        sd = 1.0 if side == "L" else -1.0
        high = (side == down) == reach
        out[f"hip_{side}"] = (-96 if high else -52, 44 * sd, 0)
        out[f"knee_{side}"] = (118 if high else 64, 0, 0)
        out[f"ankle_{side}"] = (-36, 0, 0)
    return out


def leap_clip():
    """Six frames off the ground: down into a crouch, launched, and then long and flat through the
    air at you, every limb reaching."""
    crouch = merge(finger_curl(20, "L"), finger_curl(20, "R"),
                   {"hip_L": (-96, -10, 0), "hip_R": (-96, 10, 0), "knee_L": (128, 0, 0), "knee_R": (128, 0, 0),
                    "ankle_L": (-30, 0, 0), "ankle_R": (-30, 0, 0), "spine1": (28, 0, 0), "spine2": (20, 0, 0), "chest": (10, 0, 0),
                    "neck2": (-80, 0, 0), "shoulder_L": (40, 0, 0), "shoulder_R": (40, 0, 0), "root_shift": (0.0, 0.1, -0.7), "jaw": 10})
    launch = merge(finger_curl(-10, "L", 10), finger_curl(-10, "R", 10),
                   {"root": (40, 0, 0), "root_pivot": (0.0, 0.0, 1.56), "hip_L": (20, 0, 0), "hip_R": (30, 0, 0),
                    "knee_L": (20, 0, 0), "knee_R": (30, 0, 0), "ankle_L": (40, 0, 0), "ankle_R": (40, 0, 0),
                    "neck2": (-90, 0, 0), "shoulder_L": (-120, -20, 0), "shoulder_R": (-120, 20, 0), "jaw": 40})
    fly = merge(finger_curl(-20, "L", 16), finger_curl(-20, "R", 16),
                {"root": (72, 0, 0), "root_pivot": (0.0, 0.0, 1.56), "hip_L": (24, 6, 0), "hip_R": (34, -6, 0),
                 "knee_L": (50, 0, 0), "knee_R": (30, 0, 0), "ankle_L": (40, 0, 0), "ankle_R": (40, 0, 0),
                 "spine2": (-6, 0, 0), "neck0": (-20, 0, 0), "neck2": (-96, 0, 0),
                 "shoulder_L": (-160, -26, 0), "shoulder_R": (-160, 26, 0), "elbow_L": (-6, 0, 0), "elbow_R": (-6, 0, 0),
                 "jaw": 70, "jaw_drop": 0.04})
    return keyed([(0, crouch), (2, launch), (4, fly), (5, fly)], 6)


HOLD = merge(finger_curl(70, "L"), finger_curl(70, "R"),
             {"spine1": (6, 0, 0), "spine2": (8, 0, 0), "chest": (6, 0, 0), "neck0": (20, 0, 0), "neck1": (6, 0, 0), "neck2": (-44, 0, 0),
              "shoulder_L": (-74, -14, 0), "shoulder_R": (-74, 14, 0), "elbow_L": (-48, 0, 0), "elbow_R": (-48, 0, 0),
              "wrist_L": (-10, 0, 0), "wrist_R": (-10, 0, 0),
              "hip_L": (-10, 0, 0), "hip_R": (-4, 0, 0), "knee_L": (18, 0, 0), "knee_R": (10, 0, 0), "jaw": 24})


def throw_clip():
    """Six frames: a door, or you, thrown aside. Wound back, round, and through."""
    wind = merge(finger_curl(64, "R"), finger_curl(30, "L"),
                 {"upchest": (6, 0, 30), "chest": (6, 0, 14), "neck2": (-60, 0, -30),
                  "shoulder_R": (36, -60, 0), "elbow_R": (-30, 0, 0), "shoulder_L": (-30, 10, 0),
                  "hip_L": (-20, 0, 0), "knee_L": (20, 0, 0), "hip_R": (10, 0, 0), "knee_R": (14, 0, 0)})
    swing = merge(finger_curl(20, "R"), finger_curl(30, "L"),
                  {"upchest": (14, 0, -24), "chest": (10, 0, -10), "neck2": (-58, 0, 16),
                   "shoulder_R": (-104, 30, 0), "elbow_R": (-10, 0, 0), "shoulder_L": (20, -10, 0),
                   "hip_L": (-30, 0, 0), "knee_L": (30, 0, 0), "hip_R": (16, 0, 0), "knee_R": (20, 0, 0), "jaw": 30})
    through = merge(finger_curl(-10, "R", 10), finger_curl(30, "L"),
                    {"upchest": (18, 0, -40), "chest": (12, 0, -16), "neck2": (-54, 0, 26),
                     "shoulder_R": (-60, 70, 0), "elbow_R": (-6, 0, 0), "shoulder_L": (30, -10, 0),
                     "hip_L": (-34, 0, 0), "knee_L": (34, 0, 0), "hip_R": (20, 0, 0), "knee_R": (24, 0, 0), "jaw": 20})
    return keyed([(0, wind), (2, swing), (4, through), (5, through)], 6)


def scream_clip():
    """Six frames: the head goes back, further than a neck goes, the chin piece drops all the way,
    the arms open wide and back, the fingers spread."""
    start = merge(POSES["stand"], {"jaw": 6})
    top = merge(finger_curl(-24, "L", 18), finger_curl(-24, "R", 18),
                {"spine2": (-4, 0, 0), "chest": (-8, 0, 0), "upchest": (-10, 0, 0),
                 "neck0": (-14, 0, 0), "neck1": (-24, 0, 0), "neck2": (-118, 0, 0),
                 "shoulder_L": (24, -70, 0), "shoulder_R": (24, 70, 0), "elbow_L": (-14, 0, 0), "elbow_R": (-14, 0, 0),
                 "hip_L": (-6, 0, 0), "hip_R": (-6, 0, 0), "knee_L": (10, 0, 0), "knee_R": (10, 0, 0),
                 "jaw": 110, "jaw_drop": 0.06})
    return keyed([(0, start), (3, top), (5, top)], 6)


def stunned_clip():
    """Six frames, blinded: it reels back, the hands come up over the card, the head turns away
    and down, the knees give."""
    start = merge(POSES["stand"], {})
    covered = merge(finger_curl(30, "L", 6), finger_curl(30, "R", 6),
                    {"spine1": (-8, 0, 0), "spine2": (-6, 0, 0), "chest": (10, 0, 12), "upchest": (10, 0, 8),
                     "neck0": (30, 0, 0), "neck1": (20, 0, 0), "neck2": (-30, 18, 40),
                     "shoulder_L": (-136, -30, 0), "shoulder_R": (-128, 34, 0), "elbow_L": (-112, 0, 0), "elbow_R": (-120, 0, 0),
                     "hip_L": (-40, 0, 0), "hip_R": (-30, 0, 0), "knee_L": (60, 0, 0), "knee_R": (48, 0, 0),
                     "ankle_L": (-20, 0, 0), "ankle_R": (-18, 0, 0), "root_shift": (0.0, 0.18, -0.25), "jaw": 30})
    return keyed([(0, start), (3, covered), (5, covered)], 6)


# Hanging off a ledge by its fingers, its face just over the edge. The entity stands at the level
# of the ledge: everything hangs down from there.
LEDGE = merge(finger_curl(84, "L"), finger_curl(84, "R"),
              {"root_shift": (0.0, -0.3, -2.86), "neck0": (0, 0, 0), "neck1": (0, 0, 0), "neck2": (-60, 0, 0),
               "shoulder_L": (-85, -8, 0), "shoulder_R": (-85, 8, 0), "elbow_L": (-130, 0, 0), "elbow_R": (-130, 0, 0),
               "wrist_L": (40, 0, 0), "wrist_R": (40, 0, 0),
               "hip_L": (-14, 6, 0), "hip_R": (-4, -6, 0), "knee_L": (20, 0, 0), "knee_R": (12, 0, 0)})


def swim_key(i):
    """Under the water, long and flat, pulling itself at you with its arms, the legs trailing."""
    lead = "L" if i < 2 else "R"
    other = "R" if lead == "L" else "L"
    reach = i % 2 == 0
    out = merge(finger_curl(-10, "L", 12), finger_curl(-10, "R", 12),
                {"root": (88, 0, 0), "root_pivot": (0.0, 0.0, 1.56), "root_shift": (0.0, 0.5, -1.2),
                 "neck0": (-30, 0, 0), "neck1": (-30, 0, 0), "neck2": (-40, 0, 0), "jaw": 30})
    out[f"shoulder_{lead}"] = (-176 if reach else -120, 0, 0)
    out[f"shoulder_{other}"] = (-40 if reach else -100, 0, 0)
    out[f"elbow_{lead}"] = (-6 if reach else -40, 0, 0)
    out[f"elbow_{other}"] = (-20, 0, 0)
    out["hip_L"] = (14 if i % 2 else -14, 6, 0)
    out["hip_R"] = (-14 if i % 2 else 14, -6, 0)
    out["knee_L"] = (20, 0, 0)
    out["knee_R"] = (20, 0, 0)
    out["ankle_L"] = (40, 0, 0)
    out["ankle_R"] = (40, 0, 0)
    return out


GROUNDED = ("stand", "stoop", "crouch", "peek_right", "peek_left", "reach", "bedside", "walk", "stoop_walk", "crawl",
            "lunge", "lean_right", "lean_left", "run", "hold", "throw", "scream", "stunned")
CONTACT = ("toe_", "heel_", "foot_", "ankle_")
CONTACT_HANDS = ("f0k4_", "f1k4_", "f2k4_", "f3k4_", "palm_")


def grounded(joints, pose, hands=False, sole=0.012):
    """Shifts the pose up or down so its lowest foot (or hand, crawling) rests on the floor."""
    mats = joint_transforms(joints, pose)
    names = CONTACT + (CONTACT_HANDS if hands else ())
    zs = [(mats[j.name] @ np.append(j.pos, 1.0))[2] for j in joints if j.name.startswith(names)]
    if not zs:
        return pose
    dz = sole - min(zs)
    out = dict(pose)
    sx, sy, sz = out.get("root_shift", (0.0, 0.0, 0.0))
    out["root_shift"] = (sx, sy, sz + dz)
    return out


def clips(joints=None):
    """name -> (fps, loop, [pose per frame]). Names match CreaturePose in the mod. With joints given,
    every grounded clip is put on the floor frame by frame."""
    out = _clips()
    if joints is None:
        return out
    for name in GROUNDED:
        if name in out:
            fps, loop, frames = out[name]
            out[name] = (fps, loop, [grounded(joints, f, hands=name == "crawl") for f in frames])
    return out


def _clips():
    out = {name: (1.0, False, [pose]) for name, pose in POSES.items()}
    out["walk"] = (8.0, True, keyed([(0, walk_key("L", "contact")), (2, walk_key("L", "passing")),
                                     (4, walk_key("R", "contact")), (6, walk_key("R", "passing"))], 8, loop=True))
    out["stoop_walk"] = (8.0, True, keyed([(0, stoop_key("L", "contact")), (2, stoop_key("L", "passing")),
                                           (4, stoop_key("R", "contact")), (6, stoop_key("R", "passing"))], 8, loop=True))
    out["crawl"] = (8.0, True, keyed([(0, crawl_key(0)), (2, crawl_key(1)), (4, crawl_key(2)), (6, crawl_key(3))], 8, loop=True))
    out["lunge"] = (12.0, False, lunge_clip())
    out["rise"] = (8.0, False, rise_clip())
    out["lean_right"] = (6.0, False, lean_clip("R"))
    out["lean_left"] = (6.0, False, lean_clip("L"))
    # Its body, out after you. The order matches CreaturePose.
    out["run"] = (10.0, True, keyed([(0, run_key("L", "contact")), (2, run_key("L", "passing")),
                                     (4, run_key("R", "contact")), (6, run_key("R", "passing"))], 8, loop=True))
    out["climb"] = (8.0, True, keyed([(0, climb_key(0)), (2, climb_key(1)), (4, climb_key(2)), (6, climb_key(3))], 8, loop=True))
    out["leap"] = (10.0, False, leap_clip())
    out["hold"] = (1.0, False, [HOLD])
    out["throw"] = (12.0, False, throw_clip())
    out["scream"] = (10.0, False, scream_clip())
    out["stunned"] = (10.0, False, stunned_clip())
    out["ledge"] = (1.0, False, [LEDGE])
    out["swim"] = (8.0, True, keyed([(0, swim_key(0)), (2, swim_key(1)), (4, swim_key(2)), (6, swim_key(3))], 8, loop=True))
    return out
