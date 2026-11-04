"""A tiny pixel-art renderer for concept sheets.

Shapes are drawn as shaded 2.5D primitives (tapered capsules, ellipsoids, flat polygons, lines)
into a z-buffered canvas, then lit, quantised to a short colour ramp per material with ordered
dithering, outlined, and scaled up with nearest-neighbour. Faces and textures are painted
straight onto the pixels afterwards. Image coordinates: x right, y down, z toward the viewer.
"""
import numpy as np
from PIL import Image, ImageDraw, ImageFont

BAYER4 = (np.array([[0, 8, 2, 10], [12, 4, 14, 6], [3, 11, 1, 9], [15, 7, 13, 5]]) + 0.5) / 16.0 - 0.5
LIGHT = np.array([-0.55, -0.65, 0.75])
LIGHT = LIGHT / np.linalg.norm(LIGHT)


def rgb(h):
    h = h.lstrip("#")
    return np.array([int(h[i:i + 2], 16) for i in (0, 2, 4)], float)


RAMPS = {
    # every ramp runs dark to light
    "black": ["#040305", "#09080b", "#100e12", "#18151a", "#221e24", "#2e2830"],
    "paper": ["#4a4234", "#655a47", "#83765d", "#a29478", "#beb094", "#d6caaf", "#e9e0ca", "#f5efe0"],
    "stain": ["#3b2a1a", "#5a4026", "#7a5a36", "#9a7a4c"],
    "flesh": ["#3c2224", "#5a3434", "#7a4a46", "#9c665c", "#bb8574", "#d4a48f", "#e7c2ad", "#f3dccb"],
    "bone": ["#3e3a33", "#5d574c", "#7f7868", "#a29a87", "#c3bba6", "#dcd5c1", "#eeeadb", "#fbf8ef"],
    "red": ["#2a0507", "#4a0a0d", "#6e1214", "#951d1c", "#b8302a", "#d24c3b"],
    "metal": ["#202024", "#3b3b42", "#5f5f68", "#8b8b95", "#b8b8c2", "#e2e2ea"],
    "gold": ["#2e2010", "#4e3818", "#735325", "#9a7334", "#c09a4c", "#e0c47a"],
    "wood": ["#1d130c", "#2e1f14", "#43301f", "#5a412a", "#735437", "#8c6a47"],
    "wall": ["#2a2219", "#3a3024", "#4d4030", "#61513c", "#77654b", "#8e7a5c", "#a6916e", "#bca782"],
    "night": ["#06070b", "#0b0d14", "#11141e", "#181c29", "#212636", "#2b3144"],
    "tape": ["#0a0706", "#16100c", "#241a13", "#33251a", "#463324", "#5c4430"],
    "cloth": ["#07060a", "#0e0c12", "#16131b", "#201b26", "#2b2532", "#373040"],
    "pale": ["#352c2c", "#544645", "#76625e", "#97817a", "#b6a097", "#cfbcb2", "#e3d5cc", "#f3ebe5"],
    "leaf": ["#0a140c", "#101e13", "#17291a", "#1f3522", "#29432b", "#345336"],
    "sky": ["#121628", "#1c2236", "#2a2f45", "#3d3f52", "#57525c", "#766a68", "#957f6f", "#ae9275"],
    "gum": ["#1e0608", "#3a0d12", "#5c1720", "#80242c", "#a1373b", "#bd5653", "#d27b72"],
    "teeth": ["#2e261a", "#4d412c", "#73654a", "#9a8b68", "#bcae88", "#d6cba6", "#e9e1c4", "#f6f1de"],
    "iris": ["#101412", "#1d2522", "#2f3b36", "#46554c", "#627262", "#83907c", "#a3ad98"],
    "rust": ["#22100a", "#3d1d0f", "#5c2d15", "#7b401d", "#9a5a2c"],
    "sheet": ["#2a0b0b", "#4a1414", "#6b1f1c", "#8c2d26", "#a83f33", "#c25a45", "#d77f63", "#e6a78a"],
    "grey": ["#1a1a1a", "#2c2c2c", "#404040", "#565656", "#6e6e6e", "#888888", "#a3a3a3", "#bfbfbf"],
}
RAMPS = {k: np.array([rgb(c) for c in v]) for k, v in RAMPS.items()}


