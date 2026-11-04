"""Detail painters for the concept sheets: eyes, teeth, pins, skin, tears, drips.

All of these paint straight onto a Scene's pixels (after the geometry is placed), limited to
the given object ids, with the scene's ramps and ordered dithering, so they stay pixel art.
"""
import numpy as np

from pixelart import BAYER4, LIGHT, RAMPS, noise2, rgb


def put(sc, x, y, colour, only=None):
    x, y = int(round(x)), int(round(y))
    if 0 <= x < sc.w and 0 <= y < sc.h and (only is None or sc.obj[y, x] in np.atleast_1d(only)):
        sc.paint[y, x] = rgb(colour) if isinstance(colour, str) else colour
        sc.painted[y, x] = True
        return True
    return False


def ramp_at(ramp, v, x=0, y=0):
    R = RAMPS[ramp]
    b = BAYER4[int(y) % 4, int(x) % 4]
    return R[int(np.clip(np.floor(v * (len(R) - 1) + 0.5 + b), 0, len(R) - 1))]


def lit(sc):
    return sc.amb + sc.dif * np.clip(sc.n @ LIGHT, 0, 1)


def current(sc):
    """The colour each pixel has right now (painted, or its material's mid tone)."""
    out = np.zeros((sc.h, sc.w, 3))
    for name in set(sc.mat[sc.obj >= 0].tolist()) - {""}:
        R = RAMPS[name]
        out[sc.mat == name] = R[len(R) // 2]
    out[sc.painted] = sc.paint[sc.painted]
    return out


def darken(sc, mask, factor, only=None):
    """Multiply the current colours under mask (factor < 1 darkens)."""
    m = mask.copy()
    if only is not None:
        m &= np.isin(sc.obj, np.atleast_1d(only))
    cur = current(sc)
    f = np.broadcast_to(np.asarray(factor, float), (sc.h, sc.w))
    sc.paint[m] = cur[m] * f[m][:, None]
    sc.painted[m] = True


# -- skin -------------------------------------------------------------------------------------------

def skin(sc, obj, ramp, seed, offset=0.0, gain=1.0, mottle=0.14, pores=0.0, blotches=0.0, veins=0,
         edge=0.0, extra=None, light=True):
    """Lit, mottled skin (or paper, plaster...) on an object's own pixels."""
    rng = np.random.default_rng(seed)
    m = np.isin(sc.obj, np.atleast_1d(obj))
    v = (lit(sc) * gain if light else np.full((sc.h, sc.w), gain)) + offset
    v = v + mottle * (noise2(sc.w, sc.h, 9, seed, 4) - 0.5)
    if blotches:
        bl = noise2(sc.w, sc.h, 16, seed + 5, 2)
        v = v - blotches * np.clip(bl - 0.58, 0, 1) * 4
    if pores:
        pm = rng.random((sc.h, sc.w)) < pores
        v = v - 0.12 * pm
    if edge:
        er = np.zeros_like(m)
        for dy, dx in ((0, 1), (0, -1), (1, 0), (-1, 0), (2, 0), (-2, 0), (0, 2), (0, -2)):
            er |= np.roll(np.roll(~m, dy, 0), dx, 1)
        v = v - edge * (er & m)
    if extra is not None:
        v = v + extra
    sc.paint_ramp(m, ramp, v)
    if veins:
        for i in range(veins):
            ys, xs = np.nonzero(m)
            if not len(xs):
                break
            k = rng.integers(len(xs))
            walk(sc, xs[k], ys[k], rng.uniform(0, 2 * np.pi), int(rng.integers(10, 40)), rng,
                 colour=None, ramp=ramp, value_drop=0.16, only=obj, wobble=0.35, branch=0.04)
    return v


def walk(sc, x, y, heading, steps, rng, colour=None, ramp=None, value_drop=0.15, only=None, wobble=0.35,
         branch=0.0, thick=False, mask=None):
    """A random-walk line: veins, capillaries, cracks."""
    for s in range(steps):
        heading += rng.normal(0, wobble)
        x += np.cos(heading)
        y += np.sin(heading)
        xi, yi = int(round(x)), int(round(y))
        if not (0 <= xi < sc.w and 0 <= yi < sc.h):
            return
        if mask is not None and not mask[yi, xi]:
            return
        if only is not None and sc.obj[yi, xi] not in np.atleast_1d(only):
            return
        if colour is not None:
            c = rgb(colour) if isinstance(colour, str) else colour
        else:
            cur = sc.paint[yi, xi] if sc.painted[yi, xi] else RAMPS[ramp][len(RAMPS[ramp]) // 2]
            c = cur * (1 - value_drop)
        sc.paint[yi, xi] = c
        sc.painted[yi, xi] = True
        if thick:
            put(sc, x + np.sin(heading), y - np.cos(heading), c, only)
        if branch and rng.random() < branch:
            walk(sc, x, y, heading + rng.choice([-1, 1]) * rng.uniform(0.5, 1.1), steps // 2, rng, colour, ramp,
                 value_drop, only, wobble, branch * 0.5, False, mask)


# -- eyes -----------------------------------------------------------------------------------------

def almond(sc, cx, cy, w, h, tilt=0.0, droop=0.0):
    """Mask of an almond-shaped eye opening (intersection of two big circles)."""
    ca, sa = np.cos(tilt), np.sin(tilt)
    lx = (sc.X - cx) * ca + (sc.Y - cy) * sa
    ly = -(sc.X - cx) * sa + (sc.Y - cy) * ca
    hw = w / 2
    # upper arc and lower arc radii from half-width and heights
    up_h, lo_h = h * 0.55, h * 0.45
    R1 = (hw * hw + up_h * up_h) / (2 * up_h)
    R2 = (hw * hw + lo_h * lo_h) / (2 * lo_h)
    upper = lx ** 2 + (ly - (R1 - up_h) - droop * lx / hw * h * 0.3) ** 2 <= R1 * R1
    lower = lx ** 2 + (ly + (R2 - lo_h)) ** 2 <= R2 * R2
    return upper & lower, lx, ly


def eye(sc, obj, cx, cy, w, h, skin_ramp="pale", look=(0.0, 0.0), iris=0.42, pupil=0.4, red=0.35, seed=0,
        tilt=0.0, droop=0.0, socket=1.0, socket_depth=0.32, lid=0.22, lashes=True, iris_ramp="iris", wet=True,
        dead=False, caps=10, lower_lid=True):
    """A realistic wet eye in a sunken socket. iris is a fraction of the width; pupil of the iris."""
    rng = np.random.default_rng(seed)
    # socket: shadow rings, deepest above the eye and at the inner corner
    if socket:
        ca, sa = np.cos(tilt), np.sin(tilt)
        lx0 = (sc.X - cx) * ca + (sc.Y - cy + h * 0.18) * sa
        ly0 = -(sc.X - cx) * sa + (sc.Y - cy + h * 0.18) * ca
        q = np.sqrt((lx0 / (w * 0.5 * 1.9 * socket)) ** 2 + (ly0 / (h * 0.62 * 1.9 * socket)) ** 2)
        f = 1 - socket_depth * 1.6 * np.clip(1 - q, 0, 1) ** 1.3
        b = BAYER4[(np.arange(sc.h)[:, None] % 4), (np.arange(sc.w)[None, :] % 4)]
        f = np.round((f + b * 0.06) * 12) / 12
        darken(sc, q < 1, f, obj)
    m, lx, ly = almond(sc, cx, cy, w, h, tilt, droop)
    m &= np.isin(sc.obj, np.atleast_1d(obj))
    if not m.any():
        return m
    # sclera: dingy, darker toward the corners and under the lid, pink at the inner corner
    r = np.sqrt((lx / (w / 2)) ** 2 + (ly / (h / 2)) ** 2)
    v = 0.78 - 0.32 * np.clip(r - 0.35, 0, 1) - 0.25 * np.clip(-ly / (h / 2) - 0.2, 0, 1) - 0.12 * red
    sc.paint_ramp(m, "bone", v)
    inner = m & (sc.ell(cx - w * 0.44, cy + h * 0.05, w * 0.12, h * 0.32, tilt))
    sc.paint_ramp(inner, "gum", 0.62)
    # capillaries creeping in from the edges
    for i in range(int(caps * (0.4 + red))):
        a = rng.uniform(0, 2 * np.pi)
        sx, sy = cx + np.cos(a) * w * 0.47, cy + np.sin(a) * h * 0.4
        walk(sc, sx, sy, a + np.pi + rng.normal(0, 0.5), int(rng.integers(3, max(4, int(w * 0.28)))), rng,
             colour=ramp_at("red", 0.55 + 0.2 * rng.random()), mask=m, wobble=0.5, branch=0.15)
    # iris and pupil
    ir = w * iris * 0.5
    ix = cx + look[0] * (w * 0.5 - ir) * 0.85
    iy = cy + look[1] * h * 0.15 + h * 0.04
    d = np.sqrt((sc.X - ix) ** 2 + (sc.Y - iy) ** 2)
    ang = np.arctan2(sc.Y - iy, sc.X - ix)
    mi = m & (d <= ir)
    spokes = 0.5 + 0.5 * np.sin(ang * 23 + 3 * noise2(sc.w, sc.h, 3, seed + 1, 1))
    iv = 0.55 - 0.35 * (d / ir) + 0.18 * (spokes - 0.5) + 0.1 * (noise2(sc.w, sc.h, 2, seed + 2, 2) - 0.5)
    iv = np.where(d > ir * 0.82, 0.05, iv)
    if dead:
        iv = iv * 0.5 + 0.35
    sc.paint_ramp(mi, iris_ramp, iv)
    pr = ir * pupil
    sc.paint_mask(m & (d <= pr), "#030303")
    # the lid's shadow across the top of the eye, and the lash line
    lidm = m & (ly < -h * (0.5 - lid))
    sc.paint_ramp(lidm, skin_ramp, 0.12)
    if lashes:
        edge = m & ~np.roll(m, 1, 0)
        sc.paint_mask(edge, "#140c0a")
        edge2 = m & ~np.roll(m, 2, 0) & ~edge
        sc.paint_ramp(edge2, skin_ramp, 0.08)
    if lower_lid:
        lo = ~m & np.roll(m, 1, 0) & np.isin(sc.obj, np.atleast_1d(obj))
        sc.paint_ramp(lo, "gum", 0.72)
    if wet:
        hx, hy = ix - ir * 0.35, iy - ir * 0.4
        for dx, dy in ((0, 0), (1, 0), (0, 1)):
            put(sc, hx + dx, hy + dy, "#fffdf4", obj)
        put(sc, ix + ir * 0.45, iy + ir * 0.3, "#c9c4b8", obj)
    return m


def hole(sc, obj, cx, cy, rx, ry, skin_ramp, angle=0.0, glints=((0.0, 0.25),), depth=1.0):
    """A deep empty socket that falls away to black, with wet pinpoints at the bottom."""
    ca, sa = np.cos(angle), np.sin(angle)
    lx0 = (sc.X - cx) * ca + (sc.Y - cy) * sa
    ly0 = -(sc.X - cx) * sa + (sc.Y - cy) * ca
    q = np.sqrt((lx0 / (rx * 1.7)) ** 2 + (ly0 / (ry * 1.7)) ** 2)
    f = 1 - 0.75 * depth * np.clip(1 - q, 0, 1) ** 0.9
    b = BAYER4[(np.arange(sc.h)[:, None] % 4), (np.arange(sc.w)[None, :] % 4)]
    f = np.round((f + b * 0.06) * 12) / 12
    darken(sc, q < 1, f, obj)
    inner = sc.ell(cx, cy + ry * 0.08, rx * 0.82, ry * 0.82, angle) & np.isin(sc.obj, np.atleast_1d(obj))
    dd = np.sqrt(((sc.X - cx) / rx) ** 2 + ((sc.Y - cy) / ry) ** 2)
    sc.paint_ramp(inner, "black", 0.35 - 0.4 * (1 - dd))
    for gx, gy in glints:
        put(sc, cx + gx * rx, cy + gy * ry, "#f4efe2", obj)
        put(sc, cx + gx * rx + 1, cy + gy * ry, "#6e665a", obj)


# -- noses, lips, teeth ----------------------------------------------------------------------------

def nose(sc, obj, cx, top, bottom, w, ramp, hook=0.0, light=0.0, nostrils=True, crooked=0.0):
    """A long nose from the front: lit bridge, shadowed far side, a heavy tip, flared nostrils."""
    L = bottom - top
    o = np.isin(sc.obj, np.atleast_1d(obj))
    base = lit(sc) + light
    t = np.clip((sc.Y - top) / L, 0, 1)
    xc = cx + crooked * t * w + hook * t ** 3
    halfw = w * (0.18 + 0.32 * t ** 1.6)
    bridge = o & (sc.Y >= top) & (sc.Y <= bottom) & (np.abs(sc.X - xc) <= halfw)
    sc.paint_ramp(bridge & (sc.X > xc + halfw * 0.25), ramp, base - 0.28 - 0.1 * t)
    sc.paint_ramp(bridge & (sc.X <= xc + halfw * 0.25) & (sc.X > xc - halfw * 0.35), ramp, base + 0.12)
    sc.paint_ramp(bridge & (sc.X <= xc - halfw * 0.35), ramp, base - 0.06)
    tipx = cx + crooked * w + hook
    tip = o & sc.ell(tipx, bottom - L * 0.08, w * 0.5, w * 0.36)
    sc.paint_ramp(tip, ramp, base + 0.04 + 0.12 * (sc.Y < bottom - L * 0.12))
    put(sc, tipx - w * 0.15, bottom - L * 0.14, ramp_at(ramp, 0.95), obj)
    if nostrils:
        for s in (-1, 1):
            wing = o & sc.ell(tipx + s * w * 0.5, bottom - L * 0.02, w * 0.28, w * 0.26)
            sc.paint_ramp(wing, ramp, base - 0.12)
            sc.paint_mask(o & sc.ell(tipx + s * w * 0.3, bottom + w * 0.08, w * 0.17, w * 0.1, -s * 0.4), "#0d0606")
    shadow = o & sc.ell(tipx, bottom + w * 0.32, w * 0.75, w * 0.16) & ~sc.ell(tipx, bottom - L * 0.08, w * 0.5, w * 0.36)
    sc.paint_ramp(shadow, ramp, base - 0.3)


def lips(sc, obj, cx, cy, w, ramp="flesh", curve=0.0, part=0.0, v=0.0, chapped=True, seed=0, thin=1.0):
    """Closed (or barely parted) thin lips with a dark line between them."""
    rng = np.random.default_rng(seed)
    o = np.isin(sc.obj, np.atleast_1d(obj))
    k = (sc.X - cx) / (w / 2)
    inside = np.abs(k) <= 1
    yline = cy - curve * k ** 2
    taper = np.clip(1 - k ** 4, 0, 1)
    up = o & inside & (sc.Y >= yline - 3.2 * thin * taper - 0.5) & (sc.Y < yline)
    lo = o & inside & (sc.Y >= yline + part) & (sc.Y < yline + part + 3.8 * thin * taper + 0.5)
    base = lit(sc) + v
    sc.paint_ramp(up, ramp, base - 0.18)
    sc.paint_ramp(lo, ramp, base + 0.06 - 0.1 * ((sc.Y - yline - part) / (3.8 * thin + 1)))
    line = o & inside & (sc.Y >= yline - 0.6) & (sc.Y < yline + part + 0.6) & (taper > 0.05)
    sc.paint_mask(line, "#1a0909")
    if chapped:
        for i in range(int(w * 0.5)):
            x = cx + rng.uniform(-0.8, 0.8) * w / 2
            kk = (x - cx) / (w / 2)
            y0 = cy - curve * kk ** 2 + part + 1
            for j in range(int(rng.integers(1, 3))):
                put(sc, x, y0 + j, ramp_at(ramp, 0.2), obj)
    # a highlight on the lower lip
    hl = lo & (np.abs(k) < 0.35) & (sc.Y > yline + part + 1.0) & (sc.Y < yline + part + 2.0)
    sc.paint_ramp(hl, ramp, base + 0.25)


def maw(sc, obj, cx, top, w, h, seed=0, teeth_top=True, teeth_bottom=True, tooth_w=None, missing=0.12,
        saliva=4, gum_h=None, curve=0.0, tongue=True, lip_ramp="flesh", depth_ramp="gum"):
    """An open mouth: dark wet inside, gums, rows of small worn human teeth, saliva strings."""
    rng = np.random.default_rng(seed)
    o = np.isin(sc.obj, np.atleast_1d(obj))
    k = (sc.X - cx) / (w / 2)
    inside_x = np.abs(k) < 1
    top_y = top - curve * k ** 2
    bot_y = top + h * np.sqrt(np.clip(1 - k ** 2, 0, 1)) ** 0.8
    m = o & inside_x & (sc.Y >= top_y) & (sc.Y <= bot_y)
    depth = np.clip((sc.Y - top_y) / max(h, 1), 0, 1)
    centre = 1 - np.clip(np.abs(k), 0, 1)
    sc.paint_ramp(m, depth_ramp, 0.28 - 0.3 * centre * np.sin(np.pi * depth))
    if tongue:
        tm = m & sc.ell(cx + w * 0.05, top + h * 0.82, w * 0.3, h * 0.2)
        sc.paint_ramp(tm, "gum", 0.45 + 0.15 * (sc.Y < top + h * 0.78))
    tw = tooth_w or max(w / 14, 3)
    gh = gum_h or max(h * 0.12, 2)
    if teeth_top:
        gm = m & (sc.Y < top_y + gh)
        sc.paint_ramp(gm, "gum", 0.55 + 0.2 * (sc.Y < top_y + gh * 0.4))
        x = cx - w / 2 + tw * 0.4
        while x < cx + w / 2 - tw * 0.4:
            kk = (x - cx) / (w / 2)
            this_w = tw * (0.75 + 0.5 * rng.random()) * (1 - 0.35 * kk * kk)
            if rng.random() > missing:
                ty = top - curve * kk * kk + gh * 0.6
                th = tw * (1.3 + 0.7 * rng.random()) * (1 - 0.4 * kk * kk)
                tooth(sc, obj, x, ty, this_w, th, rng, down=True)
            x += this_w + (1 if rng.random() < 0.3 else 0)
    if teeth_bottom:
        x = cx - w / 2 + tw * 0.9
        while x < cx + w / 2 - tw * 0.9:
            kk = (x - cx) / (w / 2)
            this_w = tw * (0.7 + 0.45 * rng.random()) * (1 - 0.35 * kk * kk)
            yb = top + h * np.sqrt(max(1 - kk * kk, 0)) ** 0.8
            gm = m & (np.abs(sc.X - x) < this_w) & (sc.Y > yb - gh)
            sc.paint_ramp(gm, "gum", 0.5)
            if rng.random() > missing:
                th = tw * (1.0 + 0.6 * rng.random()) * (1 - 0.4 * kk * kk)
                tooth(sc, obj, x, yb - gh * 0.6, this_w, th, rng, down=False)
            x += this_w + (1 if rng.random() < 0.3 else 0)
    for i in range(saliva):
        sx = cx + rng.uniform(-0.6, 0.6) * w / 2
        kk = (sx - cx) / (w / 2)
        y0 = top - curve * kk * kk + gh * 1.8
        y1 = top + h * np.sqrt(max(1 - kk * kk, 0)) ** 0.8 - gh * 1.6
        sag = rng.uniform(1, 4)
        n = int(max(y1 - y0, 2))
        for j in range(n):
            t = j / n
            xx = sx + sag * np.sin(np.pi * t) * rng.choice([-1, 1]) * 0.3
            put(sc, xx, y0 + t * (y1 - y0), ramp_at("bone", 0.55 + 0.25 * np.sin(np.pi * t), xx, y0), obj)
    return m


def tooth(sc, obj, x, y, w, h, rng, down=True):
    """One small worn human tooth (rounded rectangle) hanging down (or standing up) from y."""
    o = np.isin(sc.obj, np.atleast_1d(obj))
    tilt = rng.normal(0, 0.08)
    lx = (sc.X - x) * np.cos(tilt) + (sc.Y - y) * np.sin(tilt)
    ly = -(sc.X - x) * np.sin(tilt) + (sc.Y - y) * np.cos(tilt)
    ly = ly if down else -ly
    hw = w / 2
    m = o & (np.abs(lx) <= hw) & (ly >= 0) & (ly <= h)
    rr = min(hw, 2.0)
    corner = (ly > h - rr) & (np.abs(lx) > hw - rr) & (((np.abs(lx) - (hw - rr)) ** 2 + (ly - (h - rr)) ** 2) > rr * rr)
    m &= ~corner
    t = np.clip(ly / max(h, 1), 0, 1)
    side = np.clip(lx / hw, -1, 1)
    stain = 0.35 * (1 - t) ** 2 + 0.15 * rng.random()
    v = 0.78 - stain - 0.18 * np.abs(side) + 0.08 * (side < -0.2)
    sc.paint_ramp(m, "teeth", v)
    if rng.random() < 0.3:
        crack = m & (np.abs(lx - rng.uniform(-hw * 0.4, hw * 0.4)) < 0.6) & (ly > h * 0.3)
        sc.paint_ramp(crack, "teeth", 0.25)
    gap = o & ((np.abs(np.abs(lx) - hw) < 0.7) & (ly >= 0) & (ly <= h * 0.92))
    sc.paint_ramp(gap & ~m, "gum", 0.08)


# -- pins, drips, tears, stitches ------------------------------------------------------------------

def pin(sc, p0, p1, head=2.0, wound_obj=None, wound_ramp="red", drip=0, rng=None, z=500, shaft="metal",
        head_col="#d9d4c8", light=1.0, wound=2.2):
    """A long pin entering at p0 and ending at p1 with a round head, drawn on top of everything."""
    rng = rng or np.random.default_rng(0)
    p0 = np.array(p0[:2], float)
    p1 = np.array(p1[:2], float)
    d = p1 - p0
    L = np.linalg.norm(d)
    if L < 1:
        return
    u = d / L
    n = np.array([-u[1], u[0]])
    lit_side = 1 if n @ LIGHT[:2] < 0 else -1
    obj = sc.new_id()
    sc.no_outline.add(obj)
    for i in range(int(L) + 1):
        q = p0 + u * i
        for off, val in ((0, 0.62), (lit_side, 0.92), (-lit_side, 0.25)):
            x, y = q + n * off * 0.6
            xi, yi = int(round(x)), int(round(y))
            if 0 <= xi < sc.w and 0 <= yi < sc.h:
                if off != 0 and L < 30:
                    continue
                sc.paint[yi, xi] = ramp_at(shaft, val * light, xi, yi)
                sc.painted[yi, xi] = True
                sc.obj[yi, xi] = obj
                sc.z[yi, xi] = z
    if head:
        hm = sc.ell(p1[0], p1[1], head, head)
        hd = np.sqrt((sc.X - p1[0] + head * 0.35) ** 2 + (sc.Y - p1[1] + head * 0.35) ** 2) / head
        c = rgb(head_col)
        sc.paint[hm] = (c * light * np.clip(1.05 - 0.55 * hd, 0.35, 1.05)[..., None])[hm]
        sc.painted[hm] = True
        sc.obj[hm] = obj
    if wound_obj is not None:
        wm = sc.ell(p0[0], p0[1], wound, wound * 0.8) & np.isin(sc.obj, np.atleast_1d(wound_obj))
        sc.paint_ramp(wm, wound_ramp, 0.12)
        put(sc, p0[0], p0[1], "#0a0303")
        for i in range(drip):
            dx = p0[0] + rng.uniform(-1.2, 1.2)
            ln = int(rng.integers(4, 16))
            for j in range(ln):
                v = 0.35 - 0.2 * j / ln
                put(sc, dx + 0.15 * np.sin(j * 0.7), p0[1] + 1 + j, ramp_at(wound_ramp, v, dx, j), wound_obj)
            put(sc, dx, p0[1] + 1 + ln, ramp_at(wound_ramp, 0.5), wound_obj)


def drips(sc, obj, x0, x1, y, n, rng, ramp="rust", length=(4, 18), v=0.3):
    for i in range(n):
        x = rng.uniform(x0, x1)
        ln = int(rng.integers(*length))
        for j in range(ln):
            put(sc, x + 0.2 * np.sin(j * 0.5), y + j, ramp_at(ramp, v - 0.15 * j / ln, x, y + j), obj)


def stitches(sc, obj, pts, spacing=5, size=3, colour="#1c1410", rng=None):
    """Crude stitches along a polyline: an X or slash every few pixels."""
    rng = rng or np.random.default_rng(0)
    pts = np.array(pts, float)
    acc = 0.0
    for a, b in zip(pts[:-1], pts[1:]):
        seg = b - a
        L = np.linalg.norm(seg)
        u = seg / max(L, 1e-9)
        n = np.array([-u[1], u[0]])
        t = spacing - acc
        while t < L:
            c = a + u * t
            for s in np.linspace(-1, 1, size * 2 + 1):
                q = c + n * s * size + u * s * 0.8 + rng.normal(0, 0.15, 2)
                put(sc, q[0], q[1], colour, obj)
            t += spacing
        acc = (acc + L) % spacing
    for a, b in zip(pts[:-1], pts[1:]):
        L = int(np.linalg.norm(b - a))
        for i in range(L):
            q = a + (b - a) * i / max(L, 1)
            if i % 2 == 0:
                put(sc, q[0], q[1], "#3a1a16", obj)


def tear(sc, obj, pts, rng, fibre="paper", inside="#0b0707", depth_ramp=None):
    """A ragged hole through paper or cloth: dark inside, frayed light fibres on its edge."""
    from pixelart import poly_mask
    pts = np.array(pts, float)
    jag = []
    for a, b in zip(pts, np.roll(pts, -1, 0)):
        L = np.linalg.norm(b - a)
        for i in range(max(int(L / 2.5), 1)):
            q = a + (b - a) * i / max(int(L / 2.5), 1)
            jag.append(q + rng.normal(0, 1.1, 2))
    m = poly_mask(sc.w, sc.h, jag) & np.isin(sc.obj, np.atleast_1d(obj))
    if depth_ramp:
        cy = np.mean(pts[:, 1])
        sc.paint_ramp(m, depth_ramp, 0.15 + 0.1 * np.sin((sc.Y - cy) * 0.2))
    else:
        sc.paint_mask(m, inside)
    edge = ~m & (np.roll(m, 1, 0) | np.roll(m, -1, 0) | np.roll(m, 1, 1) | np.roll(m, -1, 1))
    edge &= np.isin(sc.obj, np.atleast_1d(obj))
    ys, xs = np.nonzero(edge)
    for x, y in zip(xs, ys):
        if rng.random() < 0.55:
            sc.paint[y, x] = ramp_at(fibre, 0.62 + 0.14 * rng.random(), x, y)
            sc.painted[y, x] = True
            if rng.random() < 0.12:
                put(sc, x + rng.integers(-1, 2), y + rng.integers(-1, 2), ramp_at(fibre, 0.78), obj)
    return m


def wrinkles(sc, obj, cx, cy, w, n, rng, ramp, angle=0.0, spread=0.3, length=0.5, value=-0.18):
    """Fine curved creases (under eyes, at mouth corners)."""
    o = np.isin(sc.obj, np.atleast_1d(obj))
    for i in range(n):
        a = angle + rng.normal(0, spread)
        L = int(w * length * rng.uniform(0.6, 1.2))
        x = cx + rng.normal(0, w * 0.15)
        y = cy + rng.normal(0, w * 0.08)
        bend = rng.normal(0, 0.03)
        for j in range(L):
            a += bend
            x += np.cos(a)
            y += np.sin(a)
            xi, yi = int(round(x)), int(round(y))
            if 0 <= xi < sc.w and 0 <= yi < sc.h and o[yi, xi]:
                base = sc.paint[yi, xi] if sc.painted[yi, xi] else RAMPS[ramp][3]
                sc.paint[yi, xi] = base * (1 + value)
                sc.painted[yi, xi] = True


def finger(sc, pts, r0, r1, ramp, obj=None, z=0.0, creases=True, nail=True, rng=None, nail_ramp="teeth", dirt=0.0):
    """A fleshy finger along a polyline of joints (base to tip): tapered, slightly swollen at the
    knuckles, creased across each joint, with a nail at the tip."""
    rng = rng or np.random.default_rng(0)
    obj = sc.new_id() if obj is None else obj
    pts = [np.asarray(p, float) for p in pts]
    n = len(pts) - 1
    for i in range(n):
        a, b = pts[i], pts[i + 1]
        ra = r0 + (r1 - r0) * i / n
        rb = r0 + (r1 - r0) * (i + 1) / n
        sc.capsule((a[0], a[1], z), (b[0], b[1], z), ra, rb, ramp, obj)
        if i > 0:
            sc.ellipsoid((a[0], a[1], z), ra * 1.12, ra * 1.12, ra, ramp, obj)
    tip = pts[-1]
    d = pts[-1] - pts[-2]
    d /= np.linalg.norm(d) + 1e-9
    out = {"obj": obj, "tip": tip, "dir": d, "joints": pts}
    return out


def finish_finger(sc, f, ramp, rng, nail_ramp="teeth", dirt=0.0, crease_value=-0.22):
    """Paint creases at the knuckles and the nail, after skin() has painted the flesh."""
    obj = f["obj"]
    pts = f["joints"]
    for i in range(1, len(pts) - 1):
        a, b = pts[i - 1], pts[i + 1]
        d = b - a
        d /= np.linalg.norm(d) + 1e-9
        nrm = np.array([-d[1], d[0]])
        for k in range(3):
            off = (k - 1) * 1.6
            c = pts[i] + d * off
            L = 6 + rng.integers(0, 4)
            for t in np.linspace(-L / 2, L / 2, int(L * 2)):
                q = c + nrm * t + d * 0.4 * np.sin(t * 0.7)
                xi, yi = int(round(q[0])), int(round(q[1]))
                if 0 <= xi < sc.w and 0 <= yi < sc.h and sc.obj[yi, xi] == obj:
                    base = sc.paint[yi, xi] if sc.painted[yi, xi] else RAMPS[ramp][4]
                    sc.paint[yi, xi] = base * (1 + crease_value * (1 - abs(t) / (L / 2)) ** 0.5)
                    sc.painted[yi, xi] = True
    tip = f["tip"]
    d = f["dir"]
    nail_c = tip - d * 4.0
    nrm = np.array([-d[1], d[0]])
    ang = np.arctan2(d[1], d[0])
    m = sc.ell(nail_c[0], nail_c[1], 5.0, 3.3, ang) & (sc.obj == obj)
    sc.paint_ramp(m, nail_ramp, 0.55 - dirt * 0.3)
    cut = sc.ell(nail_c[0] + d[0] * 3.5, nail_c[1] + d[1] * 3.5, 2.2, 3.0, ang) & m
    sc.paint_ramp(cut, "rust", 0.25 + 0.2 * (1 - dirt))
    cuticle = sc.ell(nail_c[0] - d[0] * 4, nail_c[1] - d[1] * 4, 1.5, 3.4, ang) & (sc.obj == obj) & ~m
    sc.paint_ramp(cuticle, "red", 0.45)
