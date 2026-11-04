"""The three tempers as models: UNEASY, ANGRY and FURIOUS.

Chosen from the round-3 concept sheets (docs/concepts/forms/, picked by an independent critique)
and rebuilt with its changes. Each is the base creature of poses.py with parts added or rebuilt,
and each keeps what the one before it grew, so they read as the same thing getting worse:

    UNEASY   "Second pair": a second, small pair of arms out through a split under its ribs,
             child's hands that wring and pick and knock. The long arms hang longer and stiller.
             The card has come unpinned and been pinned back on crooked.
    ANGRY    "Long neck": nine lacquered vertebrae, carried coiled. It reaches over walls, up over
             pillars and in through windows, one vertebra at a time, while its body stays outside.
             The small hands are clasped, and still.
    FURIOUS  "Unwound": the black has split and slid off; under it is the armature, gunmetal rod
             and wing nuts. Tape spools out of cassette hubs at its shoulders and hips, and out of
             its mouth when it opens. The card is shredded; the real face is behind the strips.
             The small hands pull tape out of its own joints. It moves like a chewed tape.

Every model has every clip the game plays (the base clip names), plus its own: UNEASY knock and
pick; ANGRY reach_wall, reach_up and reach_window. Clips are made by adapting the base clips
frame by frame (the form's posture, its extra parts) and replacing the ones the critique cut.
"""
import math

import numpy as np

import forms as F
import poses as P
from poses import Joint, finger_curl, keyed, merge

ZERO = (0.0, 0.0, 0.0)


def add(a, b):
    a = a or ZERO
    return tuple(x + y for x, y in zip(a, b))


def local(pose, name, e):
    """Composes a rotation in the joint's own frame onto what the pose already has there."""
    return F.compose(pose.get(name, ZERO), e)


# ------------------------------------------------------------------------------------------------
# Forward kinematics queries, and a small solver for aiming a hand or a head
# ------------------------------------------------------------------------------------------------

_RIGS = {}


def rig_of(form):
    if form.key not in _RIGS:
        _RIGS[form.key] = form.rig()
    return _RIGS[form.key]


def chain_to(joints, end):
    by = {j.name: j for j in joints}
    names = set()
    n = end
    while n:
        names.add(n)
        n = by[n].parent
    return [j for j in joints if j.name in names], by[end]


def world_point(joints, pose, name):
    sub, j = chain_to(joints, name)
    m = P.joint_transforms(sub, pose)[name]
    return (m @ np.append(j.pos, 1.0))[:3]


def world_pitch(joints, pose, name):
    """How far forward (degrees) the joint's frame is tipped, seen from the side."""
    sub, j = chain_to(joints, name)
    R = P.joint_transforms(sub, pose)[name][:3, :3]
    v = R @ np.array([0.0, 0.0, 1.0])
    return math.degrees(math.atan2(-v[1], v[2]))


def solve(joints, pose, params, end, target):
    """Coordinate descent on the given (joint, axis) angles until `end` is at `target`."""
    sub, endj = chain_to(joints, end)
    target = np.asarray(target, float)

    def err(p):
        m = P.joint_transforms(sub, p)[end]
        return float(np.linalg.norm((m @ np.append(endj.pos, 1.0))[:3] - target))

    p = dict(pose)
    best = err(p)
    step = 32.0
    while step >= 0.5:
        improved = False
        for jn, ax in params:
            for d in (step, -step):
                q = dict(p)
                v = list(q.get(jn, ZERO))
                v[ax] += d
                q[jn] = tuple(v)
                e = err(q)
                if e < best - 1e-7:
                    p, best, improved = q, e, True
        if not improved:
            step /= 2.0
    return p, best


# ------------------------------------------------------------------------------------------------
# The small arms, which every later form keeps
# ------------------------------------------------------------------------------------------------

SMALL_SIDE = {"L": np.array([0.3, -0.9, 0.0]), "R": np.array([-0.3, -0.9, 0.0])}


