"""Block and item textures for the expansion, drawn pixel by pixel at 16x16 (the game's own scale).

Run: python3 tools/textures/draw_textures.py
Writes into common/src/main/resources/assets/thewake/textures/{block,item}/.
"""
import os

import numpy as np
from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(os.path.dirname(HERE))
OUT = os.path.join(ROOT, "common/src/main/resources/assets/thewake/textures")
RNG = np.random.default_rng(1993)


def canvas(color=(0, 0, 0, 0)):
    img = np.zeros((16, 16, 4), dtype=np.float64)
    img[:, :] = color
    return img


def save(img, kind, name):
    path = os.path.join(OUT, kind, name + ".png")
    os.makedirs(os.path.dirname(path), exist_ok=True)
    Image.fromarray(np.clip(img, 0, 255).astype(np.uint8), "RGBA").save(path)


def noise(img, amount, mask=None):
    n = RNG.normal(0.0, amount, (16, 16, 1))
    if mask is not None:
        n *= mask[..., None]
    img[..., :3] += n
    return img


def px(img, x, y, c):
    if 0 <= x < 16 and 0 <= y < 16:
        img[y, x] = c if len(c) == 4 else (*c, 255)


def rect(img, x0, y0, x1, y1, c):
    for y in range(y0, y1 + 1):
        for x in range(x0, x1 + 1):
            px(img, x, y, c)


# ---- blocks -------------------------------------------------------------------------------

def wallpaper(stained):
    img = canvas((206, 190, 142, 255))
    # Faint stripes, a small figure between them: an old pattern somebody chose once.
    for x in range(16):
        if x % 4 == 0:
            img[:, x, :3] -= 16
        if x % 4 == 2:
            img[:, x, :3] += 6
    for (cx, cy) in ((2, 3), (2, 11), (6, 7), (6, 15), (10, 3), (10, 11), (14, 7), (14, 15)):
        for dx, dy in ((0, -1), (-1, 0), (1, 0), (0, 1)):
            px(img, cx + dx, (cy + dy) % 16, (176, 156, 108))
        px(img, cx, cy % 16, (150, 128, 84))
    noise(img, 4.0)
    if stained:
        # Water came through from above and dried, more than once.
        yy, xx = np.mgrid[0:16, 0:16]
        for (sx, sy, r, k) in ((5, 2, 6.5, 0.55), (11, 9, 5.0, 0.45), (3, 13, 3.5, 0.35)):
            d = np.sqrt((xx - sx) ** 2 + ((yy - sy) * 0.7) ** 2)
            ring = np.clip(1.0 - np.abs(d - r) / 1.2, 0, 1) * 0.9 + np.clip(1.0 - d / r, 0, 1) * 0.35
            img[..., 0] -= 60 * k * ring
            img[..., 1] -= 66 * k * ring
            img[..., 2] -= 70 * k * ring
        # The seam, lifting.
        for y in range(16):
            px(img, 8, y, (150, 132, 90) if y % 3 else (120, 104, 70))
        for y in range(0, 5):
            px(img, 9, y, (232, 220, 182))
    return img


def ceiling_tile():
    img = canvas((214, 210, 196, 255))
    noise(img, 3.0)
    for _ in range(26):
        x, y = RNG.integers(1, 15, 2)
        px(img, int(x), int(y), (176, 170, 156))
    for i in range(16):
        px(img, i, 0, (190, 186, 172))
        px(img, 0, i, (190, 186, 172))
        px(img, i, 15, (168, 164, 150))
        px(img, 15, i, (168, 164, 150))
    return img


def plywood():
    img = canvas((214, 182, 132, 255))
    # Grain running along it, the layered edge at one side.
    for y in range(16):
        wave = int(round(np.sin(y * 0.9) * 1.5))
        for x in range(16):
            g = np.sin((x + wave) * 1.3 + y * 0.15) * 8
            img[y, x, :3] += (g, g * 0.9, g * 0.7)
    for y in (4, 11):
        for x in range(16):
            px(img, x, y, (186, 152, 104))
    noise(img, 3.0)
    # A stencilled mark: a number, as on the back of a flat.
    for x, y in ((11, 13), (12, 13), (13, 13), (13, 14), (12, 14), (11, 15), (12, 15), (13, 15)):
        px(img, x, y, (60, 52, 46))
    return img


