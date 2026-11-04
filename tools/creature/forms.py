"""The creature's forms: the same puppet, rebuilt a little worse every time its temper rises.

Reputation.Form in the mod has four steps: WATCHER (the base creature in poses.py), UNEASY, ANGRY
and FURIOUS. Each later form is a variant of the base skeleton with parts added, moved or
replaced, its own card (or cards), and its own poses. Pure numpy, like poses.py: the concept
sheets (tools/concepts/draw_forms.py) and the Blender build both read this file.

A variant gives:
    skeleton()   the body joints (Blender space, Z up, facing -Y, blocks), parents first
    cards()      the cards it wears: which joint each rides, where, how it is cut into pieces
    material(n)  how a joint's bone is drawn: "black", "pale", "metal", or None (not drawn)
    poses()      named poses: "stand", "move", "attack", "special", ...
"""
import math

import numpy as np

import poses as P
from poses import Joint, merge

PALE = ("f0", "f1", "f2", "f3", "f4", "thumb", "palm")
MIDS = ("uarm", "farm", "thigh", "shin", "meta")


# --------------------------------------------------------------------------------------------
# Skeleton surgery
# --------------------------------------------------------------------------------------------

def index(joints):
    return {j.name: j for j in joints}


def subtree(joints, root):
    names = {root}
    for j in joints:
        if j.parent in names:
            names.add(j.name)
    return names


def shift(joints, root, d):
    """Moves a joint and everything hanging from it."""
    d = np.asarray(d, float)
    for n in subtree(joints, root):
        index(joints)[n].pos = index(joints)[n].pos + d


def place(joints, after, joint):
    """Inserts a joint into the list right after another (the list stays parents-first)."""
    names = [j.name for j in joints]
    joints.insert(names.index(after) + 1, joint)
    return joint


def add(joints, name, pos, radius, parent):
    j = Joint(name, pos, radius, parent)
    joints.append(j)
    return j


def remove(joints, root):
    gone = subtree(joints, root)
    joints[:] = [j for j in joints if j.name not in gone]


def vertebra(joints, after, name, d, radius=None):
    """A new joint between `after` and its children, the children and all below moved by d."""
    J = index(joints)
    base = J[after]
    children = [j for j in joints if j.parent == after]
    for c in children:
        shift(joints, c.name, d)
    new = Joint(name, base.pos + np.asarray(d, float), radius or base.radius, after)
    place(joints, after, new)
    for c in children:
        c.parent = name
    return new


def scale_from(joints, root, factor, about=None):
    """Lengthens (or shortens) everything below `root` about the root's parent (or a point)."""
    J = index(joints)
    o = J[J[root].parent].pos if about is None else np.asarray(about, float)
    for n in subtree(joints, root):
        J[n].pos = o + (J[n].pos - o) * factor


def thin(joints, names, factor):
    for j in joints:
        if j.name in names:
            j.radius = (j.radius[0] * factor, j.radius[1] * factor)


def unit(v):
    v = np.asarray(v, float)
    return v / (np.linalg.norm(v) + 1e-12)


def hand(joints, tag, wrist, d, side, scale=1.0, fingers=4, lengths=(0.94, 1.0, 0.96, 0.82, 0.74), splay=0.25,
         thumb=True, segments=P.FINGER_SEGMENTS, fat=1.0):
    """A pale hand on a wrist: the palm and fingers along d, the knuckles in a row along `side`,
    the thumb on the -side edge. Names follow the base hand (palm_X, fNkM_X, thumbN_X)."""
    J = index(joints)
    w = J[wrist].pos
    d, side = unit(d), unit(side)
    s = scale
    add(joints, f"palm_{tag}", w + d * 0.11 * s, (0.030 * s * fat, 0.012 * s * fat), wrist)
    for f in range(fingers):
        off = (f - (fingers - 1) / 2.0) * 0.0197 * s
        p = w + d * 0.145 * s + side * off
        fd = unit(d + side * off / s * splay * 4)
        prev = f"palm_{tag}"
        add(joints, f"f{f}k0_{tag}", p, 0.0088 * s * fat, prev)
        prev = f"f{f}k0_{tag}"
        for k, seg in enumerate(segments):
            seg *= lengths[f % len(lengths)] * s
            mid, end = p + fd * seg * 0.5, p + fd * seg
            add(joints, f"f{f}m{k}_{tag}", mid, (0.0058 - k * 0.0006) * s * fat, prev)
            tip = k == len(segments) - 1
            add(joints, f"f{f}k{k + 1}_{tag}", end, (0.0024 if tip else 0.0082 - k * 0.0008) * s * fat, f"f{f}m{k}_{tag}")
            prev = f"f{f}k{k + 1}_{tag}"
            p = end
    if thumb:
        p = w + d * 0.035 * s - side * 0.046 * s
        td = unit(d - side * 0.3)
        add(joints, f"thumb0_{tag}", p, 0.0086 * s * fat, wrist)
        for k, (seg, r) in enumerate(((0.104, 0.0076), (0.094, 0.0058), (0.072, 0.0024))):
            p = p + td * seg * s
            add(joints, f"thumb{k + 1}_{tag}", p, r * s * fat, f"thumb{k}_{tag}")


def arm(joints, tag, parent, clav, shoulder, elbow, wrist, d, side, radii=(0.032, 0.038, 0.019, 0.029, 0.017, 0.021),
        hand_scale=1.0, fingers=4, lengths=(0.94, 1.0, 0.96, 0.82, 0.74), fat=1.0):
    clav, shoulder, elbow, wrist = (np.asarray(p, float) for p in (clav, shoulder, elbow, wrist))
    add(joints, f"clav_{tag}", clav, radii[0], parent)
    add(joints, f"shoulder_{tag}", shoulder, radii[1], f"clav_{tag}")
    add(joints, f"uarm_{tag}", (shoulder + elbow) / 2, radii[2], f"shoulder_{tag}")
    add(joints, f"elbow_{tag}", elbow, radii[3], f"uarm_{tag}")
    add(joints, f"farm_{tag}", (elbow + wrist) / 2, radii[4], f"elbow_{tag}")
    add(joints, f"wrist_{tag}", wrist, radii[5], f"farm_{tag}")
    hand(joints, tag, f"wrist_{tag}", d, side, hand_scale, fingers, lengths, fat=fat)


