#!/usr/bin/env python3
"""
WallDock 图标生成器（纯 numpy，无第三方绘图库）

设计：深空底色 + 冷紫/青双光晕 + 倾斜的液态玻璃胶囊（内含 4 个图标点），
与 App 内 Dock 的材质语言一致。

用法：python tools/gen_icon.py
产物：
  res 下 adaptive 前后景 + legacy 各密度位图
  build/icon-preview/*.png 供人工检查
"""
import os
import struct
import zlib
import numpy as np

DP = 108.0          # adaptive icon 画布 108dp
S = 1728            # 母版像素（16 px/dp）
K = S / DP
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RES = os.path.join(ROOT, "app", "src", "main", "res")
PREVIEW = os.path.join(ROOT, "build", "icon-preview")

DENSITIES = {"mdpi": 1.0, "hdpi": 1.5, "xhdpi": 2.0, "xxhdpi": 3.0, "xxxhdpi": 4.0}


# ---------------------------------------------------------------- 基础工具
def png_write(path, rgba):
    """rgba: (H,W,4) uint8"""
    h, w, _ = rgba.shape
    raw = b"".join(b"\x00" + rgba[y].tobytes() for y in range(h))

    def chunk(tag, data):
        c = struct.pack(">I", len(data)) + tag + data
        return c + struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF)

    png = b"\x89PNG\r\n\x1a\n"
    png += chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 6, 0, 0, 0))
    png += chunk(b"IDAT", zlib.compress(raw, 9))
    png += chunk(b"IEND", b"")
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "wb") as f:
        f.write(png)


def box_blur(a, r):
    """三次盒式模糊近似高斯，O(1)/像素。a: (H,W) float32"""
    if r < 1:
        return a
    out = a
    for _ in range(3):
        pad = np.pad(out, ((0, 0), (r + 1, r)), mode="edge")
        cs = np.cumsum(pad, axis=1, dtype=np.float32)
        out = (cs[:, 2 * r + 1:] - cs[:, :-(2 * r + 1)]) / (2 * r + 1)
        pad = np.pad(out, ((r + 1, r), (0, 0)), mode="edge")
        cs = np.cumsum(pad, axis=0, dtype=np.float32)
        out = (cs[2 * r + 1:, :] - cs[:-(2 * r + 1), :]) / (2 * r + 1)
    return out.astype(np.float32)


def sdf_rrect(x, y, cx, cy, w, h, r, ang_deg=0.0):
    """像素坐标下的圆角矩形 SDF"""
    a = np.deg2rad(ang_deg)
    dx, dy = x - cx * K, y - cy * K
    xr = dx * np.cos(a) + dy * np.sin(a)
    yr = -dx * np.sin(a) + dy * np.cos(a)
    hw, hh = w * K / 2, h * K / 2
    rr = r * K
    qx, qy = np.abs(xr) - (hw - rr), np.abs(yr) - (hh - rr)
    ax, ay = np.maximum(qx, 0), np.maximum(qy, 0)
    return np.sqrt(ax * ax + ay * ay) + np.minimum(np.maximum(qx, qy), 0) - rr


def cov(sdf):
    """SDF -> 覆盖率（1px 抗锯齿）"""
    return np.clip(0.5 - sdf, 0.0, 1.0).astype(np.float32)


def over(rgb, alpha, color, a):
    """预乘 over 合成：rgb 与 alpha 同步累计"""
    a = np.clip(a, 0.0, 1.0).astype(np.float32)
    a3 = a[..., None]
    rgb *= 1.0 - a3
    rgb += np.asarray(color, dtype=np.float32) * a3
    alpha[...] = a + alpha * (1.0 - a)


def radial(x, y, cx, cy, sigma, strength):
    d2 = (x - cx * K) ** 2 + (y - cy * K) ** 2
    return (np.exp(-d2 / (2 * (sigma * K) ** 2)) * strength).astype(np.float32)


# ---------------------------------------------------------------- 画布
yy, xx = np.mgrid[0:S, 0:S].astype(np.float32)