def small_arms(J):
    """Thin, folded double, out through a split under the lowest ribs; child's hands that keep
    the extra knuckle."""
    for side, s in (("L", 1.0), ("R", -1.0)):
        tag = side + "2"
        F.arm(J, tag, "spine2", (0.040 * s, 0.006, 1.965), (0.100 * s, -0.012, 1.950), (0.142 * s, -0.080, 1.830),
              (0.052 * s, -0.176, 2.020), d=(-s * 0.55, -0.25, 0.8), side=SMALL_SIDE[side],
              radii=(0.019, 0.021, 0.0105, 0.016, 0.0095, 0.0115), hand_scale=0.64, lengths=(0.86, 1.0, 0.95, 0.8))


def small_curl(side, amount, spread=0.0):
    axis = SMALL_SIDE[side] * (1.0 if side == "L" else -1.0)
    return F.curl(side + "2", amount, axis, spread=spread)


def wring(t, k=1.0):
    """The small hands working at each other: curling, turning, never still."""
    p = {}
    for side, s in (("L", 1.0), ("R", -1.0)):
        ph = t + (0.0 if s > 0 else 1.9)
        p.update(small_curl(side, 55 + 30 * math.sin(ph), spread=6))
        p[f"wrist_{side}2"] = (k * 14 * math.sin(2 * ph), 0.0, k * 22 * s * math.cos(ph))
        p[f"elbow_{side}2"] = (0.0, 0.0, -s * k * 6 * math.sin(ph))
    return p


def clasped(tight=80):
    p = {}
    for side, s in (("L", 1.0), ("R", -1.0)):
        p.update(small_curl(side, tight, spread=0))
        p[f"wrist_{side}2"] = (0.0, 0.0, 14 * s)
        p[f"elbow_{side}2"] = (0.0, 0.0, -10 * s)
    return p


def reaching(amount=1.0, open_hands=True):
    """The small arms unfolded and out in front, toward you."""
    p = {}
    for side, s in (("L", 1.0), ("R", -1.0)):
        p[f"shoulder_{side}2"] = (-60 * amount, -10 * s * amount, 0.0)
        p[f"elbow_{side}2"] = (0.0, 0.0, 70 * s * amount)
        p[f"wrist_{side}2"] = (-30 * amount, 0.0, 0.0)
        p.update(small_curl(side, -20 if open_hands else 70, spread=10 if open_hands else 0))
    return p


# ------------------------------------------------------------------------------------------------
# Forms
# ------------------------------------------------------------------------------------------------

class Form(F.Variant):
    """A temper as a model: the skeleton, the head, the parts, and every clip."""

    head_offset = np.zeros(3)

    def head_center(self):
        return P.HEAD_CENTER + self.head_offset

    def extra_clips(self):
        return {}

    def adapt(self, name, i, n, pose):
        return pose

    def clips(self):
        base = P._clips()
        out = {}
        for name, (fps, loop, frames) in base.items():
            out[name] = (fps, loop, [self.adapt(name, i, len(frames), dict(f)) for i, f in enumerate(frames)])
        out.update(self.extra_clips())
        joints = self.rig()
        grounded = set(P.GROUNDED) | set(self.grounded_extra())
        for name in list(out):
            if name in grounded:
                fps, loop, frames = out[name]
                out[name] = (fps, loop, [F.grounded(self, f, joints) for f in frames])
        return out

    def grounded_extra(self):
        return ()


