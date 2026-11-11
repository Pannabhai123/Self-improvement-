#!/usr/bin/env python3
"""
Scene 5 – सच्ची दोस्ती  →  5.6 second cinematic video.

Renders a 1920x1080 MP4 from two AI keyframes:
  A) scene-05-sachchi-dosti.png  (start)
  B) scene-05-keyframe-b.png     (a moment later)

Motion design (all procedural, no external video model):
  * slow dolly-in / push-in with sub-pixel-accurate crop window
  * gentle horizontal drift (parallax feel)
  * A→B cross-dissolve on an ease curve, so the nuzzle "settles" over time
  * 3D-ish floating pollen motes + drifting butterflies sprites
  * soft golden light bloom pulse, warm grade, subtle film grain
  * vignette + fade-in / fade-out
  * generated ambience: soft pad swell + forest wind + a warm purr-ish tone
"""
import math
import os
import random
import subprocess
import wave

import imageio_ffmpeg
import numpy as np
from PIL import Image, ImageDraw, ImageFilter

HERE = os.path.dirname(os.path.abspath(__file__))
KEY_A = os.path.join(HERE, "scene-05-sachchi-dosti.png")
KEY_B = os.path.join(HERE, "scene-05-keyframe-b.png")
OUT_MP4 = os.path.join(HERE, "scene-05-sachchi-dosti.mp4")
WAV = os.path.join(HERE, "_ambience.wav")
RAW = os.path.join(HERE, "_frames.raw")

W, H = 1920, 1080
FPS = 30
DUR = 5.6
NF = int(round(FPS * DUR))          # 168 frames
SR = 48000

SS = 2          # supersample factor for crop resampling
RNG = random.Random(7)


def ease(t: float) -> float:
    """Smooth in-out."""
    return t * t * (3.0 - 2.0 * t)


def ease_out(t: float) -> float:
    return 1.0 - (1.0 - t) ** 3


# ---------------------------------------------------------------- keyframes
def load_keyframe(path):
    im = Image.open(path).convert("RGB")
    # work at 2x the output size so the crop window always has real pixels
    big = im.resize((int(im.width * SS), int(im.height * SS)), Image.LANCZOS)
    return big


A = load_keyframe(KEY_A)
B = load_keyframe(KEY_B)
assert A.size == B.size, (A.size, B.size)
SrW, SrH = A.size
ASPECT = W / H

# The crop window must always fit inside the source, even at max zoom-out.
SRC_ASPECT = SrW / SrH
if SRC_ASPECT > ASPECT:                 # source wider than 16:9 → crop width
    BASE_H = SrH
    BASE_W = SrH * ASPECT
else:                                   # source taller → crop height
    BASE_W = SrW
    BASE_H = SrW / ASPECT


def crop_window(zoom, p=0.0):
    """Return (left, top, right, bottom) float box. p = 0..1 shot progress."""
    w = BASE_W / zoom
    h = BASE_H / zoom
    # slow lateral drift + a gentle upward drift, in source pixels
    cx = SrW / 2 + math.sin(math.pi * p) * 0.010 * SrW
    cy = SrH / 2 - 0.012 * SrH * p
    l = min(max(cx - w / 2, 0.0), SrW - w)
    t = min(max(cy - h / 2, 0.0), SrH - h)
    return l, t, l + w, t + h


# ---------------------------------------------------------------- sprites
def make_mote_sprite(size, color=(255, 238, 190)):
    s = int(round(size * 4))
    img = Image.new("L", (s, s), 0)
    d = ImageDraw.Draw(img)
    r = s / 2
    d.ellipse([r - size / 2, r - size / 2, r + size / 2, r + size / 2], fill=255)
    img = img.filter(ImageFilter.GaussianBlur(size * 0.55))
    arr = np.asarray(img, dtype=np.float32)[..., None] / 255.0
    rgb = np.array(color, dtype=np.float32)[None, None, :] / 255.0
    return arr * rgb            # premultiplied soft glow, HxWx3


