#!/usr/bin/env python3
"""Builds audio/README.md — index of voiced clips with start timecodes."""
import glob, os, re

BR = [0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, 0]
SR = {"m":"min","s":"sec"}

def duration(f):
    d = open(f, "rb").read()
    off = 0
    if d[:3] == b"ID3":
        sz = ((d[6] & 0x7f) << 21) | ((d[7] & 0x7f) << 14) | ((d[8] & 0x7f) << 7) | (d[9] & 0x7f)
        off = 10 + sz
    while off < len(d) - 4 and not (d[off] == 0xFF and (d[off+1] & 0xE0) == 0xE0):
        off += 1
    h = int.from_bytes(d[off:off+4], "big")
    br = BR[(h >> 12) & 0xF]
    return len(d) * 8 / (br * 1000) if br else 0

def label(i):
    p = f"chunks/{i:03d}.txt"
    if not os.path.exists(p):
        return ""
    t = re.sub(r"\s+", " ", open(p, encoding="utf-8").read()).strip()
    t = re.sub(r'^"|"$', "", t)
    return t[:78] + ("…" if len(t) > 78 else "")

def tc(sec):
    m, s = divmod(int(round(sec)), 60)
    return f"{m:02d}:{s:02d}"

files = sorted(glob.glob("vo-*.mp3"))
rows, t = [], 0.0
for f in files:
    d = duration(f)
    i = int(re.search(r"vo-(\d+)", f).group(1))
    rows.append((f, tc(t), tc(d), label(i)))
    t += d

lines = ["# वॉइस-ओवर ऑडियो — इंडेक्स", "",
         f"कुल क्लिप: **{len(files)}** | कुल अवधि: **{int(t//60)} मिनट {int(t%60)} सेकंड**", "",
         "| # | फाइल | शुरू | लंबाई | कहाँ से शुरू होता है |", "|---|---|---|---|---|"]
for f, st, du, lb in rows:
    lines.append(f"| {f[3:6]} | `{f}` | {st} | {du} | {lb} |")
lines += ["", "## जोड़ने का तरीका", "",
          "क्लिप्स क्रम से (`vo-001` → `vo-040`) जोड़ दो — बीच में कुछ मिलाने की ज़रूरत नहीं, हर क्लिप पूरे वाक्य पर खत्म होती है।",
          "ffmpeg से एक ही फाइल:", "",
          "```bash",
          "printf \"file '%s'\\n\" vo-*.mp3 > list.txt",
          "ffmpeg -f concat -safe 0 -i list.txt -c copy pura-voiceover.mp3",
          "```", "",
          "अगर 50 मिनट का वर्ज़न चाहिए तो एडिटर में 1.1–1.2x गति बढ़ा दो, या जिन क्लिप्स में सिर्फ़ \u201cलेकिन ध्यान रखो\u201d वाला हिस्सा है उन्हें छोटा कर दो।"]
open("README.md", "w", encoding="utf-8").write("\n".join(lines) + "\n")
print("\n".join(lines[:8]))
print("total", tc(t))