class Uneasy(Form):
    key, form, name = "uneasy", "uneasy", "Second pair"
    TILT = (0.0, 30.0, 0.0)

    def skeleton(self):
        J = P.skeleton()
        F.base_arms_down(J, 0.12)
        small_arms(J)
        return J

    def cards(self):
        return [F.Card("neck2", self.head_center(), art={"kind": "face", "params": F.FACE["uneasy"]["params"], "marks": []})]

    def material(self, name):
        if name.startswith(F.PALE):
            return "pale"
        return "black"

    def grounded_extra(self):
        return ("knock", "pick")

    def posture(self, pose, hang=True):
        """Shoulders rounded, the head bowed toward the small hands, the card on crooked."""
        p = dict(pose)
        p["clav_L"] = add(p.get("clav_L"), (0.0, 0.0, -9.0))
        p["clav_R"] = add(p.get("clav_R"), (0.0, 0.0, 9.0))
        p["upchest"] = add(p.get("upchest"), (7.0, 0.0, 0.0))
        p["neck2"] = local(p, "neck2", self.TILT)
        if hang:
            # the long arms hang dead: whatever the clip did with them, mostly undone
            for side in ("L", "R"):
                sh = p.get(f"shoulder_{side}", ZERO)
                p[f"shoulder_{side}"] = (sh[0] * 0.35 - 4.0, sh[1] * 0.35, sh[2] * 0.35)
                el = p.get(f"elbow_{side}", ZERO)
                p[f"elbow_{side}"] = (el[0] * 0.4, el[1], el[2])
        return p

    def adapt(self, name, i, n, pose):
        moving = name in ("walk", "stoop_walk", "run", "crawl", "climb", "swim", "leap")
        keep_arms = name in ("climb", "crawl", "swim", "leap", "throw", "stunned", "ledge", "lie", "rise", "hold",
                             "peek_right", "peek_left", "lean_right", "lean_left", "reach", "bedside", "scream", "stoop", "crouch")
        p = self.posture(pose, hang=not keep_arms)
        if name in ("hold", "scream"):
            p.update(reaching(0.9))
        elif moving:
            p.update(clasped(80 if name != "run" else 88))
        else:
            p.update(wring(2 * math.pi * i / max(n, 1)))
        return p

    def walk_key(self, lead, phase):
        """No marionette knees: a slow shuffle, bent forward, the feet barely leaving the floor."""
        back = "R" if lead == "L" else "L"
        if phase == "contact":
            legs = {f"hip_{lead}": (-20, 0, 0), f"knee_{lead}": (14, 0, 0), f"ankle_{lead}": (6, 0, 0),
                    f"hip_{back}": (14, 0, 0), f"knee_{back}": (18, 0, 0), f"ankle_{back}": (-12, 0, 0)}
            shift = (0.0, 0.0, -0.06)
        else:
            legs = {f"hip_{back}": (-12, 0, 0), f"knee_{back}": (34, 0, 0), f"ankle_{back}": (-14, 0, 0),
                    f"hip_{lead}": (2, 0, 0), f"knee_{lead}": (8, 0, 0), f"ankle_{lead}": (0, 0, 0)}
            shift = (0.0, 0.0, -0.02)
        s = 1.0 if lead == "L" else -1.0
        return merge(P.HUNCH, finger_curl(14, "L"), finger_curl(18, "R"),
                     {"spine2": (10, 0, 0), "chest": (14, 0, 0), "upchest": (14, 2 * s, 2 * s), "neck2": (-58, 0, 0),
                      "root_shift": shift, "shoulder_L": (-3, 0, 0), "shoulder_R": (-3, 0, 0)}, legs)

    ARM2 = (("shoulder_{}2", 0), ("shoulder_{}2", 1), ("shoulder_{}2", 2), ("elbow_{}2", 0), ("elbow_{}2", 2))

    def aim(self, pose, side, offset, frm="upchest"):
        """The small hand on `side` put `offset` (blocks) from the joint `frm`, in its posed place."""
        J = rig_of(self)
        target = world_point(J, pose, frm) + np.asarray(offset, float)
        params = [(a.format(side), ax) for a, ax in self.ARM2]
        out, err = solve(J, pose, params, f"wrist_{side}2", target)
        return {k: v for k, v in out.items() if k.endswith(side + "2")}

    def knock_clip(self):
        """Three, a pause, one. The small right hand at the door in front of it, knuckles first;
        the long arms do not move."""
        base = self.posture(merge(P.POSES["stand"], {"neck2": (-50, 6, 0)}))
        up = self.aim(base, "R", (-0.04, -0.30, 0.10))
        hit = self.aim(base, "R", (-0.04, -0.40, 0.08))
        frames = []
        seq = ["up", "hit", "up", "hit", "up", "hit", "up", "up", "up", "up", "up", "up", "hit", "up", "up", "up"]
        for which in seq:
            p = merge(base, up if which == "up" else hit, small_curl("R", 82), clasped(70) if False else small_curl("L", 70),
                      {"wrist_R2": (-30 if which == "hit" else 10, 0, 0)})
            frames.append(p)
        return frames

    def pick_clip(self):
        """It takes your light: the small left hand out and up to it, closing on it, and back."""
        base = self.posture(merge(P.POSES["stand"], {"chest": (14, 0, 0), "upchest": (10, 0, 0), "neck2": (-44, -6, 0)}))
        reach = self.aim(base, "L", (0.02, -0.42, 0.30))
        home = self.aim(base, "L", (0.0, -0.16, -0.06))
        out = merge(base, reach, small_curl("L", -20, 10))
        close = merge(base, reach, small_curl("L", 85))
        back = merge(base, home, small_curl("L", 85))
        frames = keyed([(0, merge(base, small_curl("L", 40))), (3, out), (4, close), (5, close), (7, back)], 8)
        return [merge(f, small_curl("R", 70)) for f in frames]

    def lunge_clip(self):
        """No long-arm lunge: the body tips at you, and the small arms come out first, open."""
        start = merge(P.POSES["stand"], {"jaw": 0.0})
        mid = self.posture(merge(start, {"spine1": (8, 0, 0), "spine2": (8, 0, 0), "chest": (8, 0, 0), "neck2": (-60, 0, 0), "jaw": 24.0,
                                         "hip_L": (-20, 0, 0), "knee_L": (24, 0, 0)}))
        end = self.posture(merge(start, {"spine1": (14, 0, 0), "spine2": (12, 0, 0), "chest": (10, 0, 0), "neck0": (18, 0, 0),
                                         "neck2": (-60, 0, 0), "hip_L": (-26, 0, 0), "knee_L": (34, 0, 0), "hip_R": (10, 0, 0),
                                         "jaw": 64.0, "jaw_drop": 0.04}))
        for side, sx in (("L", 1.0), ("R", -1.0)):
            mid.update(self.aim(mid, side, (0.06 * sx, -0.32, 0.0)))
            end.update(self.aim(end, side, (0.10 * sx, -0.46, 0.04)))
        mid.update(small_curl("L", -10, 8))
        mid.update(small_curl("R", -10, 8))
        end.update(small_curl("L", -25, 14))
        end.update(small_curl("R", -25, 14))
        return keyed([(0, self.posture(start)), (4, mid), (9, end)], 10)

    def extra_clips(self):
        walk = keyed([(0, self.walk_key("L", "contact")), (2, self.walk_key("L", "passing")),
                      (4, self.walk_key("R", "contact")), (6, self.walk_key("R", "passing"))], 8, loop=True)
        return {
            "walk": (8.0, True, [self.posture(merge(f, clasped(80)), hang=True) for f in walk]),
            "lunge": (12.0, False, self.lunge_clip()),
            "knock": (10.0, False, self.knock_clip()),
            "pick": (10.0, False, self.pick_clip()),
        }