class Scene:
    """A z-buffered canvas of shaded primitives."""

    def __init__(self, w, h, bg=None):
        self.w, self.h = w, h
        self.z = np.full((h, w), -1e9)
        self.n = np.zeros((h, w, 3))
        self.n[..., 2] = 1.0
        self.mat = np.full((h, w), "", dtype=object)
        self.obj = np.full((h, w), -1, int)
        self.bias = np.zeros((h, w))  # added to the lit value (texture darkening/lightening)
        self.flat = np.full((h, w), np.nan)  # explicit lit value, ignores the normal
        self.paint = np.zeros((h, w, 3))
        self.painted = np.zeros((h, w), bool)
        self.bg = np.zeros((h, w, 3))
        if bg is not None:
            self.bg[:] = rgb(bg) if isinstance(bg, str) else bg
        self.amb = 0.28
        self.dif = 0.85
        self.ids = 0
        self.no_outline = set()
        yy, xx = np.mgrid[0:h, 0:w]
        self.X = xx + 0.5
        self.Y = yy + 0.5

    def new_id(self):
        self.ids += 1
        return self.ids

    # -- writing ---------------------------------------------------------------------------
    def _write(self, mask, z, n, mat, obj, flat=None, bias=None):
        m = mask & (z > self.z)
        self.z[m] = z[m]
        self.n[m] = n[m]
        self.mat[m] = mat
        self.obj[m] = obj
        self.flat[m] = np.nan if flat is None else (flat[m] if np.ndim(flat) else flat)
        self.bias[m] = 0.0 if bias is None else (bias[m] if np.ndim(bias) else bias)
        self.painted[m] = False
        return obj

    def capsule(self, p0, p1, r0, r1, mat, obj=None, flatten=1.0):
        """A tapered capsule from p0 to p1 (x, y, z) with radii r0 and r1."""
        obj = self.new_id() if obj is None else obj
        p0 = np.asarray(p0, float)
        p1 = np.asarray(p1, float)
        d = p1[:2] - p0[:2]
        dd = max(float(d @ d), 1e-9)
        t = np.clip(((self.X - p0[0]) * d[0] + (self.Y - p0[1]) * d[1]) / dd, 0.0, 1.0)
        cx = p0[0] + t * d[0]
        cy = p0[1] + t * d[1]
        cz = p0[2] + t * (p1[2] - p0[2])
        r = r0 + t * (r1 - r0)
        dx = self.X - cx
        dy = self.Y - cy
        dist = np.sqrt(dx * dx + dy * dy)
        rr = np.maximum(r, 0.35)
        mask = dist <= rr
        q = np.clip(dist / rr, 0, 1)
        nz = np.sqrt(np.clip(1 - q * q, 0, 1))
        n = np.stack([dx / rr, dy / rr, nz * flatten], -1)
        n /= np.linalg.norm(n, axis=-1, keepdims=True) + 1e-9
        z = cz + nz * rr
        return self._write(mask, z, n, mat, obj)

    def ellipsoid(self, c, rx, ry, rz, mat, obj=None, angle=0.0, bias=None):
        obj = self.new_id() if obj is None else obj
        ca, sa = np.cos(angle), np.sin(angle)
        lx = (self.X - c[0]) * ca + (self.Y - c[1]) * sa
        ly = -(self.X - c[0]) * sa + (self.Y - c[1]) * ca
        q = (lx / rx) ** 2 + (ly / ry) ** 2
        mask = q <= 1.0
        nz = np.sqrt(np.clip(1 - q, 0, 1))
        nlx = lx / (rx * rx)
        nly = ly / (ry * ry)
        nx = nlx * ca - nly * sa
        ny = nlx * sa + nly * ca
        n = np.stack([nx, ny, nz / rz], -1)
        n /= np.linalg.norm(n, axis=-1, keepdims=True) + 1e-9
        z = c[2] + nz * rz
        return self._write(mask, z, n, mat, obj, bias=bias)

    def polygon(self, pts, z, mat, obj=None, normal=(0, 0, 1), flat=None, bias=None):
        """A flat polygon. z may be a number or a function z(X, Y)."""
        obj = self.new_id() if obj is None else obj
        mask = poly_mask(self.w, self.h, pts)
        zz = z(self.X, self.Y) if callable(z) else np.full((self.h, self.w), float(z))
        n = np.zeros((self.h, self.w, 3))
        nn = np.asarray(normal, float)
        n[:] = nn / np.linalg.norm(nn)
        return self._write(mask, zz, n, mat, obj, flat=flat, bias=bias)

    def line(self, p0, p1, mat, obj=None, width=1, flat=0.6):
        """A thin flat line (pins, strings). z is interpolated along it. Never outlined."""
        obj = self.new_id() if obj is None else obj
        self.no_outline.add(obj)
        img = Image.new("L", (self.w, self.h), 0)
        ImageDraw.Draw(img).line([(p0[0], p0[1]), (p1[0], p1[1])], fill=255, width=width)
        mask = np.asarray(img) > 0
        d = np.array([p1[0] - p0[0], p1[1] - p0[1]], float)
        dd = max(float(d @ d), 1e-9)
        t = np.clip(((self.X - p0[0]) * d[0] + (self.Y - p0[1]) * d[1]) / dd, 0, 1)
        z = p0[2] + t * (p1[2] - p0[2])
        n = np.zeros((self.h, self.w, 3))
        n[..., 2] = 1
        return self._write(mask, z, n, mat, obj, flat=flat if not callable(flat) else flat(t))

    def polyline(self, pts, mat, obj=None, width=1, flat=0.6):
        obj = self.new_id() if obj is None else obj
        for a, b in zip(pts[:-1], pts[1:]):
            self.line(a, b, mat, obj, width, flat)
        return obj

    # -- painting (after geometry; overrides the shaded colour) --------------------------------
    def paint_mask(self, mask, colour, only_obj=None):
        m = mask.copy()
        if only_obj is not None:
            m &= np.isin(self.obj, np.atleast_1d(only_obj))
        self.paint[m] = rgb(colour) if isinstance(colour, str) else colour
        self.painted[m] = True
        return m

    def paint_ramp(self, mask, ramp, value, only_obj=None, dither=1.0):
        """Paint with a ramp at a lit value (scalar or array), dithered."""
        m = mask.copy()
        if only_obj is not None:
            m &= np.isin(self.obj, np.atleast_1d(only_obj))
        R = RAMPS[ramp]
        v = np.broadcast_to(np.asarray(value, float), (self.h, self.w))
        b = BAYER4[(np.arange(self.h)[:, None] % 4), (np.arange(self.w)[None, :] % 4)]
        idx = np.clip(np.floor(v * (len(R) - 1) + 0.5 + b * dither), 0, len(R) - 1).astype(int)
        self.paint[m] = R[idx[m]]
        self.painted[m] = True
        return m

    def ell(self, cx, cy, rx, ry, angle=0.0):
        ca, sa = np.cos(angle), np.sin(angle)
        lx = (self.X - cx) * ca + (self.Y - cy) * sa
        ly = -(self.X - cx) * sa + (self.Y - cy) * ca
        return (lx / rx) ** 2 + (ly / ry) ** 2 <= 1.0

    def poly(self, pts):
        return poly_mask(self.w, self.h, pts)

    def seg(self, p0, p1, width=1):
        img = Image.new("L", (self.w, self.h), 0)
        ImageDraw.Draw(img).line([tuple(p0[:2]), tuple(p1[:2])], fill=255, width=width)
        return np.asarray(img) > 0

    # -- output ----------------------------------------------------------------------------------
    def render(self, outline=True, dither=1.0, light=None, outline_obj_edges=True, rim=0.0):
        L = LIGHT if light is None else np.asarray(light, float) / np.linalg.norm(light)
        out = self.bg.copy()
        b = BAYER4[(np.arange(self.h)[:, None] % 4), (np.arange(self.w)[None, :] % 4)]
        lit = self.amb + self.dif * np.clip(self.n @ L, 0, 1)
        if rim:
            lit += rim * np.clip(1 - self.n[..., 2], 0, 1) ** 2
        lit = np.where(np.isnan(self.flat), lit, self.flat) + self.bias
        for name in set(self.mat[self.obj >= 0].tolist()) - {""}:
            R = RAMPS[name]
            m = (self.mat == name) & (self.obj >= 0)
            idx = np.clip(np.floor(lit * (len(R) - 1) + 0.5 + b * dither), 0, len(R) - 1).astype(int)
            out[m] = R[idx[m]]
        out[self.painted] = self.paint[self.painted]
        if outline:
            obj = self.obj
            edge = np.zeros_like(obj, bool)
            for dy, dx in ((0, 1), (0, -1), (1, 0), (-1, 0)):
                nb = np.roll(np.roll(obj, dy, 0), dx, 1)
                nz = np.roll(np.roll(self.z, dy, 0), dx, 1)
                diff = (nb != obj) & (obj >= 0)
                if outline_obj_edges:
                    edge |= diff & ((nb < 0) | (nz < self.z - 2.5))
                else:
                    edge |= diff & (nb < 0)
            if self.no_outline:
                edge &= ~np.isin(obj, list(self.no_outline))
            for name in set(self.mat[edge].tolist()) - {""}:
                m = edge & (self.mat == name) & ~self.painted
                out[m] = RAMPS[name][0]
        return out