def tape_shelf_front():
    img = canvas((44, 36, 30, 255))
    # Two rows of spines between three boards.
    for y in (0, 7, 15):
        rect(img, 0, y, 15, y, (70, 58, 46))
    for row, (y0, y1) in enumerate(((1, 6), (8, 14))):
        x = 1
        while x < 15:
            w = int(RNG.integers(1, 3))
            body = (18, 18, 20) if RNG.random() < 0.8 else ((60, 22, 22) if RNG.random() < 0.5 else (28, 34, 60))
            rect(img, x, y0, min(14, x + w - 1), y1, body)
            # The label, handwritten once.
            ly = y0 + int(RNG.integers(1, 3))
            label = (226, 222, 206) if RNG.random() < 0.85 else (232, 200, 120)
            rect(img, x, ly, min(14, x + w - 1), ly + 2, label)
            if RNG.random() < 0.5:
                px(img, x, ly + 1, (90, 90, 96))
            x += w + (1 if RNG.random() < 0.3 else 0)
        rect(img, 0, y0, 0, y1, (60, 50, 40))
        rect(img, 15, y0, 15, y1, (60, 50, 40))
    # One gap, near the end of the bottom row: one is missing.
    rect(img, 11, 8, 12, 14, (12, 10, 9))
    noise(img, 2.0)
    return img


def tape_shelf_side():
    img = canvas((58, 48, 38, 255))
    for y in range(16):
        for x in range(16):
            img[y, x, :3] += np.sin(x * 2.1 + y * 0.4) * 4
    noise(img, 3.0)
    return img


def tape_shelf_top():
    img = canvas((66, 54, 42, 255))
    noise(img, 4.0)
    return img


def metal(base=(130, 132, 136)):
    img = canvas((*base, 255))
    for y in range(16):
        img[y, :, :3] += np.sin(y * 3.1) * 3
    noise(img, 2.5)
    for i in range(16):
        px(img, i, 0, (160, 162, 166))
        px(img, i, 15, (92, 94, 98))
    for (x, y) in ((1, 1), (14, 1), (1, 14), (14, 14)):
        px(img, x, y, (80, 80, 84))
    return img


def returns_slot_front():
    img = metal()
    rect(img, 3, 8, 12, 10, (14, 14, 16))
    rect(img, 3, 11, 12, 11, (170, 172, 176))
    # RETURNS, as it would be at this size: three letters stamped above.
    letters = {"R": ["##.", "#.#", "##.", "#.#"], "E": ["###", "#..", "##.", "###"], "T": ["###", ".#.", ".#.", ".#."]}
    x = 3
    for ch in "RET":
        for dy, row in enumerate(letters[ch]):
            for dx, c in enumerate(row):
                if c == "#":
                    px(img, x + dx, 2 + dy, (40, 40, 44))
        x += 4
    return img


def fluorescent(on):
    img = canvas((198, 196, 186, 255))
    for i in range(16):
        px(img, i, 0, (150, 150, 146))
        px(img, 0, i, (150, 150, 146))
        px(img, i, 15, (150, 150, 146))
        px(img, 15, i, (150, 150, 146))
    for y in range(2, 14):
        for x in range(2, 14):
            prism = ((x + y) % 2) * 6
            if on:
                c = (248 - prism, 250 - prism, 240 - prism)
            else:
                c = (128 - prism, 130 - prism, 126 - prism)
            px(img, x, y, c)
    if not on:
        # A dead fly in the corner of the diffuser.
        px(img, 4, 11, (40, 38, 34))
    return img


def vcr_front(tape):
    img = canvas((24, 24, 26, 255))
    # The face is only the bottom five rows in the model (uv 11..16); draw there.
    rect(img, 1, 11, 14, 15, (30, 30, 33))
    rect(img, 2, 12, 8, 12, (6, 6, 7))  # the slot
    if tape:
        rect(img, 3, 12, 7, 12, (52, 52, 56))
    # The clock, blinking 12:00 when it has never been set; PLAY when it has a tape.
    green = (110, 230, 160)
    if tape:
        for x, y in ((10, 13), (10, 14), (11, 13), (11, 14), (12, 13)):
            px(img, x, y, green)
        px(img, 10, 12, green)
    else:
        for x, y in ((9, 13), (10, 13), (12, 13), (13, 13), (9, 14), (12, 14), (11, 14)):
            px(img, x, y, green)
    for x in (3, 5, 7):
        px(img, x, 14, (120, 120, 126))
    rect(img, 1, 15, 14, 15, (40, 40, 44))
    return img


def vcr_side():
    img = canvas((28, 28, 30, 255))
    for y in range(11, 16):
        for x in range(16):
            if y == 12 and x % 2 == 0:
                px(img, x, y, (14, 14, 15))
    noise(img, 1.5)
    return img


def vcr_top():
    img = canvas((30, 30, 32, 255))
    for y in range(3, 13, 2):
        rect(img, 3, y, 12, y, (16, 16, 17))
    noise(img, 1.5)
    return img


# ---- items --------------------------------------------------------------------------------