NECK_JOINTS = ["neck1a", "neck1b", "neck1c", "neck1d", "neck1e", "neck1f", "neck1g", "neck2"]
SEG = 0.28
# Each segment's direction, degrees forward of straight up (the first is the neck0 -> neck1a bone).
# Carried folded like an accordion behind its head, so its face is about where the WATCHER's is;
# reaching, it unfolds, one vertebra at a time.
COIL = [-78, 98, -78, 98, -78, 98, -50, 20]
# Under a low ceiling, or down on all fours: folded flatter still.
COIL_FLAT = [-86, 94, -86, 94, -86, 94, -70, 60]
LOW_CLIPS = ("crawl", "crouch", "stoop", "stoop_walk", "bedside", "swim", "lie", "ledge", "rise")
REACH_WALL = [-4, 4, 20, 60, 100, 120, 150, 178]
REACH_UP = [0, 0, 0, 0, 0, 10, 40, 90]
REACH_WINDOW = [85, 90, 90, 90, 90, 90, 90, 90]


class Angry(Form):
    key, form, name = "angry", "angry", "Long neck"
    head_offset = np.array([0.0, 0.060, SEG * 8 - 0.46])

    def skeleton(self):
        J = P.skeleton()
        F.base_arms_down(J, 0.12)
        small_arms(J)
        F.remove(J, "neck1")
        prev = "neck0"
        p = np.array([0.0, 0.040, 2.49])
        for i, nm in enumerate(NECK_JOINTS):
            p = p + np.array([0.0, 0.0, SEG])
            F.add(J, nm, p.copy(), 0.027 - 0.0007 * i, prev)
            prev = nm
        F.add(J, "skull", p + np.array([0.0, -0.03, 0.12]), 0.018, "neck2")
        return J

    def cards(self):
        return [F.Card("neck2", self.head_center(), art={"kind": "face", "params": F.FACE["angry"]["params"], "marks": []})]

    def grounded_extra(self):
        return ("reach_wall", "reach_up", "reach_window")

    def material(self, name):
        # the neck is lacquered rigid segments (build_form.lacquer_neck), not skin
        if name in NECK_JOINTS or name == "skull":
            return None
        return super().material(name)

    def shape(self, pose, phis, head_pitch=None):
        """Lays the neck out along the directions `phis`, keeping the head pitched the way the
        base pose had it (or at `head_pitch`), and its turn and tilt."""
        base_pitch = world_pitch(P.rig(), pose, "neck2") if head_pitch is None else head_pitch
        p = dict(pose)
        n2 = p.get("neck2", ZERO)
        p.pop("neck1", None)
        torso = world_pitch(rig_of(self), p, "upchest")
        p["neck0"] = (phis[0] - torso, 0.0, 0.0)
        for nm, a, b in zip(NECK_JOINTS[:-1], phis[:-1], phis[1:]):
            p[nm] = (b - a, 0.0, 0.0)
        p["neck2"] = (base_pitch - phis[-1], n2[1], n2[2])
        return p

    def adapt(self, name, i, n, pose):
        p = self.shape(pose, COIL_FLAT if name in LOW_CLIPS else COIL)
        p.update(clasped(72))
        return p

    def unfold(self, k, target):
        """k segments of the way from folded to `target`; the rest keep their fold behind them."""
        out = []
        for j in range(len(COIL)):
            if j < k:
                out.append(target[j])
            else:
                anchor = target[k - 1] - COIL[k - 1] if k > 0 else 0.0
                out.append(COIL[j] + anchor)
        return out

    def reach_clip(self, body, target, head_pitch, hands=None):
        frames = []
        start_pitch = world_pitch(P.rig(), body, "neck2")
        for f in range(8):
            k = min(8, f + 2)
            pitch = start_pitch + (head_pitch - start_pitch) * min(1.0, f / 6.0)
            p = self.shape(merge(body, hands or {}), self.unfold(k, target), pitch)
            p.update(clasped(72))
            frames.append(p)
        frames[-1]["jaw"] = 24.0
        return frames

    def extra_clips(self):
        stand = dict(P.POSES["stand"])
        # over a wall about three blocks high: leaning into it, hands spread along the top, the
        # face coming down your side
        lean = merge(stand, {"spine1": (6, 0, 0), "spine2": (6, 0, 0), "chest": (4, 0, 0)})
        wall_hands = merge(finger_curl(84, "L", 12), finger_curl(84, "R", 12),
                           {"shoulder_L": (-158, -34, 0), "shoulder_R": (-158, 34, 0), "elbow_L": (-34, 0, 0), "elbow_R": (-34, 0, 0),
                            "wrist_L": (50, 0, 0), "wrist_R": (50, 0, 0)})
        wall = self.reach_clip(lean, REACH_WALL, 6.0, wall_hands)
        # straight up, and then down over the edge of whatever you are standing on
        up = self.reach_clip(stand, REACH_UP, 70.0)
        # in through a window: crouched outside, the neck level through the frame, the face well in
        low = merge(stand, {"spine2": (8, 0, 0), "chest": (6, 0, 0), "hip_L": (-50, 0, 0), "hip_R": (-46, 0, 0), "knee_L": (80, 0, 0),
                            "knee_R": (76, 0, 0), "ankle_L": (-30, 0, 0), "ankle_R": (-30, 0, 0), "shoulder_L": (-30, 0, 0),
                            "shoulder_R": (-30, 0, 0)})
        window = self.reach_clip(low, REACH_WINDOW, 4.0)
        return {"reach_wall": (8.0, False, wall), "reach_up": (8.0, False, up), "reach_window": (8.0, False, window)}