# --------------------------------------------------------------------------------------------
# Posing helpers
# --------------------------------------------------------------------------------------------

def axis_euler(axis, degrees):
    """A rotation about any axis, as the (rx, ry, rz) world-axis euler angles poses use."""
    a = unit(axis)
    t = math.radians(degrees)
    K = np.array([[0, -a[2], a[1]], [a[2], 0, -a[0]], [-a[1], a[0], 0]])
    R = np.eye(3) + math.sin(t) * K + (1 - math.cos(t)) * K @ K
    b = -math.asin(max(-1.0, min(1.0, R[2, 0])))
    ax = math.atan2(R[2, 1], R[2, 2])
    c = math.atan2(R[1, 0], R[0, 0])
    return (math.degrees(ax), math.degrees(b), math.degrees(c))


def euler_matrix(e):
    return P.euler(*e)


def compose(*eulers):
    """Euler of applying the rotations right to left (like matrices)."""
    R = np.eye(3)
    for e in eulers:
        R = R @ P.euler(*e)
    b = -math.asin(max(-1.0, min(1.0, R[2, 0])))
    return (math.degrees(math.atan2(R[2, 1], R[2, 2])), math.degrees(b), math.degrees(math.atan2(R[1, 0], R[0, 0])))


def curl(tag, amount, axis, fingers=4, spread=0.0, thumb=0.5):
    """Curls a hand's fingers about a world axis (at rest), spreading the extra knuckle too."""
    pose = {}
    for f in range(fingers):
        fan = (f - (fingers - 1) / 2.0) * spread
        pose[f"f{f}k0_{tag}"] = compose(axis_euler(axis, amount * 0.4), (fan, 0, 0))
        pose[f"f{f}k1_{tag}"] = axis_euler(axis, amount * 0.7)
        pose[f"f{f}k2_{tag}"] = axis_euler(axis, amount * 0.85)
        pose[f"f{f}k3_{tag}"] = axis_euler(axis, amount * 0.75)
    pose[f"thumb1_{tag}"] = axis_euler(axis, amount * thumb)
    return pose


def head_joints(center, parent="neck2", suffix=""):
    """Jaw, eyes and lids for a card centred at `center` (at rest), riding `parent`."""
    out = [Joint(f"jaw{suffix}", center + P.JAW_HINGE_LOCAL, 0.01, parent)]
    for side, e in (("L", P.EYES_LOCAL[0]), ("R", P.EYES_LOCAL[1])):
        out.append(Joint(f"eye{suffix}_{side}", center + e + P.EYE_BACK, 0.01, parent))
        out.append(Joint(f"lid{suffix}_{side}", center + e + P.EYE_BACK, 0.01, parent))
    return out


def rot4(axis, degrees, about=(0.0, 0.0, 0.0)):
    """A 4x4 rotation about an axis through a point (card-local)."""
    R = P.euler(*axis_euler(axis, degrees))
    return P.about(about, R)


def move4(d):
    T = np.eye(4)
    T[:3, 3] = d
    return T


# --------------------------------------------------------------------------------------------
# Cards
# --------------------------------------------------------------------------------------------

class Piece:
    """A rectangle of the card's art (pixels u0..u1 across, v0..v1 down), moved by T (4x4,
    card-local: x across to the creature's left, y back, z up) before the card is placed."""

    def __init__(self, u0, v0, u1, v1, T=None):
        self.u0, self.v0, self.u1, self.v1 = u0, v0, u1, v1
        self.T = np.eye(4) if T is None else T


class Card:
    """An index card riding a joint. center: its centre at rest (Blender space); R: its rest
    orientation (columns: across, normal pointing back, up); half: half-size; art: what is
    painted on it (see draw_forms.card_art); pieces: how it is cut, or None for whole."""

    def __init__(self, joint="neck2", center=None, R=None, half=None, art=None, pieces=None, back=None, scale=1.0):
        self.joint = joint
        self.center = np.array(P.HEAD_CENTER if center is None else center, float)
        self.R = np.eye(3) if R is None else np.asarray(R, float)
        self.half = np.array(P.CARD if half is None else half, float) * scale
        self.art = art or {"kind": "face", "params": {}}
        self.pieces = pieces
        self.back = back or {"kind": "back"}


def card_basis(normal, up=(0.0, 0.0, 1.0)):
    """Rest orientation for a card whose front faces along -normal... i.e. looks out along `out`."""
    out = unit(normal)
    n = -out  # the card's +y (back) points away from where it looks
    u = unit(np.asarray(up, float) - np.dot(up, n) * n)
    x = unit(np.cross(n, u))
    # columns: x across (to the creature's left when it looks along -Y), y back, z up
    if np.dot(np.cross(x, n), u) < 0:
        x = -x
    return np.stack([x, n, u], axis=1)


def look_basis(out, up=(0.0, 0.0, 1.0)):
    """A card looking out along `out` (unit), its top toward `up`."""
    out = unit(out)
    y = -out
    z = unit(np.asarray(up, float) - np.dot(up, y) * y)
    x = np.cross(y, z)
    return np.stack([x, y, z], axis=1)


# --------------------------------------------------------------------------------------------
# The variants
# --------------------------------------------------------------------------------------------

FACE = {
    # the faces each temper wears (face_art.face parameters) plus what has been done to the card
    "uneasy": {"params": dict(lids=(0.15, 0.2), look=((0.0, 0.05), (0.05, 0.05)), wet=1.0, wear=0.35, seed=12, number="32"),
               "marks": ["crayon"]},
    "angry": {"params": dict(lids=(0.05, 0.1), look=((0.0, 0.0), (0.0, 0.0)), wet=1.2, wear=0.7, blood=0.6, seed=13, number="33"),
              "marks": ["crayon_scribbled"]},
    "furious": {"params": dict(lids=(0.0, 0.0), look=((0.0, 0.0), (0.0, 0.0)), wet=1.5, wear=1.0, blood=1.0, seed=14, number="34"),
                "marks": ["crayon_scribbled", "torn_eyes"]},
}


