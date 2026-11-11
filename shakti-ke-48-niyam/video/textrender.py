#!/usr/bin/env python3
"""Devanagari-safe text renderer (harfbuzz shaping + freetype raster) for video cards."""
import uharfbuzz as hb, freetype, numpy as np
from PIL import Image, ImageDraw, ImageFilter

FONT = "NotoSansDevanagari.ttf"
_blob = hb.Blob.from_file_path(FONT)
_face_hb = hb.Face(_blob)
_font_hb = hb.Font(_face_hb)
try:
    _font_hb.set_variations({"wght": 700, "wdth": 100})
except Exception:
    pass
_ft = freetype.Face(FONT)
try:
    _ft.set_var_design_coords([700.0, 100.0])
except Exception:
    pass


def render_text(text, px, color, stroke=0, stroke_color=(0, 0, 0)):
    buf = hb.Buffer()
    buf.add_str(text)
    buf.guess_segment_properties()
    hb.shape(_font_hb, buf, {"kern": True, "liga": True})
    scale = px / _face_hb.upem
    _ft.set_pixel_sizes(0, px)
    w = int(sum(p.x_advance for p in buf.glyph_positions) * scale) + px
    img = Image.new("L", (max(w, 8), int(px * 2.0)), 0)
    pen, baseline = px // 2, int(px * 1.45)
    for info, p in zip(buf.glyph_infos, buf.glyph_positions):
        try:
            _ft.load_glyph(info.codepoint, freetype.FT_LOAD_RENDER | freetype.FT_LOAD_NO_HINTING)
        except Exception:
            pen += p.x_advance * scale
            continue
        bm = _ft.glyph.bitmap
        if bm.width and bm.rows:
            a = np.array(bm.buffer, dtype=np.uint8).reshape(bm.rows, bm.pitch)[:, : bm.width]
            g = Image.fromarray(a, mode="L")
            img.paste(g, (int(pen + p.x_offset * scale + _ft.glyph.bitmap_left),
                          int(baseline - p.y_offset * scale - _ft.glyph.bitmap_top)), g)
        pen += p.x_advance * scale
    bb = img.getbbox()
    img = img.crop(bb) if bb else img
    pad = stroke + 4
    out = Image.new("RGBA", (img.width + pad * 2, img.height + pad * 2), (0, 0, 0, 0))
    mask = Image.new("L", out.size, 0)
    mask.paste(img, (pad, pad))
    if stroke:
        dil = mask.filter(ImageFilter.GaussianBlur(stroke * 0.62)).point(lambda v: 255 if v > 45 else 0)
        lay = Image.new("RGBA", out.size, tuple(stroke_color) + (0,))
        lay.putalpha(dil)
        out = Image.alpha_composite(out, lay)
    fill = Image.new("RGBA", out.size, tuple(color) + (0,))
    fill.putalpha(mask)
    return Image.alpha_composite(out, fill)


def wrap(text, px, max_w, **kw):
    """Break a long title into lines that fit max_w."""
    words, lines, cur = text.split(), [], ""
    for wd in words:
        t = (cur + " " + wd).strip()
        if render_text(t, px, **kw).width <= max_w or not cur:
            cur = t
        else:
            lines.append(cur)
            cur = wd
    if cur:
        lines.append(cur)
    return [render_text(l, px, **kw) for l in lines]


def plate(size, radius=22, color=(10, 6, 8), alpha=205, border=(255, 205, 66, 120)):
    img = Image.new("RGBA", size, (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    d.rounded_rectangle((0, 0, size[0] - 1, size[1] - 1), radius=radius, fill=tuple(color) + (alpha,),
                        outline=border, width=3)
    return img