def poly_mask(w, h, pts):
    img = Image.new("L", (w, h), 0)
    ImageDraw.Draw(img).polygon([(float(p[0]), float(p[1])) for p in pts], fill=255)
    return np.asarray(img) > 0


def noise2(w, h, scale, seed=0, octaves=3):
    """Smooth value noise in [0, 1]."""
    rng = np.random.default_rng(seed)
    out = np.zeros((h, w))
    amp, tot = 1.0, 0.0
    for o in range(octaves):
        s = scale / (2 ** o)
        gw, gh = int(w / s) + 3, int(h / s) + 3
        g = rng.random((gh, gw))
        yy, xx = np.mgrid[0:h, 0:w] / s
        x0, y0 = np.floor(xx).astype(int), np.floor(yy).astype(int)
        fx, fy = xx - x0, yy - y0
        fx, fy = fx * fx * (3 - 2 * fx), fy * fy * (3 - 2 * fy)
        v = (g[y0, x0] * (1 - fx) * (1 - fy) + g[y0, x0 + 1] * fx * (1 - fy)
             + g[y0 + 1, x0] * (1 - fx) * fy + g[y0 + 1, x0 + 1] * fx * fy)
        out += amp * v
        tot += amp
        amp *= 0.5
    return out / tot


def upscale(arr, k):
    img = Image.fromarray(np.clip(arr, 0, 255).astype(np.uint8))
    return img.resize((img.width * k, img.height * k), Image.NEAREST)


