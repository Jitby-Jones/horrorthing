#!/usr/bin/env python3
"""Draws every clip as stick figures, frame by frame, side and front: a quick check of the motion
without Blender's renderer.  python tools/creature/clip_sheet.py [out.png]"""
import os
import sys

import numpy as np
from PIL import Image, ImageDraw

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import poses  # noqa: E402
from build_creature import skeleton  # noqa: E402


def main(out):
    joints = skeleton()
    clips = poses.clips(joints)
    cell = 150
    scale = 40.0
    rows = []
    for name, (fps, loop, frames) in clips.items():
        row = Image.new("RGB", (cell * 2 * max(len(f) for _, _, f in clips.values()) + 120, cell), (24, 22, 26))
        d = ImageDraw.Draw(row)
        d.text((4, 4), name, fill=(230, 220, 200))
        for i, pose in enumerate(frames):
            mats = poses.joint_transforms(joints, pose)
            pos = {j.name: (mats[j.name] @ np.append(j.pos, 1.0))[:3] for j in joints}
            for v, (ax, sign) in enumerate(((1, -1.0), (0, 1.0))):  # side (y), front (x)
                ox = 120 + (i * 2 + v) * cell + cell // 2
                oy = cell - 10
                for j in joints:
                    if j.parent is None:
                        continue
                    a, b = pos[j.parent], pos[j.name]
                    col = (200, 60, 60) if j.name.endswith("_L") else (90, 140, 220) if j.name.endswith("_R") else (220, 220, 220)
                    d.line([(ox + sign * a[ax] * scale, oy - a[2] * scale), (ox + sign * b[ax] * scale, oy - b[2] * scale)],
                           fill=col, width=1)
                d.line([(ox - cell // 2 + 4, oy), (ox + cell // 2 - 4, oy)], fill=(70, 70, 70))
        rows.append(row)
    sheet = Image.new("RGB", (max(r.width for r in rows), cell * len(rows)), (24, 22, 26))
    for i, r in enumerate(rows):
        sheet.paste(r, (0, i * cell))
    sheet.save(out)
    print("wrote", out)


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else "clips.png")
