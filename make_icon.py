#!/usr/bin/env python3
"""生成 ModemTest 应用图标：深色圆角方块 + 信号柱 + 拨号点，纯 Python 画"""
import os, struct, zlib

def make_png(w, h, pixel_fn):
    """pixel_fn(x, y) -> (r,g,b,a)，返回 PNG bytes"""
    raw = bytearray()
    for y in range(h):
        raw.append(0)  # filter none
        for x in range(w):
            r, g, b, a = pixel_fn(x, y, w, h)
            raw += bytes((r, g, b, a))
    def chunk(tag, data):
        c = tag + data
        return struct.pack(">I", len(data)) + c + struct.pack(">I", zlib.crc32(c) & 0xFFFFFFFF)
    png = b"\x89PNG\r\n\x1a\n"
    png += chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 6, 0, 0, 0))
    png += chunk(b"IDAT", zlib.compress(bytes(raw), 9))
    png += chunk(b"IEND", b"")
    return png

def icon_pixel(x, y, w, h):
    # 归一化坐标 0..1
    u = (x + 0.5) / w
    v = (y + 0.5) / h
    # 背景：左上亮右下暗的渐变（深蓝灰）
    t = (u + v) / 2
    br = int(0x14 + (0x1E - 0x14) * t)
    bg = int(0x18 + (0x2A - 0x18) * t)
    bb = int(0x20 + (0x36 - 0x20) * t)

    # 圆角矩形（自适应圆角半径）
    radius = 0.22
    cx = min(max(u, radius), 1 - radius)
    cy = min(max(v, radius), 1 - radius)
    d = ((u - cx) ** 2 + (v - cy) ** 2) ** 0.5
    if d > radius:
        return (0, 0, 0, 0)

    # 信号柱：3 根递增高度的柱子，居中偏下
    base_y = 0.72
    cols = [0.34, 0.50, 0.66]
    heights = [0.16, 0.28, 0.40]
    acc = (0x2F, 0x81, 0xF7)  # 蓝
    for i, (cu, hh) in enumerate(zip(cols, heights)):
        bw = 0.075
        if abs(u - cu) < bw and (base_y - hh) < v < base_y:
            # 柱内渐变：顶部亮
            tt = (base_y - v) / hh
            r = int(acc[0] + (0x7A - acc[0]) * tt)
            g = int(acc[1] + (0xC8 - acc[1]) * tt)
            b = int(acc[2] + (0xFF - acc[2]) * tt)
            return (r, g, b, 255)

    # 柱底横线
    if base_y < v < base_y + 0.022 and 0.30 < u < 0.70:
        return (0x8B, 0x98, 0xA5, 255)

    # 拨号点：柱子上方一个小圆（表示通话）
    dx, dy, dr = 0.50, 0.20, 0.055
    if ((u - dx) ** 2 + (v - dy) ** 2) ** 0.5 < dr:
        return (0x23, 0x86, 0x36, 255)  # 绿

    # 波纹：从拨号点向外的两道弧
    for rr in (0.085, 0.12):
        dist = ((u - dx) ** 2 + (v - dy) ** 2) ** 0.5
        if abs(dist - rr) < 0.012 and v < dy:
            a = 150 if rr < 0.1 else 90
            return (0x2F, 0x81, 0xF7, a)

    return (br, bg, bb, 255)

SIZES = {
    "mipmap-mdpi": 48, "mipmap-hdpi": 72, "mipmap-xhdpi": 96,
    "mipmap-xxhdpi": 144, "mipmap-xxxhdpi": 192,
}
base = os.path.join(os.path.dirname(os.path.abspath(__file__)), "res")
for d, sz in SIZES.items():
    dpath = os.path.join(base, d)
    os.makedirs(dpath, exist_ok=True)
    data = make_png(sz, sz, icon_pixel)
    with open(os.path.join(dpath, "ic_launcher.png"), "wb") as f:
        f.write(data)
    print("%-16s %3dx%-3d  %d bytes" % (d, sz, sz, len(data)))
print("OK")
