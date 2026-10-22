#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""从 ic_launcher.svg 生成各密度 PNG 图标 + 悬浮球用图（需要 cairosvg / Pillow）。
矢量图标（res/drawable/ic_launcher_vec.xml、ic_launcher_fg_vec.xml）是手工从同一个 SVG 转的，二者保持一致。
用法：python3 tools/make_icons.py
"""
import os
import cairosvg
from PIL import Image, ImageDraw

SVG = "ic_launcher.svg"
SS = 4

def rounded(size, radius_ratio=0.23):
    cairosvg.svg2png(url=SVG, write_to="/tmp/raw_%d.png" % size,
                     output_width=size * SS, output_height=size * SS)
    im = Image.open("/tmp/raw_%d.png" % size).convert("RGBA")
    S = size * SS
    mask = Image.new("L", (S, S), 0)
    ImageDraw.Draw(mask).rounded_rectangle([0, 0, S - 1, S - 1], radius=int(S * radius_ratio), fill=255)
    out = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    out.paste(im, (0, 0), mask)
    return out.resize((size, size), Image.LANCZOS)

def main():
    for name, px in [("mdpi", 48), ("hdpi", 72), ("xhdpi", 96), ("xxhdpi", 144), ("xxxhdpi", 192)]:
        d = "app/res/mipmap-%s" % name
        os.makedirs(d, exist_ok=True)
        rounded(px).save(os.path.join(d, "ic_launcher.png"))
    print("icons rebuilt from", SVG)

if __name__ == "__main__":
    main()