# ---------------------------------------------------------------- 背景层
def render_background():
    t = (xx + yy) / (2 * S)                      # 0..1 对角
    top = np.array([0x0B, 0x0E, 0x18], dtype=np.float32)
    mid = np.array([0x14, 0x1A, 0x2E], dtype=np.float32)
    bot = np.array([0x1B, 0x17, 0x33], dtype=np.float32)

    w1 = np.clip(1 - t * 2, 0, 1)[..., None]
    w3 = np.clip(t * 2 - 1, 0, 1)[..., None]
    w2 = 1 - w1 - w3
    bg = top * w1 + mid * w2 + bot * w3

    # 冷紫主光晕（左上）+ 青色副光晕（右下）
    gl1 = radial(xx, yy, 26, 18, 40, 0.62)[..., None]
    gl2 = radial(xx, yy, 95, 99, 34, 0.34)[..., None]
    bg += np.array([0x64, 0x55, 0xFF], dtype=np.float32) * gl1
    bg += np.array([0x2F, 0xC8, 0xFF], dtype=np.float32) * gl2 * 0.55

    # 细斜向丝光
    band = np.exp(-(((xx - yy) / S - 0.06) ** 2) / (2 * 0.09 ** 2)) * 0.055
    bg += (np.array([0x9A, 0xAE, 0xFF], dtype=np.float32) * band[..., None])

    # 暗角
    r = np.sqrt((xx - S / 2) ** 2 + (yy - S / 2) ** 2) / (S / 2)
    vig = np.clip((r - 0.62) / 0.55, 0, 1)[..., None] * 0.55
    bg *= 1.0 - vig
    return np.clip(bg, 0, 255)


# ---------------------------------------------------------------- 前景层
PILL = dict(cx=54.0, cy=59.0, w=57.0, h=21.0, r=10.5, ang_deg=-11.0)


def render_foreground():
    """返回 (rgb_premult, alpha) float32，均未归一"""
    fg = np.zeros((S, S, 3), dtype=np.float32)
    fa = np.zeros((S, S), dtype=np.float32)

    pill_sdf = sdf_rrect(xx, yy, **PILL)
    pill = cov(pill_sdf)

    # 1) 投影：胶囊下移 + 模糊
    sh = cov(sdf_rrect(xx, yy, PILL["cx"], PILL["cy"] + 3.2, PILL["w"], PILL["h"],
                       PILL["r"], PILL["ang_deg"]))
    sh = box_blur(sh, int(4.0 * K)) * 0.62
    over(fg, fa, (6, 8, 16), sh)

    # 2) 玻璃本体：上亮下暗的白色渐变 + 一点冷色倾向
    a = np.deg2rad(PILL["ang_deg"])
    dx, dy = xx - PILL["cx"] * K, yy - PILL["cy"] * K
    yr = (-dx * np.sin(a) + dy * np.cos(a)) / (PILL["h"] * K) + 0.5   # 0 顶 1 底
    yr = np.clip(yr, 0, 1)
    body_a = (0.52 * (1 - yr) ** 1.3 + 0.075)
    body_rgb = np.stack([
        255 - 6 * yr,
        255 - 2 * yr,
        np.full_like(yr, 255.0),
    ], axis=-1)
    over(fg, fa, body_rgb, pill * body_a)

    # 3) 斜向高光带（裁进胶囊内）
    streak_axis = ((xx - yy * 0.55) / K - 40.0)
    streak = np.exp(-(streak_axis ** 2) / (2 * 11.0 ** 2)) * 0.32
    streak = box_blur(streak.astype(np.float32), int(0.9 * K)) * pill
    over(fg, fa, (255, 255, 255), streak)

    # 4) 顶部内高光细线
    top_line = np.exp(-((yr - 0.045) ** 2) / (2 * 0.05 ** 2)) * pill * 0.5
    top_line = box_blur(top_line.astype(np.float32), int(0.45 * K))
    over(fg, fa, (255, 255, 255), top_line)

    # 5) 描边
    edge = cov(np.abs(pill_sdf) - 0.62 * K)
    over(fg, fa, (255, 255, 255), edge * 0.62)

    # 6) 四个图标点
    for i, ox in enumerate((-19.0, -6.4, 6.4, 19.0)):
        cx = PILL["cx"] + ox * np.cos(np.deg2rad(PILL["ang_deg"]))
        cy = PILL["cy"] + ox * np.sin(np.deg2rad(PILL["ang_deg"]))
        dot = cov(sdf_rrect(xx, yy, cx, cy, 8.0, 8.0, 2.6, PILL["ang_deg"]))
        dot = box_blur(dot, int(0.25 * K))
        over(fg, fa, (255, 255, 255), dot * (0.95 if i < 3 else 0.7))

    return fg, fa


# ---------------------------------------------------------------- 组装
def composite(bg, fg, fa):
    out = bg.copy()
    a = fa[..., None]
    out = out * (1 - a) + fg
    return np.clip(out, 0, 255)