def font(size, bold=False, serif=False, mono=False):
    if mono:
        path = "/usr/share/fonts/truetype/dejavu/DejaVuSansMono.ttf"
    elif serif:
        path = "/usr/share/fonts/truetype/dejavu/DejaVuSerif-Bold.ttf" if bold else "/usr/share/fonts/truetype/dejavu/DejaVuSerif.ttf"
    else:
        path = "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf" if bold else "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf"
    try:
        return ImageFont.truetype(path, size)
    except OSError:
        return ImageFont.load_default()


def wallpaper(w, h, seed=3, dark=0.0, ramp="wall", motif=True):
    """Old damask wallpaper (Doctor Nowhere's corners), as an RGB array."""
    R = RAMPS[ramp]
    n = noise2(w, h, 18, seed, 3)
    stain = noise2(w, h, 9, seed + 7, 2)
    yy, xx = np.mgrid[0:h, 0:w]
    v = 0.62 + 0.18 * (n - 0.5) - 0.25 * np.clip(stain - 0.62, 0, 1) * 3
    if motif:
        cx = (xx % 12) - 6
        cy = ((yy + (xx // 12) % 2 * 9) % 18) - 9
        petal = ((cx / 3.0) ** 2 + (cy / 5.5) ** 2 <= 1) & ~((cx / 1.6) ** 2 + (cy / 3.6) ** 2 <= 1)
        dot = (cx == 0) & (np.abs(cy) <= 1)
        v = v - 0.16 * petal - 0.12 * dot
        stripe = (xx % 24 == 0)
        v = v - 0.06 * stripe
    v = v - dark
    b = BAYER4[(yy % 4), (xx % 4)]
    idx = np.clip(np.floor(v * (len(R) - 1) + 0.5 + b), 0, len(R) - 1).astype(int)
    return R[idx]