class Variant:
    key = ""
    form = ""
    name = ""
    lines = []

    def skeleton(self):
        return P.skeleton()

    def head_center(self):
        return P.HEAD_CENTER.copy()

    def heads(self):
        """(card centre, joint) of each head that has eyes and a jaw."""
        return [(self.head_center(), "neck2", "")]

    def rig(self):
        out = self.skeleton()
        for center, joint, suffix in self.heads():
            out += head_joints(center, joint, suffix)
        return out

    def cards(self):
        f = FACE[self.form]
        return [Card("neck2", self.head_center(), art={"kind": "face", "params": f["params"], "marks": f["marks"]})]

    def material(self, name):
        if name.startswith(PALE):
            return "pale"
        return "black"

    def ball(self, name):
        if name.startswith(MIDS):
            return None
        if name.startswith(("shoulder", "elbow", "wrist", "hip_", "knee", "ankle", "neck", "spine", "clav", "hock")):
            return 1.25
        return None

    def poses(self):
        return {"stand": P.POSES["stand"]}

    def contact(self):
        """Joint-name prefixes that rest on the floor in each pose (for grounding)."""
        return P.CONTACT


def base_arms_down(J, d=0.0):
    for s in ("L", "R"):
        shift(J, f"wrist_{s}", (0, 0, -d))
        index(J)[f"farm_{s}"].pos = (index(J)[f"elbow_{s}"].pos + index(J)[f"wrist_{s}"].pos) / 2


# ---------------------------------------------------------------- UNEASY ----------------------