SPRITES = {k: make_mote_sprite(sz) for sz, k in [(2, "far"), (3.4, "mid"), (6.0, "near")]}

BUTTERFLY_COLORS = [(255, 168, 74), (110, 190, 255), (255, 226, 120), (255, 130, 160)]


def make_butterfly(color, size=26):
    s = int(round(size * 4))
    img = Image.new("RGBA", (s, s), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    cx, cy = s / 2, s / 2
    w = size / 2
    d.ellipse([cx - w, cy - w * 1.15, cx - w * 0.08, cy - w * 0.1], fill=color + (225,))
    d.ellipse([cx + w * 0.08, cy - w * 1.15, cx + w, cy - w * 0.1], fill=color + (225,))
    d.ellipse([cx - w * 0.85, cy + w * 0.02, cx - w * 0.08, cy + w * 0.85], fill=color + (190,))
    d.ellipse([cx + w * 0.08, cy + w * 0.02, cx + w * 0.85, cy + w * 0.85], fill=color + (190,))
    return img.filter(ImageFilter.GaussianBlur(0.6))


BF_SPRITES = {c: make_butterfly(c) for c in BUTTERFLY_COLORS}


class Butterfly:
    def __init__(self, i):
        self.color = BUTTERFLY_COLORS[i % len(BUTTERFLY_COLORS)]
        self.size = RNG.uniform(20, 40)
        self.base_x = RNG.uniform(0.08, 0.92)
        self.base_y = RNG.uniform(0.18, 0.80)
        self.ax = RNG.uniform(0.03, 0.09)
        self.ay = RNG.uniform(0.02, 0.06)
        self.fx = RNG.uniform(0.7, 1.5)
        self.fy = RNG.uniform(0.9, 1.9)
        self.ph = RNG.uniform(0, 6.28)
        self.vx = RNG.uniform(0.03, 0.09)
        self.vy = RNG.uniform(-0.02, 0.03)
        self.flap = RNG.uniform(9, 14)       # wingbeats per second

    def sprite(self, t):
        spr = BF_SPRITES[self.color]
        # simulate wingbeat by squashing horizontally
        k = 0.35 + 0.65 * abs(math.sin(math.pi * self.flap * t + self.ph))
        w = max(2, int(self.size * k))
        return spr.resize((w, max(2, int(self.size))), Image.LANCZOS)

    def pos(self, t):
        x = self.base_x + self.ax * math.sin(2 * math.pi * self.fx * t + self.ph) + self.vx * t
        y = self.base_y + self.ay * math.sin(2 * math.pi * self.fy * t + self.ph * 1.7) + self.vy * t
        return x, y


BUTTERFLIES = [Butterfly(i) for i in range(9)]
for _ in range(1):
    pass


class Mote:
    def __init__(self):
        self.x = RNG.random()
        self.y = RNG.random()
        self.depth = RNG.choice(["far", "mid", "mid", "near"])
        self.sp = {"far": 0.006, "mid": 0.016, "near": 0.030}[self.depth]
        self.amp = RNG.uniform(0.004, 0.012)
        self.ph = RNG.uniform(0, 6.28)
        self.f = RNG.uniform(0.4, 1.2)
        self.alpha = {"far": 0.35, "mid": 0.6, "near": 0.85}[self.depth]

    def pos(self, t):
        y = (self.y - self.sp * t) % 1.0
        x = (self.x + self.amp * math.sin(2 * math.pi * self.f * t + self.ph)) % 1.0
        return x, y


MOTES = [Mote() for _ in range(260)]

# ---------------------------------------------------------------- grade LUTs
def build_overlays():
    # vignette
    yy, xx = np.mgrid[0:H, 0:W].astype(np.float32)
    nx = (xx - W / 2) / (W / 2)
    ny = (yy - H / 2) / (H / 2)
    r = np.sqrt(nx ** 2 + ny ** 2) / math.sqrt(2)
    vig = np.clip(1.0 - 0.42 * (r ** 2.1), 0.0, 1.0)[..., None]

    # warm highlight bloom mask (top-centre light shafts)
    bx = np.exp(-((nx + 0.05) ** 2) / 0.22)
    by = np.exp(-((ny + 0.72) ** 2) / 0.5)
    bloom = (bx * by)[..., None].astype(np.float32) * 0.55

    # subtle animated grayscale grain
    grains = [np.random.default_rng(i).normal(0, 2.1, (H, W, 1)).astype(np.float32)
              for i in range(8)]
    return vig.astype(np.float32), bloom, grains


VIG, BLOOM, GRAINS = build_overlays()

# ---------------------------------------------------------------- audio
def build_audio():
    n = int(SR * DUR)
    t = np.arange(n) / SR
    rng = np.random.default_rng(3)

    # warm pad: A minor-ish add9 swell
    pad = np.zeros(n, dtype=np.float64)
    for f, a in [(110.0, 0.30), (164.81, 0.22), (220.0, 0.20),
                 (261.63, 0.14), (329.63, 0.10), (493.88, 0.06)]:
        vib = 1.0 + 0.0025 * np.sin(2 * np.pi * 0.22 * t + f)
        pad += a * np.sin(2 * np.pi * f * t * vib)
    # slow attack, gentle release
    env = np.minimum(1.0, t / 1.6) * np.clip((DUR - t) / 1.3, 0, 1) ** 1.4
    pad *= env

    # forest wind: filtered noise with slow amplitude wander
    noise = rng.normal(0, 1, n)
    # simple one-pole low-pass twice for a soft "air" tone
    for _ in range(2):
        noise = np.convolve(noise, np.ones(180) / 180.0, mode="same")
    wind_env = 0.16 + 0.10 * (0.5 + 0.5 * np.sin(2 * np.pi * 0.31 * t - 1.0))
    wind = noise / (np.max(np.abs(noise)) + 1e-9) * wind_env * env

    # warm purr-ish low tone, very quiet, entering after the nuzzle settles
    purr_env = np.clip((t - 1.2) / 1.4, 0, 1) * np.clip((DUR - t) / 1.0, 0, 1)
    purr = 0.055 * np.sin(2 * np.pi * 46.0 * t) * (0.55 + 0.45 * np.sin(2 * np.pi * 11.0 * t))
    purr *= purr_env

    # two distant bird chirps
    mix = pad + wind + purr
    for start, f0, f1, dur, amp in [(0.9, 2400, 3200, 0.09, 0.05),
                                    (2.55, 2100, 2800, 0.08, 0.04),
                                    (3.9, 2600, 3400, 0.10, 0.045)]:
        i0 = int(start * SR)
        ln = int(dur * SR)
        tt = np.arange(ln) / SR
        f = np.linspace(f0, f1, ln)
        chirp = np.sin(2 * np.pi * f * tt) * np.exp(-tt / (dur * 0.5)) * amp
        mix[i0:i0 + ln] += chirp

    # gentle limiter + fades
    mix = np.tanh(mix * 1.1) * 0.72
    mix[: int(0.05 * SR)] *= np.linspace(0, 1, int(0.05 * SR))
    mix[-int(0.25 * SR):] *= np.linspace(1, 0, int(0.25 * SR))

    stereo = np.stack([mix, np.roll(mix, 37) * 0.98], axis=1)
    pcm = (np.clip(stereo, -1, 1) * 32767).astype(np.int16)
    with wave.open(WAV, "wb") as wf:
        wf.setnchannels(2)
        wf.setsampwidth(2)
        wf.setframerate(SR)
        wf.writeframes(pcm.tobytes())
    return WAV


# ---------------------------------------------------------------- render
def encode(frames_path, out_path):
    ff = imageio_ffmpeg.get_ffmpeg_exe()
    cmd = [
        ff, "-y", "-hide_banner", "-loglevel", "error",
        "-f", "rawvideo", "-pix_fmt", "rgb24", "-s", f"{W}x{H}", "-r", str(FPS),
        "-i", frames_path,
        "-i", WAV,
        "-c:v", "libx264", "-preset", "slow", "-crf", "17",
        "-pix_fmt", "yuv420p", "-profile:v", "high", "-level", "4.2",
        "-c:a", "aac", "-b:a", "192k",
        "-shortest", "-movflags", "+faststart", out_path,
    ]
    subprocess.run(cmd, check=True)


def render():
    fh = open(RAW, "wb")
    for i in range(NF):
        t = i / FPS
        u = i / (NF - 1)                       # 0..1 progress
        e = ease_out(u)

        zoom = 1.0 + 0.085 * e                 # slow push-in
        box = crop_window(zoom, u)
        frame_a = A.resize((W, H), Image.BICUBIC, box=box)
        frame_b = B.resize((W, H), Image.BICUBIC, box=box)

        # A→B nuzzle settle, mostly in the middle of the shot
        m = ease(np.clip((u - 0.15) / 0.7, 0, 1))
        frame = Image.blend(frame_a, frame_b, m * 0.92)

        arr = np.asarray(frame, dtype=np.float32) / 255.0

        # ---- soft golden light pulse + bloom
        pulse = 0.10 + 0.06 * math.sin(2 * math.pi * 0.55 * t - 0.8)
        arr = arr * (1.0 + BLOOM * pulse)
        arr = arr + BLOOM * 0.045 * np.array([1.0, 0.86, 0.60], dtype=np.float32)

        # ---- warm cinematic grade
        arr = arr ** 0.96
        arr[..., 0] *= 1.045
        arr[..., 1] *= 1.012
        arr[..., 2] *= 0.955
        arr = np.clip(arr, 0, 1)
        arr = arr + 0.055 * (arr - 0.5) * (1 - np.abs(arr - 0.5) * 2)

        # ---- vignette
        arr = arr * VIG

        # ---- fade in / out
        fade = min(1.0, u / 0.09) * min(1.0, (1.0 - u) / 0.11)
        arr = arr * fade

        # ---- grain
        g = GRAINS[i % len(GRAINS)] * (0.6 + 0.8 * fade)
        arr = arr * 255.0 + g
        out = Image.fromarray(np.clip(arr, 0, 255).astype(np.uint8))

        # ---- butterflies
        for bf in BUTTERFLIES:
            x, y = bf.pos(t)
            spr = bf.sprite(t)
            px = int(x * W - spr.width / 2)
            py = int(y * H - spr.height / 2)
            if -80 < px < W + 80 and -80 < py < H + 80:
                out.paste(spr, (px, py), spr)

        # ---- pollen motes as additive glow
        oa = np.asarray(out, dtype=np.float32)
        for mo in MOTES:
            x, y = mo.pos(t)
            spr = SPRITES[mo.depth]
            sh, sw = spr.shape[:2]
            px = int(x * W - sw / 2)
            py = int(y * H - sh / 2)
            if px < -sw or py < -sh or px > W or py > H:
                continue
            x0, y0 = max(px, 0), max(py, 0)
            x1, y1 = min(px + sw, W), min(py + sh, H)
            if x1 <= x0 or y1 <= y0:
                continue
            sub = spr[y0 - py:y1 - py, x0 - px:x1 - px]
            a = mo.alpha * (0.55 + 0.45 * math.sin(2 * math.pi * 0.9 * t + mo.ph))
            oa[y0:y1, x0:x1] += sub * a * 190.0

        fh.write(np.clip(oa, 0, 255).astype(np.uint8).tobytes())
        if i % 30 == 0:
            print(f"  frame {i:3d}/{NF}  u={u:.2f} zoom={zoom:.3f}", flush=True)

    fh.close()
    print("encoding …", flush=True)
    encode(RAW, OUT_MP4)


if __name__ == "__main__":
    print("building audio …")
    build_audio()
    print("rendering frames …")
    render()
    size = os.path.getsize(OUT_MP4)
    print(f"done → {OUT_MP4}  ({size/1e6:.2f} MB)")