def old_camera():
    img = canvas()
    body = (54, 46, 40)
    rect(img, 2, 6, 13, 13, body)
    rect(img, 2, 6, 13, 6, (84, 72, 62))
    rect(img, 2, 13, 13, 13, (30, 26, 22))
    # Leatherette.
    for y in range(7, 13):
        for x in range(3, 13):
            if (x + y) % 3 == 0:
                px(img, x, y, (44, 38, 33))
    # The lens.
    for x, y in ((7, 8), (8, 8), (9, 8), (6, 9), (10, 9), (6, 10), (10, 10), (7, 11), (8, 11), (9, 11)):
        px(img, x, y, (150, 150, 156))
    rect(img, 7, 9, 9, 10, (16, 18, 26))
    px(img, 7, 9, (120, 140, 190))
    # The flash, a silver dish on a bracket.
    rect(img, 9, 1, 14, 4, (196, 198, 204))
    rect(img, 10, 2, 13, 3, (240, 240, 236))
    rect(img, 11, 5, 11, 5, (110, 110, 116))
    # A button.
    px(img, 4, 5, (150, 40, 34))
    return img


def flash_bulb():
    img = canvas()
    glass = (226, 236, 240, 200)
    for y in range(2, 10):
        w = [2, 3, 4, 4, 4, 4, 3, 2][y - 2]
        for x in range(8 - w, 8 + w):
            px(img, x, y, glass)
    px(img, 6, 4, (255, 255, 255))
    px(img, 6, 5, (255, 255, 255))
    rect(img, 7, 4, 8, 8, (200, 196, 170, 230))  # the filament wire
    rect(img, 6, 10, 9, 13, (176, 176, 180))
    for y in (11, 13):
        rect(img, 6, y, 9, y, (128, 128, 134))
    rect(img, 7, 14, 8, 14, (80, 70, 50))
    return img


def pin():
    img = canvas()
    # A long dressmaker's pin, a pale bead for a head, and rust where it was in something.
    for i in range(11):
        x, y = 3 + i, 12 - i
        c = (190, 192, 198) if i < 7 else (150, 92, 60)
        px(img, x, y, c)
    for x, y in ((2, 13), (3, 13), (2, 14), (3, 14), (1, 13), (2, 12)):
        px(img, x, y, (232, 226, 214))
    px(img, 2, 13, (255, 252, 244))
    px(img, 13, 2, (120, 40, 30))
    px(img, 14, 1, (90, 30, 24))
    return img


def tape():
    img = canvas()
    rect(img, 1, 4, 14, 12, (20, 20, 22))
    rect(img, 1, 4, 14, 4, (46, 46, 50))
    # The label, the handwriting on it.
    rect(img, 3, 5, 12, 7, (226, 220, 200))
    for x in (4, 5, 7, 8, 9, 11):
        px(img, x, 6, (60, 60, 120))
    # The window and the reels.
    rect(img, 4, 9, 11, 11, (60, 58, 52))
    rect(img, 5, 9, 6, 10, (110, 90, 70))
    rect(img, 9, 9, 10, 10, (40, 34, 28))
    rect(img, 1, 12, 14, 12, (10, 10, 11))
    return img


def card():
    img = canvas()
    paper = (214, 204, 182)
    rect(img, 4, 1, 11, 14, paper)
    for y in (4, 7, 10, 13):
        rect(img, 4, y, 11, y, (190, 196, 210))
    rect(img, 4, 2, 11, 2, (196, 120, 120))
    # Its eyes were here.
    rect(img, 5, 5, 6, 6, (0, 0, 0, 0))
    rect(img, 9, 5, 10, 6, (0, 0, 0, 0))
    px(img, 5, 7, (120, 70, 60))
    # The jaw cut, and a stain.
    rect(img, 6, 10, 6, 13, (140, 120, 100))
    rect(img, 9, 10, 9, 13, (140, 120, 100))
    for (x, y) in ((4, 1), (11, 1), (4, 14), (11, 14)):
        px(img, x, y, (120, 70, 50))
    noise(img, 3.0, mask=(img[..., 3] > 0).astype(float))
    return img


def main():
    save(wallpaper(False), "block", "wallpaper")
    save(wallpaper(True), "block", "stained_wallpaper")
    save(ceiling_tile(), "block", "ceiling_tile")
    save(plywood(), "block", "plywood")
    save(tape_shelf_front(), "block", "tape_shelf_front")
    save(tape_shelf_side(), "block", "tape_shelf_side")
    save(tape_shelf_top(), "block", "tape_shelf_top")
    save(returns_slot_front(), "block", "returns_slot_front")
    save(metal(), "block", "returns_slot_side")
    save(fluorescent(True), "block", "fluorescent_light_on")
    save(fluorescent(False), "block", "fluorescent_light_off")
    save(vcr_front(False), "block", "vcr_front")
    save(vcr_front(True), "block", "vcr_front_tape")
    save(vcr_side(), "block", "vcr_side")
    save(vcr_top(), "block", "vcr_top")
    save(old_camera(), "item", "old_camera")
    save(flash_bulb(), "item", "flash_bulb")
    save(pin(), "item", "pin")
    save(tape(), "item", "tape")
    save(card(), "item", "card")
    print("textures written to", OUT)


if __name__ == "__main__":
    main()