class Rigged(Variant):
    key, form, name = "u1", "uneasy", "Rigged"
    lines = ["UNEASY, variant 1: the rig shows. A stop-motion rig arm is bolted between its shoulder blades,",
             "a jointed black-metal arm that goes up and back into the dark and is never there in the next frame.",
             "It holds it: it leans further than anything standing could, its toes trailing on the floor.",
             "One more vertebra in the neck; the card folded down the middle and flattened out again."]

    def skeleton(self):
        J = P.skeleton()
        vertebra(J, "neck1", "neck1b", (0.0, -0.02, 0.17), 0.021)
        add(J, "rig0", (0.0, 0.105, 2.25), 0.03, "upchest")
        return J

    def head_center(self):
        return P.HEAD_CENTER + np.array([0.0, -0.02, 0.17])

    def cards(self):
        f = FACE[self.form]
        W, H = 220, 380
        fold = 17.0
        pieces = [Piece(0, 0, W // 2, H + 150, rot4((0, 0, 1), fold)),
                  Piece(W // 2, 0, W, H + 150, rot4((0, 0, 1), -fold))]
        return [Card("neck2", self.head_center(), art={"kind": "face", "params": f["params"], "marks": ["crayon", "fold"]},
                     pieces=pieces)]

    def material(self, name):
        if name.startswith("rig"):
            return "metal"
        return super().material(name)

    def poses(self):
        stand = merge(P.POSES["stand"], P.finger_curl(10, "L"), P.finger_curl(14, "R"), {
            # leaning out over its toes from the ankles, which nothing standing could do; the head
            # lolls over to one side the way a hung thing's does
            "root": (20, 0, 0), "root_pivot": (0.0, -0.12, 0.0),
            "ankle_L": (52, 0, 0), "ankle_R": (46, 0, 0), "knee_L": (6, 0, 0), "knee_R": (10, 0, 0),
            "hip_L": (-4, 0, 0), "hip_R": (0, 0, 0),
            "spine2": (2, 0, 0), "chest": (2, 0, 0), "upchest": (4, -3, 0),
            "neck0": (6, 0, 0), "neck1": (2, 16, 0), "neck1b": (0, 18, 0), "neck2": (-58, 30, 16),
            "shoulder_L": (-20, -4, 0), "shoulder_R": (-18, 4, 0), "elbow_L": (-4, 0, 0), "elbow_R": (-2, 0, 0)})
        move = merge(stand, {
            # carried: the legs hang and trail, the toes dragging behind it
            "root": (30, 0, 4), "hip_L": (16, 0, 0), "knee_L": (24, 0, 0), "ankle_L": (62, 0, 0),
            "hip_R": (2, 0, 0), "knee_R": (14, 0, 0), "ankle_R": (60, 0, 0),
            "shoulder_L": (-24, -4, 0), "shoulder_R": (-20, 4, 0), "neck1": (0, 6, 0), "neck1b": (0, 8, 0), "neck2": (-64, 10, 0)})
        attack = merge(P.finger_curl(-12, "L", 10), P.finger_curl(-12, "R", 10), {
            # swung at you on the rig, feet off the floor, everything reaching
            "root": (48, 0, 0), "root_pivot": (0.0, 0.0, 1.56),
            "hip_L": (24, 0, 0), "hip_R": (30, 0, 0), "knee_L": (40, 0, 0), "knee_R": (26, 0, 0), "ankle_L": (60, 0, 0), "ankle_R": (60, 0, 0),
            "neck0": (-6, 0, 0), "neck1": (-8, 0, 0), "neck1b": (-6, 0, 0), "neck2": (-58, 0, 0),
            "shoulder_L": (-120, -16, 0), "shoulder_R": (-120, 16, 0), "elbow_L": (-10, 0, 0), "elbow_R": (-10, 0, 0), "jaw": 50})
        special = merge(P.POSES["stand"], P.finger_curl(20, "L"), P.finger_curl(50, "R"), {
            # round a door frame at an angle nothing could hold, its feet still out in the hall
            "root": (0, -46, 0), "root_pivot": (0.0, 0.0, 0.0),
            "ankle_L": (40, 0, 0), "ankle_R": (44, 0, 0), "hip_L": (0, 8, 0), "hip_R": (0, 4, 0),
            "neck0": (10, 8, 0), "neck1": (6, 12, 0), "neck1b": (4, 10, 0), "neck2": (-56, 22, 0),
            "shoulder_R": (-40, 60, 0), "elbow_R": (-40, 0, 0), "shoulder_L": (0, 16, 0)})
        return {"stand": stand, "move": move, "attack": attack, "special": special}

    def rig_path(self, anchor):
        """World points of the rig arm from the plate on its back up into the dark (concept only:
        the arm's far end is fixed in the world while the puppet moves on the end of it)."""
        a = np.asarray(anchor, float)
        return [a, a + np.array([0.0, 0.42, 0.42]), a + np.array([0.0, 0.66, 1.25]), a + np.array([0.0, 0.42, 2.05]),
                a + np.array([0.0, 0.95, 3.4])]


class SecondPair(Variant):
    key, form, name = "u2", "uneasy", "Second pair"
    lines = ["UNEASY, variant 2: a second pair of arms has come through under its ribs, thin, with small pale",
             "hands like a child's, held up in front of its chest, wringing and picking at each other and never still.",
             "They are the ones that knock. The long arms hang where they always did, a little longer than before."]

    def skeleton(self):
        J = P.skeleton()
        base_arms_down(J, 0.12)
        for side, s in (("L", 1.0), ("R", -1.0)):
            tag = side + "2"
            arm(J, tag, "spine2", (0.044 * s, 0.006, 1.97), (0.112 * s, -0.012, 1.95), (0.150 * s, -0.090, 1.80),
                (0.040 * s, -0.200, 2.02), d=(-s * 0.55, -0.25, 0.8), side=(s * 0.3, -0.9, 0.0),
                radii=(0.022, 0.026, 0.013, 0.020, 0.012, 0.014), hand_scale=0.72, lengths=(0.86, 1.0, 0.95, 0.8))
        return J

    def poses(self):
        def wring(t):
            p = {}
            for side, s in (("L", 1.0), ("R", -1.0)):
                tag = side + "2"
                p.update(curl(tag, 70 + 30 * math.sin(t + (0 if s > 0 else 1.7)), (0.0, s * 0.3, -s), spread=8))
                p[f"wrist_{tag}"] = (14 * math.sin(t * 2 + s), 0, 18 * s * math.cos(t))
            return p
        stand = merge(P.POSES["stand"], wring(0.0))
        move = merge(P.walk_key("L", "passing"), wring(1.2))
        attack = merge(P.lunge_clip()[9], curl("L2", -20, (0, 0, -1), spread=14), curl("R2", -20, (0, 0, 1), spread=14), {
            "shoulder_L2": (-50, -10, 0), "shoulder_R2": (-50, 10, 0), "elbow_L2": (0, 0, -60), "elbow_R2": (0, 0, 60)})
        special = merge(P.POSES["stand"], P.finger_curl(14, "L"), P.finger_curl(20, "R"), curl("L2", 80, (0, 0.3, -1), spread=0),
                        curl("R2", 50, (0, -0.3, 1)), {
            # one small hand up at the door, knocking; the other holding the first one's wrist
            "spine1": (6, 0, 0), "chest": (10, 0, 0), "neck2": (-62, 6, 0),
            "shoulder_L2": (-60, 0, 0), "elbow_L2": (0, 0, -50), "wrist_L2": (30, 0, 0),
            "shoulder_R2": (-20, 0, 0), "elbow_R2": (0, 0, 40)})
        return {"stand": stand, "move": move, "attack": attack, "special": special}


class Reversed(Variant):
    key, form, name = "u3", "uneasy", "Reversed"
    lines = ["UNEASY, variant 3: its knees bend the wrong way now. The legs have been rebuilt like a bird's,",
             "up on its toes with the hock behind it, and it has a hump between its shoulders. The card is on",
             "sideways, as if it was put back on in a hurry, and its head moves like a bird's: still, then somewhere else."]

    def skeleton(self):
        J = P.skeleton()
        for side, s in (("L", 1.0), ("R", -1.0)):
            remove(J, f"thigh_{side}")
            hip = f"hip_{side}"
            pts = {"thigh": (0.082 * s, -0.08, 1.33), "knee": (0.088 * s, -0.17, 1.12), "shin": (0.092 * s, 0.02, 0.84),
                   "hock": (0.094 * s, 0.22, 0.55), "meta": (0.092 * s, 0.10, 0.31), "ankle": (0.090 * s, -0.02, 0.07)}
            radius = {"thigh": 0.030, "knee": (0.036, 0.032), "shin": 0.021, "hock": (0.030, 0.026), "meta": 0.017,
                      "ankle": (0.020, 0.018)}
            prev = hip
            for part in ("thigh", "knee", "shin", "hock", "meta", "ankle"):
                add(J, f"{part}_{side}", pts[part], radius[part], prev)
                prev = f"{part}_{side}"
            # long toes splayed forward, and one back, like a bird's
            for k, (dx, dy) in enumerate(((-0.05, -0.20), (0.0, -0.24), (0.05, -0.20))):
                add(J, f"toe{k}a_{side}", (0.090 * s + dx * 0.5, -0.02 + dy * 0.5, 0.03), 0.012, f"ankle_{side}")
                add(J, f"toe{k}_{side}", (0.090 * s + dx, -0.02 + dy, 0.012), 0.006, f"toe{k}a_{side}")
            add(J, f"spur_{side}", (0.090 * s, 0.10, 0.03), 0.008, f"ankle_{side}")
        # the hump
        add(J, "hump", (0.0, 0.13, 2.27), (0.085, 0.07), "upchest")
        return J

    def cards(self):
        f = FACE[self.form]
        W, H = 220, 380
        return [Card("neck2", self.head_center(), art={"kind": "face", "params": f["params"], "marks": ["crayon"]},
                     pieces=[Piece(0, 0, W, H + 150, rot4((0, 1, 0), 72, (0.0, 0.0, 0.05)))])]

    def ball(self, name):
        if name == "hump":
            return None
        return super().ball(name)

    def contact(self):
        return ("toe", "ankle", "spur")

    def poses(self):
        stand = merge(P.finger_curl(24, "L"), P.finger_curl(30, "R"), {
            "spine1": (10, 0, 0), "spine2": (12, 0, 0), "chest": (14, 0, 0), "upchest": (10, 0, 0),
            "neck0": (40, 0, 0), "neck1": (10, 0, 0), "neck2": (-64, 0, 10),
            "hip_L": (-12, 0, 0), "hip_R": (-8, 0, 0), "knee_L": (-12, 0, 0), "knee_R": (-8, 0, 0),
            "hock_L": (14, 0, 0), "hock_R": (10, 0, 0), "ankle_L": (-4, 0, 0), "ankle_R": (-4, 0, 0),
            "shoulder_L": (-8, -4, 0), "shoulder_R": (-6, 4, 0), "elbow_L": (-14, 0, 0), "elbow_R": (-10, 0, 0)})
        move = merge(stand, {
            # the reversed step: the knee comes up and forward, the foot folds under, the head stays put
            "hip_L": (-60, 0, 0), "knee_L": (-10, 0, 0), "hock_L": (70, 0, 0), "ankle_L": (-40, 0, 0),
            "hip_R": (18, 0, 0), "knee_R": (-4, 0, 0), "hock_R": (0, 0, 0), "ankle_R": (6, 0, 0),
            "neck0": (52, 0, 0), "neck2": (-74, 0, 0)})
        attack = merge(stand, P.finger_curl(-10, "L", 8), P.finger_curl(-10, "R", 8), {
            # the peck: the neck straight out at you, the body behind it, the arms back
            "spine1": (30, 0, 0), "spine2": (16, 0, 0), "chest": (10, 0, 0), "neck0": (30, 0, 0), "neck1": (6, 0, 0),
            "neck2": (-48, 0, 0), "shoulder_L": (40, -10, 0), "shoulder_R": (40, 10, 0), "jaw": 60,
            "hip_L": (-40, 0, 0), "hock_L": (40, 0, 0), "ankle_L": (-16, 0, 0)})
        special = merge(P.finger_curl(70, "L"), P.finger_curl(70, "R"), {
            # perched on the ridge of your roof, folded up on its reversed legs, hands between its feet
            "spine1": (40, 0, 0), "spine2": (20, 0, 0), "chest": (10, 0, 0), "neck0": (30, 0, 0), "neck2": (-70, 0, -14),
            "hip_L": (-110, -10, 0), "hip_R": (-110, 10, 0), "knee_L": (-30, 0, 0), "knee_R": (-30, 0, 0),
            "hock_L": (150, 0, 0), "hock_R": (150, 0, 0), "ankle_L": (-30, 0, 0), "ankle_R": (-30, 0, 0),
            "shoulder_L": (-40, -6, 0), "shoulder_R": (-40, 6, 0), "elbow_L": (-20, 0, 0), "elbow_R": (-20, 0, 0)})
        return {"stand": stand, "move": move, "attack": attack, "special": special}


# ---------------------------------------------------------------- ANGRY -----------------------

RIBS = 5


class Opened(Variant):
    key, form, name = "a1", "angry", "Opened"
    lines = ["ANGRY, variant 1: it has opened itself. Its ribs were fingers all along: five long pale fingers a side,",
             "knuckled and nailed, that part like two hands from the breastbone. The face is not on its head any more:",
             "it is in its chest, looking out between them. The card on its head is blank; you can see through the holes.",
             "When it catches you, the ribs close."]

    def skeleton(self):
        J = P.skeleton()
        J_ = index(J)
        J_["chest"].radius = (0.10, 0.08)
        J_["upchest"].radius = (0.112, 0.07)
        for side, s in (("L", 1.0), ("R", -1.0)):
            for i in range(RIBS):
                z = 2.36 - i * 0.075
                parent = "upchest" if i < 2 else ("chest" if i < 4 else "spine2")
                p0 = np.array([0.025 * s, -0.02, z])
                prev = parent
                L = 1.0 - 0.08 * abs(i - 1.5)
                pts = [p0, p0 + np.array([0.12 * s, 0.02, -0.01]) * L, p0 + np.array([0.24 * s, -0.04, -0.03]) * L,
                       p0 + np.array([0.30 * s, -0.16, -0.05]) * L, p0 + np.array([0.27 * s, -0.29, -0.07]) * L,
                       p0 + np.array([0.18 * s, -0.37, -0.09]) * L]
                for k, p in enumerate(pts):
                    nm = f"rib{i}k{k}_{side}"
                    add(J, nm, p, (0.013, 0.012, 0.011, 0.0095, 0.008, 0.003)[k], prev)
                    prev = nm
        return J

    CHEST = np.array([0.0, -0.135, 2.21])

    def cards(self):
        f = FACE[self.form]
        blank = Card("neck2", self.head_center(), art={"kind": "blank", "params": {"seed": 21}})
        # the face, in the chest, behind the ribs, tilted back a little
        chest = Card("chest", self.CHEST, R=look_basis((0.0, -1.0, 0.1)), scale=0.82,
                     art={"kind": "face", "params": dict(f["params"], jaw=0.0), "marks": f["marks"]})
        return [blank, chest]

    def heads(self):
        return [(self.head_center(), "neck2", ""), (self.CHEST.copy(), "chest", "C")]

    def material(self, name):
        if name.startswith("rib"):
            return "pale"
        return super().material(name)

    def ball(self, name):
        if name.startswith("rib"):
            return None
        return super().ball(name)

    def ribs(self, amount, wave=0.0):
        """amount: 0 closed round the chest, 1 open like two hands, 1.4 thrown wide."""
        p = {}
        for side, s in (("L", 1.0), ("R", -1.0)):
            for i in range(RIBS):
                a = amount * (1.0 + 0.08 * math.sin(wave + i * 1.3))
                p[f"rib{i}k0_{side}"] = (0, 0, s * (-22 + 58 * a))
                p[f"rib{i}k1_{side}"] = (0, 0, s * (14 * a - 10))
                p[f"rib{i}k2_{side}"] = (0, 0, s * (-26 + 30 * a))
                p[f"rib{i}k3_{side}"] = (0, 0, s * (-26 + 28 * a))
                p[f"rib{i}k4_{side}"] = (0, 0, s * (-20 + 22 * a))
        return p

    def poses(self):
        stand = merge(P.POSES["stand"], self.ribs(0.6), {"neck2": (-50, 10, 6), "chest": (4, 0, 0)})
        move = merge(P.walk_key("R", "contact"), self.ribs(0.8, 1.0))
        attack = merge(P.lunge_clip()[9], self.ribs(1.3), {"jaw": 0.0})
        special = merge(P.HOLD, self.ribs(0.1), {"neck2": (-30, 0, 0)})
        return {"stand": stand, "move": move, "attack": attack, "special": special}


class TwoHeads(Variant):
    key, form, name = "a2", "angry", "Two heads"
    lines = ["ANGRY, variant 2: the neck has split at the second vertebra and there are two of it now, two cards",
             "on two necks, the old face and a new one. They do not agree. One is always on you; the other looks",
             "where you are going to be. The shoulders have widened to carry them."]

    def skeleton(self):
        J = P.skeleton()
        for side, s in (("L", 1.0), ("R", -1.0)):
            shift(J, f"clav_{side}", (0.03 * s, 0.0, 0.02))
        J_ = index(J)
        J_["upchest"].radius = (0.13, 0.07)
        # head A leans to its left, head B grows out to its right
        shift(J, "neck1", (0.07, 0.0, -0.03))
        shift(J, "neck2", (0.04, 0.0, 0.0))
        add(J, "neckB1", (-0.07, 0.02, 2.69), 0.022, "neck0")
        add(J, "neckB2", (-0.11, -0.02, 2.92), 0.020, "neckB1")
        add(J, "skullB", (-0.12, -0.05, 3.04), 0.018, "neckB2")
        return J

    def head_center(self):
        return P.HEAD_CENTER + np.array([0.11, 0.0, -0.03])

    def head_b(self):
        return P.HEAD_CENTER + np.array([-0.12, 0.0, -0.03])

    def heads(self):
        return [(self.head_center(), "neck2", ""), (self.head_b(), "neckB2", "B")]

    def cards(self):
        a = FACE["uneasy"]
        b = FACE["angry"]
        return [Card("neck2", self.head_center(), art={"kind": "face", "params": dict(a["params"], seed=31), "marks": ["crayon"]}),
                Card("neckB2", self.head_b(), art={"kind": "face", "params": dict(b["params"], seed=32, number="33b"),
                                                     "marks": b["marks"]})]

    def poses(self):
        heads = {"neck0": (24, 0, 0), "neck1": (16, 14, 0), "neck2": (-58, 18, 10), "neckB1": (20, -24, 0), "neckB2": (-40, -30, -40)}
        stand = merge(P.POSES["stand"], heads)
        move = merge(P.walk_key("L", "contact"), heads, {"neckB2": (-70, -10, 30)})
        attack = merge(P.lunge_clip()[9], {"neck1": (10, 10, 0), "neck2": (-60, 10, 0), "neckB1": (14, -10, 0), "neckB2": (-60, -10, 0),
                                            "jaw": 70})
        special = merge(P.POSES["stand"], {"neck0": (14, 0, 0), "neck1": (-10, 30, 0), "neck2": (-30, 30, 60),
                                           "neckB1": (30, -30, 0), "neckB2": (-90, -20, 0)})
        return {"stand": stand, "move": move, "attack": attack, "special": special}


NECK = 9


class LongNeck(Variant):
    key, form, name = "a3", "angry", "Long neck"
    lines = ["ANGRY, variant 3: the neck has grown. Nine vertebrae, two blocks and more of it, carried coiled",
             "behind its head like a question mark. It does not need to come in: the face comes in through the window",
             "and the body stays out in the dark. It looks over your walls. It looks down at you from above your pillar."]

    def skeleton(self):
        J = P.skeleton()
        remove(J, "neck1")
        prev = "neck0"
        p = np.array([0.0, 0.040, 2.49])
        for i in range(1, NECK):
            p = p + np.array([0.0, 0.0, 0.25])
            nm = "neck2" if i == NECK - 1 else f"neck1{chr(96 + i)}"
            add(J, nm, p, 0.024 - 0.0005 * i, prev)
            prev = nm
        add(J, "skull", p + np.array([0.0, -0.03, 0.12]), 0.018, "neck2")
        return J

    def head_center(self):
        return P.HEAD_CENTER + np.array([0.0, 0.02, 2.49 + 0.25 * (NECK - 1) - 2.95])

    def neck_curve(self, bends, turn=0.0):
        p = {}
        names = [f"neck1{chr(96 + i)}" for i in range(1, NECK - 1)] + ["neck2"]
        for nm, b in zip(names, bends):
            p[nm] = (b, 0, turn)
        return p

    def poses(self):
        # the question mark: up and back, then over and down, the face hanging level
        stand = merge(P.POSES["stand"], {"neck0": (-30, 0, 0)},
                      self.neck_curve([-18, -10, 6, 26, 36, 40, 36, -110]))
        move = merge(P.walk_key("L", "passing"), {"neck0": (-20, 0, 0)},
                     self.neck_curve([-10, 0, 14, 30, 34, 30, 20, -110]))
        attack = merge(P.lunge_clip()[9], {"neck0": (60, 0, 0)},
                       self.neck_curve([8, 6, 4, 2, 0, 0, 0, -56]), {"jaw": 80})
        # over a wall: the neck straight up and over, the face hanging down the other side
        special = merge(P.POSES["stand"], P.finger_curl(60, "L"), P.finger_curl(60, "R"),
                        {"neck0": (6, 0, 0), "shoulder_L": (-150, -10, 0), "shoulder_R": (-150, 10, 0),
                         "elbow_L": (-30, 0, 0), "elbow_R": (-30, 0, 0)},
                        self.neck_curve([0, 0, 0, 20, 45, 50, 45, -160]))
        return {"stand": stand, "move": move, "attack": attack, "special": special}


# ---------------------------------------------------------------- FURIOUS ---------------------

class Spider(Variant):
    key, form, name = "f1", "furious", "Spider"
    lines = ["FURIOUS, variant 1: a second pair of arms out of its back, as long as the first, and it has gone",
             "down onto all six. The card is torn in half down the middle and the halves stand open either side of",
             "its face like mandibles: the real face behind, wet, the eyes it was looking through, the mouth."]

    def skeleton(self):
        J = P.skeleton()
        for side, s in (("L", 1.0), ("R", -1.0)):
            tag = side + "B"
            arm(J, tag, "chest", (0.06 * s, 0.08, 2.22), (0.17 * s, 0.10, 2.19), (0.20 * s, 0.12, 1.60),
                (0.215 * s, 0.10, 0.98), d=(0, -0.05, -1), side=(0, 1, 0))
        return J

    def cards(self):
        f = FACE[self.form]
        W, H = 220, 380
        hx = P.CARD[0]
        lt = move4((-0.02, -0.01, 0.0)) @ rot4((0, 0, 1), -68, (-hx, 0.0, 0.0))
        rt = move4((0.02, -0.01, 0.0)) @ rot4((0, 0, 1), 68, (hx, 0.0, 0.0))
        torn = Card("neck2", self.head_center(), art={"kind": "face", "params": f["params"], "marks": f["marks"] + ["split"]},
                    pieces=[Piece(0, 0, W // 2, H, lt), Piece(W // 2, 0, W, H, rt)])
        real = Card("neck2", self.head_center() + np.array([0.0, 0.012, 0.0]), scale=0.94,
                    art={"kind": "real", "params": {"seed": 3, "jaw": 26.0}})
        return [torn, real]

    def contact(self):
        return P.CONTACT + ("f0k4_", "f1k4_", "f2k4_", "f3k4_", "palm_")

    def mandibles(self, a):
        return {}

    def poses(self):
        def legs(i):
            out = {"root": (84, 0, 0), "root_pivot": (0.0, 0.0, 1.56), "root_shift": (0.0, 0.7, -0.95),
                   "neck0": (-44, 0, 0), "neck1": (-30, 0, 0), "neck2": (-40, 0, 0)}
            out.update(P.finger_curl(-30, "L", 10))
            out.update(P.finger_curl(-30, "R", 10))
            out.update(curl("LB", -30, (0, 1, 0), spread=10))
            out.update(curl("RB", 30, (0, 1, 0), spread=10))
            for side, s in (("L", 1.0), ("R", -1.0)):
                a = 1 if (i + (side == "R")) % 2 else -1
                out[f"shoulder_{side}"] = (-70 + 14 * a, 40 * s, 0)
                out[f"elbow_{side}"] = (0, -70 * s, 0)
                out[f"wrist_{side}"] = (30, 30 * s, 0)
                out[f"shoulder_{side}B"] = (-20 - 14 * a, 64 * s, 0)
                out[f"elbow_{side}B"] = (0, -90 * s, 0)
                out[f"wrist_{side}B"] = (30, 30 * s, 0)
                out[f"hip_{side}"] = (-60 - 14 * a, 40 * s, 0)
                out[f"knee_{side}"] = (110, 0, 0)
                out[f"ankle_{side}"] = (-20, 0, 0)
            return out
        stand = legs(0)
        move = legs(1)
        attack = merge(P.finger_curl(-20, "L", 14), P.finger_curl(-20, "R", 14), curl("LB", -20, (0, 1, 0), spread=14),
                       curl("RB", 20, (0, 1, 0), spread=14), {
            # reared up on its legs, all four arms open
            "root": (30, 0, 0), "root_pivot": (0.0, 0.0, 1.56), "root_shift": (0.0, 0.3, -0.6),
            "hip_L": (-60, 0, 0), "hip_R": (-60, 0, 0), "knee_L": (100, 0, 0), "knee_R": (100, 0, 0), "ankle_L": (-30, 0, 0), "ankle_R": (-30, 0, 0),
            "neck0": (-10, 0, 0), "neck2": (-60, 0, 0), "jaw": 80,
            "shoulder_L": (-120, -60, 0), "shoulder_R": (-120, 60, 0), "elbow_L": (-30, 0, 0), "elbow_R": (-30, 0, 0),
            "shoulder_LB": (-60, -80, 0), "shoulder_RB": (-60, 80, 0), "elbow_LB": (-40, 0, 0), "elbow_RB": (-40, 0, 0)})
        special = merge(legs(2), {"root": (-90, 0, 180), "root_pivot": (0.0, 0.0, 1.56), "root_shift": (0.0, 0.0, 1.0),
                                  "neck2": (-40, 0, 180)})
        return {"stand": stand, "move": move, "attack": attack, "special": special}


TAPES = (("wrist_L", 7, 0.0), ("wrist_R", 6, 1.0), ("elbow_L", 5, 2.0), ("elbow_R", 6, 3.0), ("neck1", 8, 4.0),
         ("knee_L", 5, 5.0), ("knee_R", 4, 6.0), ("jaw", 6, 7.0), ("spine1b", 7, 8.0))


class Unwound(Variant):
    key, form, name = "f2", "furious", "Unwound"
    lines = ["FURIOUS, variant 2: it is coming apart. The black has split and slid off its limbs and under it is the",
             "armature: threaded rod, wing nuts, wire. Tape spools out of every joint and out of its mouth and streams",
             "behind it. It is a block taller, stretched. The card is shredded into strips that hang over the face.",
             "It moves the way a tape moves when it is chewed: frames missing, then all of them at once."]

    def skeleton(self):
        J = P.skeleton()
        for j in J:
            j.pos = np.array([j.pos[0] * 0.9, j.pos[1], j.pos[2] * 1.25])
            if not j.name.startswith(PALE):
                j.radius = (j.radius[0] * 0.75, j.radius[1] * 0.75)
        return J

    def head_center(self):
        c = P.HEAD_CENTER.copy()
        return np.array([c[0], c[1], 2.95 * 1.25 + (c[2] - 2.95)])

    def cards(self):
        f = FACE[self.form]
        W, H = 220, 380
        strips = []
        rng = np.random.default_rng(5)
        n = 7
        for i in range(n):
            u0, u1 = int(i * W / n), int((i + 1) * W / n)
            top_v = 30 + 10 * rng.random()
            ang = rng.uniform(-14, 14)
            sag = rng.uniform(0.0, 0.02)
            T = move4((0.0, -0.01 * (i % 2), -sag)) @ rot4((0, 1, 0), ang, ((u0 + u1) / 2 / W * 0.3 - 0.15, 0.0, 0.26 - top_v / H * 0.52))
            strips.append(Piece(u0, 0, u1, H, T))
        shreds = Card("neck2", self.head_center(), art={"kind": "face", "params": f["params"], "marks": f["marks"]}, pieces=strips)
        real = Card("neck2", self.head_center() + np.array([0.0, 0.016, 0.0]), scale=0.92,
                    art={"kind": "real", "params": {"seed": 4, "jaw": 30.0}})
        return [shreds, real]

    def material(self, name):
        if name.startswith(PALE):
            return "pale"
        return "metal"

    def poses(self):
        stand = merge(P.POSES["stand"], {"upchest": (12, -12, 10), "neck2": (-50, 30, 30), "shoulder_L": (-20, -20, 0),
                                         "elbow_L": (-60, 0, 0), "knee_R": (24, 0, 0)})
        move = merge(P.run_key("L", "passing"), {"upchest": (10, -10, 20)})
        attack = merge(P.lunge_clip()[9], {"neck2": (-80, -20, -20), "jaw": 90})
        special = merge(P.POSES["stand"], {"root": (0, 0, 0), "upchest": (40, 30, 0), "neck0": (30, 0, 0),
                                           "neck2": (-120, 0, 90), "shoulder_R": (-170, 0, 0)})
        return {"stand": stand, "move": move, "attack": attack, "special": special}


FACES_ON = (("upchest", (0.0, -0.12, 2.30), (0.0, -1.0, 0.1), 0.78), ("spine2", (0.0, -0.10, 1.98), (0.0, -1.0, 0.0), 0.7),
            ("pelvis", (0.0, -0.11, 1.62), (0.0, -1.0, -0.1), 0.66), ("upchest", (0.0, 0.16, 2.30), (0.0, 1.0, 0.3), 0.78),
            ("spine2", (0.0, 0.15, 1.96), (0.0, 1.0, 0.1), 0.72),
            ("shoulder_L", (0.25, -0.02, 2.36), (1.0, -0.4, 0.4), 0.62), ("shoulder_R", (-0.25, -0.02, 2.36), (-1.0, -0.4, 0.4), 0.62),
            ("elbow_L", (0.26, -0.02, 1.76), (1.0, -0.6, 0.0), 0.55), ("elbow_R", (-0.26, -0.02, 1.76), (-1.0, -0.6, 0.0), 0.55),
            ("hip_L", (0.14, -0.08, 1.36), (0.6, -1.0, 0.0), 0.6), ("hip_R", (-0.14, -0.08, 1.36), (-0.6, -1.0, 0.0), 0.6),
            ("knee_L", (0.11, -0.08, 0.86), (0.3, -1.0, 0.0), 0.55), ("knee_R", (-0.11, -0.08, 0.86), (-0.3, -1.0, 0.0), 0.55))
RUFF = 6


class EveryFace(Variant):
    key, form, name = "f3", "furious", "Every face"
    lines = ["FURIOUS, variant 3: it has put on every face it has. Cards are pinned all over it, on its chest and down",
             "its spine, on its shoulders and elbows and hips and knees, and round its own face there is a ring of them",
             "like the petals of a flower. Every one is a face, and every one is looking at you."]

    def head_center(self):
        return P.HEAD_CENTER + np.array([0.0, -0.01, -0.03])

    def cards(self):
        out = []
        f = FACE[self.form]
        c = self.head_center()
        for i in range(RUFF):
            a = 2 * math.pi * (i + 0.5) / RUFF
            up = np.array([math.sin(a), 0.0, math.cos(a)])
            R = look_basis((0.0, -1.0, 0.0), up)
            ctr = c + up * 0.30 + np.array([0.0, 0.012 + 0.002 * i, 0.0])
            out.append(Card("neck2", ctr, R=R, scale=0.7, art={"kind": "face", "params": dict(f["params"], seed=40 + i,
                            lids=((0.0, 0.0) if i % 2 else (0.35, 0.15)), number=str(41 + i)), "marks": f["marks"][:1]}))
        out.append(Card("neck2", c, art={"kind": "face", "params": f["params"], "marks": f["marks"]}))
        for k, (joint, pos, look, s) in enumerate(FACES_ON):
            R = look_basis(look)
            out.append(Card(joint, pos, R=R, scale=s, art={"kind": "face", "params": dict(FACE["angry"]["params"], seed=60 + k,
                            number=str(35 + k), wear=0.5 + 0.05 * k, blood=0.2 * (k % 3)), "marks": ["crayon"] if k % 3 == 0 else []}))
        return out

    def poses(self):
        stand = merge(P.POSES["stoop"], {"neck2": (-60, 0, 0), "shoulder_L": (-20, -30, 0), "shoulder_R": (-20, 30, 0)})
        move = merge(P.stoop_key("L", "passing"), {"neck2": (-70, 0, 0)})
        attack = merge(P.scream_clip()[4], {"jaw": 0})
        special = merge(P.crawl_key(1), {"neck2": (-30, 0, 0)})
        return {"stand": stand, "move": move, "attack": attack, "special": special}


VARIANTS = [Rigged(), SecondPair(), Reversed(), Opened(), TwoHeads(), LongNeck(), Spider(), Unwound(), EveryFace()]
BY_KEY = {v.key: v for v in VARIANTS}


def grounded(variant, pose, joints=None, sole=0.012):
    """Shifts a pose so the lowest contact point of this variant rests on the floor."""
    joints = joints or variant.rig()
    mats = P.joint_transforms(joints, pose)
    names = variant.contact()
    zs = [(mats[j.name] @ np.append(j.pos, 1.0))[2] for j in joints if j.name.startswith(names)]
    if not zs:
        return pose
    out = dict(pose)
    sx, sy, sz = out.get("root_shift", (0.0, 0.0, 0.0))
    out["root_shift"] = (sx, sy, sz + sole - min(zs))
    return out
