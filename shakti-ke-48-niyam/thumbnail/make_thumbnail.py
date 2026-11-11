#!/usr/bin/env python3
"""Shakti ke 48 Niyam — YouTube thumbnail renderer (proper Devanagari shaping)."""
import uharfbuzz as hb, freetype, numpy as np
from PIL import Image, ImageDraw, ImageFilter, ImageEnhance

FONT = "NotoSansDevanagari.ttf"
SRC, OUT_PNG, OUT_JPG = "cover.png", "youtube-thumbnail.png", "youtube-thumbnail.jpg"
W, H = 1280, 720

blob = hb.Blob.from_file_path(FONT)
face_hb = hb.Face(blob)
font_hb = hb.Font(face_hb)
try:
    font_hb.set_variations({"wght": 700, "wdth": 100})
except Exception:
    pass

ft_face = freetype.Face(FONT)
try:  # variable-font weight, if the API is available
    ft_face.set_var_design_coords([700.0, 100.0])
except Exception:
    pass

def render_text(text, px, color, stroke=0, stroke_color=(0, 0, 0, 255)):
    """Render shaped Devanagari text to an RGBA image."""
    buf = hb.Buffer()
    buf.add_str(text)
    buf.guess_segment_properties()
    hb.shape(font_hb, buf, {"kern": True, "liga": True})
    infos, pos = buf.glyph_infos, buf.glyph_positions

    scale = px / face_hb.upem
    ft_face.set_pixel_sizes(0, px)
    W_est = int(sum(p.x_advance for p in pos) * scale) + px * 2
    H_est = int(px * 2.0)
    img = Image.new("L", (max(W_est, 10), H_est), 0)
    pen_x, baseline = px, int(px * 1.45)
    for info, p in zip(infos, pos):
        try:
            ft_face.load_glyph(info.codepoint, freetype.FT_LOAD_RENDER | freetype.FT_LOAD_NO_HINTING)
        except Exception:
            pen_x += p.x_advance * scale
            continue
        bm = ft_face.glyph.bitmap
        if bm.width and bm.rows:
            arr = np.array(bm.buffer, dtype=np.uint8).reshape(bm.rows, bm.pitch)[:, : bm.width]
            g = Image.fromarray(arr, mode="L")
            x = int(pen_x + (p.x_offset) * scale + ft_face.glyph.bitmap_left)
            y = int(baseline - (p.y_offset) * scale - ft_face.glyph.bitmap_top)
            img.paste(g, (x, y), g)
        pen_x += p.x_advance * scale

    bbox = img.getbbox()
    img = img.crop(bbox) if bbox else img
    pad = stroke + 4
    out = Image.new("RGBA", (img.width + pad * 2, img.height + pad * 2), (0, 0, 0, 0))
    mask = Image.new("L", out.size, 0)
    mask.paste(img, (pad, pad))
    if stroke:
        dil = mask.filter(ImageFilter.GaussianBlur(stroke * 0.62))
        dil = dil.point(lambda v: 255 if v > 45 else 0)
        layer = Image.new("RGBA", out.size, stroke_color[:3] + (0,))
        layer.putalpha(dil)
        out = Image.alpha_composite(out, layer)
    fill = Image.new("RGBA", out.size, color + (0,))
    fill.putalpha(mask)
    return Image.alpha_composite(out, fill)

# ---------- base ----------
base = Image.open(SRC).convert("RGB")
# cover-crop to 16:9
ar = W / H
w0, h0 = base.size
if w0 / h0 > ar:
    nw = int(h0 * ar); base = base.crop(((w0 - nw) // 2, 0, (w0 + nw) // 2, h0))
else:
    nh = int(w0 / ar); base = base.crop((0, (h0 - nh) // 2, w0, (h0 + nh) // 2))
base = base.resize((W, H), Image.LANCZOS)
base = ImageEnhance.Contrast(base).enhance(1.1)
base = ImageEnhance.Color(base).enhance(1.12)

# top gradient (hides the seam + gives text contrast)
ov = Image.new("L", (1, H), 0)
for y in range(H):
    ov.putpixel((0, y), int(236 * max(0, 1 - y / (H * 0.46)) ** 1.0))
ov = ov.resize((W, H)).filter(ImageFilter.GaussianBlur(70))   # soft edge, no visible seam line
base = Image.composite(Image.new("RGB", (W, H), (8, 4, 6)), base, ov)
# warm vignette on the right for the text block
glow = Image.new("L", (W, H), 0)
ImageDraw.Draw(glow).ellipse((640, 90, 1330, 520), fill=70)
glow = glow.filter(ImageFilter.GaussianBlur(90))
base = Image.composite(Image.new("RGB", (W, H), (10, 5, 8)), base, glow)

import random
random.seed(7)
grain = Image.effect_noise((W, H), 14).convert("L").point(lambda v: int(v * 0.10))
base = Image.composite(base.point(lambda v: 255), base, grain.point(lambda v: 0))
grain_rgb = Image.merge("RGB", (grain, grain, grain))
base = Image.blend(base, Image.blend(base, grain_rgb, 0.35), 0.5)

canvas = base.convert("RGBA")

# ---------- text ----------
GOLD = (255, 205, 66)
WHITE = (255, 250, 244)
RED = (206, 32, 32)

def fit(text, px, max_w, **kw):
    img = render_text(text, px, **kw)
    while img.width > max_w and px > 24:
        px -= 4
        img = render_text(text, px, **kw)
    return img

t1 = fit("शक्ति के", 68, 460, color=WHITE, stroke=6)
t2 = fit("48 नियम", 168, 590, color=GOLD, stroke=10)
badge = render_text("पूरी किताब · 1 घंटे में", 40, (255, 245, 235), stroke=5)

def shadowed(img, dx=10, dy=12, blur=9, alpha=150):
    sh = Image.new("RGBA", (img.width + dx + blur * 2, img.height + dy + blur * 2), (0, 0, 0, 0))
    a = img.split()[3].point(lambda v: min(255, int(v * alpha / 255)))
    sh.paste((0, 0, 0, 255), (dx + blur, dy + blur), a)
    return sh.filter(ImageFilter.GaussianBlur(blur))

def put(img, pos):
    sh = shadowed(img)
    canvas.alpha_composite(sh, (pos[0] - 10, pos[1] - 12))
    canvas.alpha_composite(img, pos)

# right-side text block (over the smoke, clear of the face and the frame edge)
cx = 930
y1 = 52
put(t1, (cx - t1.width // 2, y1))
y2 = y1 + t1.height - 10
put(t2, (cx - t2.width // 2, y2))
print("text sizes", t1.size, t2.size, "block bottom", y2 + t2.height)

# red badge, bottom-left over the dark shirt
pad = 16
bw, bh = badge.width + pad * 2, badge.height + pad * 2
plate = Image.new("RGBA", (bw, bh), (0, 0, 0, 0))
ImageDraw.Draw(plate).rounded_rectangle((0, 0, bw - 1, bh - 1), radius=10, fill=RED + (232,))
canvas.alpha_composite(plate, (44, H - bh - 46))
canvas.alpha_composite(badge, (44 + pad, H - bh - 46 + pad))

canvas.convert("RGB").save(OUT_PNG)
canvas.convert("RGB").save(OUT_JPG, quality=90, optimize=True, subsampling=0)
print("saved", OUT_PNG, OUT_JPG, canvas.size)