def downsample(img, size):
    """区域平均降采样（任意比例，边缘补边对齐）"""
    img = np.ascontiguousarray(img, dtype=np.float32)
    src = img.shape[0]
    n = max(1, int(np.ceil(src / size)))
    total = n * size - src
    if total > 0:
        a = total // 2
        pad = ((a, total - a), (a, total - a)) + ((0, 0),) * (img.ndim - 2)
        img = np.pad(img, pad, mode="edge")
    if img.ndim == 2:
        return img.reshape(size, n, size, n).mean(axis=(1, 3))
    return img.reshape(size, n, size, n, img.shape[2]).mean(axis=(1, 3))


def to_rgba(rgb, alpha=None):
    rgb8 = np.clip(rgb, 0, 255).astype(np.uint8)
    if alpha is None:
        a8 = np.full(rgb8.shape[:2] + (1,), 255, dtype=np.uint8)
    else:
        a8 = np.clip(alpha, 0, 255).astype(np.uint8)[..., None]
    return np.concatenate([rgb8, a8], axis=-1)


def premult_to_rgba(fg, fa):
    """预乘色 + 0..1 alpha -> straight alpha RGBA(0..255)"""
    a = np.clip(fa, 0.0, 1.0)
    safe = np.where(a > 0.002, a, 1.0)[..., None]
    rgb = fg / safe
    return to_rgba(np.clip(rgb, 0, 255), a * 255.0)


def apply_mask_square(rgba, radius_frac=0.22):
    s = rgba.shape[0]
    yy2, xx2 = np.mgrid[0:s, 0:s].astype(np.float32)
    r = s * radius_frac
    qx = np.abs(xx2 - s / 2 + 0.5) - (s / 2 - r)
    qy = np.abs(yy2 - s / 2 + 0.5) - (s / 2 - r)
    d = np.sqrt(np.maximum(qx, 0) ** 2 + np.maximum(qy, 0) ** 2) + \
        np.minimum(np.maximum(qx, qy), 0) - r
    m = np.clip(0.5 - d, 0, 1)[..., None]
    out = rgba.copy()
    out[..., 3] = out[..., 3] * m[..., 0]
    return out


def apply_mask_circle(rgba):
    s = rgba.shape[0]
    yy2, xx2 = np.mgrid[0:s, 0:s].astype(np.float32)
    d = np.sqrt((xx2 - s / 2 + 0.5) ** 2 + (yy2 - s / 2 + 0.5) ** 2) - s / 2
    m = np.clip(0.5 - d, 0, 1)[..., None]
    out = rgba.copy()
    out[..., 3] = out[..., 3] * m[..., 0]
    return out


def crop_center(img, frac):
    s = img.shape[0]
    n = int(round(s * frac))
    o = (s - n) // 2
    return img[o:o + n, o:o + n]


def main():
    os.makedirs(PREVIEW, exist_ok=True)
    bg = render_background()
    fg, fa = render_foreground()
    full = composite(bg, fg, fa)
    fore_rgba = premult_to_rgba(fg, fa)

    # ---- 母版预览（人工检查）
    png_write(os.path.join(PREVIEW, "icon-1024.png"), to_rgba(downsample(full, 1024)))
    png_write(os.path.join(PREVIEW, "icon-192.png"), to_rgba(downsample(full, 192)))
    png_write(os.path.join(PREVIEW, "icon-48.png"), to_rgba(downsample(full, 48)))
    small = downsample(full, 48)
    png_write(os.path.join(PREVIEW, "icon-48-zoom.png"),
              to_rgba(np.repeat(np.repeat(small, 6, axis=0), 6, axis=1)))

    # ---- adaptive 前后景（全出血 108dp / 中心 72dp 安全区）
    for name, mul in DENSITIES.items():
        px = int(round(DP * mul))
        d = os.path.join(RES, "mipmap-" + name)
        png_write(os.path.join(d, "ic_launcher_background.png"),
                  to_rgba(downsample(bg, px)))
        png_write(os.path.join(d, "ic_launcher_foreground.png"),
                  to_rgba(downsample(fore_rgba[..., :3], px),
                          downsample(fore_rgba[..., 3].astype(np.float32), px)))

        # legacy 方形：取中心 72dp 区域（与系统对 adaptive 的呈现一致）
        sq = crop_center(full, 72 / 108)
        sq = downsample(sq, int(round(48 * mul)))
        png_write(os.path.join(d, "ic_launcher.png"),
                  apply_mask_square(to_rgba(sq), 0.23))

        rnd = crop_center(full, 74 / 108)
        rnd = downsample(rnd, int(round(48 * mul)))
        png_write(os.path.join(d, "ic_launcher_round.png"), apply_mask_circle(to_rgba(rnd)))

    print("icon generated ->", RES)


if __name__ == "__main__":
    main()