# Cassette hubs on its back at the shoulders and on its hips; the tape comes off them.
TAPES = (("upchest", np.array([0.15, 0.07, 2.33]), 7), ("upchest", np.array([-0.15, 0.07, 2.33]), 7),
         ("pelvis", np.array([0.12, 0.03, 1.50]), 6), ("pelvis", np.array([-0.12, 0.03, 1.50]), 6))
TAPE_SEG = 0.12
MOUTH_TAPE = 10
MOUTH_SEG = 0.045


class Furious(Form):
    key, form, name = "furious", "furious", "Unwound"
    head_offset = np.array([0.0, 0.0, 0.17])

    def skeleton(self):
        J = P.skeleton()
        F.base_arms_down(J, 0.12)
        # stretched limbs, not more height: the arms and shins longer, thinner
        for side in ("L", "R"):
            F.scale_from(J, f"uarm_{side}", 1.12)
            F.scale_from(J, f"shin_{side}", 1.08)
        F.vertebra(J, "neck1", "neck1b", (0.0, -0.02, 0.17), 0.020)
        small_arms(J)
        for j in J:
            if not j.name.startswith(F.PALE) and j.name not in ("pelvis",):
                j.radius = (j.radius[0] * 0.72, j.radius[1] * 0.72)
        # tape, out of the hubs at its shoulders and hips: hinged strips, hanging, kinked
        for r, (joint, at, n) in enumerate(TAPES):
            p = np.array(at, float)
            prev = joint
            for k in range(n):
                nm = f"tape{r}k{k}"
                F.add(J, nm, p.copy(), 0.004, prev)
                kink = np.array([0.012 * math.sin(k * 2.1 + r), 0.02 * math.cos(k * 1.7 + r), -TAPE_SEG])
                p = p + kink
                prev = nm
            F.add(J, f"tape{r}end", p.copy(), 0.004, prev)
        return J

    def rig(self):
        out = super().rig()
        # the tape in its mouth: folded up behind the chin piece, rides the jaw
        c = self.head_center() + P.MOUTH_LOCAL + np.array([0.0, 0.02, 0.0])
        prev = "jaw"
        for k in range(MOUTH_TAPE + 1):
            nm = f"mtape{k}"
            out.append(Joint(nm, c + np.array([0.0, 0.0, -0.004 * k]), 0.004, prev))
            prev = nm
        return out

    def material(self, name):
        if name.startswith("tape") or name.startswith("mtape"):
            return None
        if name.startswith(F.PALE):
            return "pale"
        return "metal"

    def cards(self):
        f = F.FACE["furious"]
        W, H = 220, 380
        strips = []
        rng = np.random.default_rng(5)
        n = 7
        for i in range(n):
            u0, u1 = int(i * W / n), int((i + 1) * W / n)
            # shredder output: pinned at the brow, fanned out, curling forward off the face
            ang = -(i - (n - 1) / 2) * 15.0 + rng.uniform(-4, 4)
            top = ((u0 + u1) / 2 / W * 0.3 - 0.15, 0.0, 0.22)
            T = F.rot4((0, 1, 0), ang, top) @ F.rot4((1, 0, 0), -(38 + 26 * rng.random()), top)
            strips.append(F.Piece(u0, 0, u1, H, T))
        return [F.Card("neck2", self.head_center(), art={"kind": "face", "params": f["params"], "marks": ["torn_eyes"]}, pieces=strips),
                F.Card("neck2", self.head_center() + np.array([0.0, 0.016, 0.0]), scale=0.92,
                       art={"kind": "real", "params": {"seed": 3, "jaw": 0.0}})]

    def tape_pose(self, pose, t, trail):
        """The tape: hanging under its own weight whatever the body does, kinking, and streaming
        back the faster it goes."""
        J = rig_of(self)
        p = {}
        for r, (joint, at, n) in enumerate(TAPES):
            # undo the hub's tilt so the first strip hangs, then lean it back with the motion
            tilt = world_pitch(J, pose, joint)
            p[f"tape{r}k0"] = (-tilt + trail * 55 + 8 * math.sin(t + r), 6 * math.cos(t + r), 0.0)
            for k in range(1, n):
                swing = 16 * math.sin(t + k * 0.9 + r) + trail * (6 + 3 * k)
                twist = 28 * math.sin(t * 0.7 + k + r * 2)
                p[f"tape{r}k{k}"] = (swing, 8 * math.cos(t + k), twist)
        return p

    def mouth_tape(self, amount):
        """Spooling out of its mouth: 0 folded away, 1 hanging all the way out."""
        p = {}
        for k in range(MOUTH_TAPE):
            fold = 160.0 * (1.0 - amount) * (1 if k % 2 == 0 else -1)
            p[f"mtape{k}"] = (fold + 10 * math.sin(k), 0.0, 8 * math.cos(k))
        return p

    def posture(self, pose):
        """Stooped, the face low and forward."""
        p = dict(pose)
        p["spine2"] = add(p.get("spine2"), (8.0, 0.0, 0.0))
        p["chest"] = add(p.get("chest"), (10.0, 0.0, 0.0))
        p["neck0"] = add(p.get("neck0"), (14.0, 0.0, 0.0))
        p["neck2"] = add(p.get("neck2"), (-10.0, 0.0, 0.0))
        return p

    def small_tape(self, t):
        """The small hands pulling tape out of its own shoulders."""
        p = {}
        for side, s in (("L", 1.0), ("R", -1.0)):
            ph = t + (0 if s > 0 else 2.2)
            p[f"shoulder_{side}2"] = (-110 - 20 * math.sin(ph), -20 * s, 0.0)
            p[f"elbow_{side}2"] = (0.0, 0.0, 30 * s + 10 * s * math.sin(ph))
            p.update(small_curl(side, 70 + 15 * math.sin(ph)))
        return p

    def adapt(self, name, i, n, pose):
        t = 2 * math.pi * i / max(n, 1)
        moving = name in ("walk", "stoop_walk", "run", "crawl", "leap", "swim")
        p = self.posture(pose)
        p.update(self.small_tape(t))
        p.update(self.tape_pose(p, t * 2, 1.0 if moving else 0.0))
        open_mouth = name in ("scream", "hold", "lunge")
        p.update(self.mouth_tape(min(1.0, (i + 1) / max(n, 1) * 1.4) if open_mouth else 0.0))
        return p

    def chewed(self, frames, order):
        """Frames out of order, held and skipped, the way a chewed tape plays."""
        return [frames[k] for k in order]

    def extra_clips(self):
        base = P._clips()
        fps, loop, run = base["run"]
        run = [self.adapt("run", i, len(run), dict(f)) for i, f in enumerate(run)]
        run = self.chewed(run, [0, 0, 1, 3, 3, 4, 6, 7])
        # the lunge, low and forward, no arms flung wide: the small hands out, the jaw down, the tape
        start = merge(P.POSES["stand"], {"jaw": 0.0})
        end = merge(finger_curl(-10, "L", 8), finger_curl(-10, "R", 8),
                    {"spine1": (20, 0, 0), "spine2": (14, 0, 0), "chest": (10, 0, 0), "neck0": (30, 0, 0), "neck2": (-70, 0, 0),
                     "shoulder_L": (-40, -6, 0), "shoulder_R": (-40, 6, 0), "elbow_L": (-30, 0, 0), "elbow_R": (-30, 0, 0),
                     "hip_L": (-34, 0, 0), "knee_L": (44, 0, 0), "hip_R": (14, 0, 0), "jaw": 80.0, "jaw_drop": 0.05})
        lunge = []
        for i, f in enumerate(keyed([(0, start), (5, end), (9, end)], 10)):
            p = self.posture(f)
            p.update(reaching(min(1.0, i / 5.0)))
            p.update(self.tape_pose(p, i * 0.8, 0.6))
            p.update(self.mouth_tape(min(1.0, i / 7.0)))
            lunge.append(p)
        return {"run": (12.0, True, run), "lunge": (12.0, False, lunge)}


FORMS = {"uneasy": Uneasy(), "angry": Angry(), "furious": Furious()}
