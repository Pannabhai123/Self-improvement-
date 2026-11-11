# 🦁 सच्ची दोस्ती — Scene Series

3D animated short: a golden lion (crown + red royal cape) and an orange-white kitten.

## Scene 5 — सच्ची दोस्ती (True Friendship)

| | |
|---|---|
| **Duration** | 5–6 seconds |
| **Shot** | Cinematic wide |
| **Still** | `scene-05-sachchi-dosti.png` (16:9) |
| **Video** | `scene-05-sachchi-dosti.mp4` — 1920×1080, 30fps, 5.6s, H.264 + AAC stereo |
| **Mood** | Emotional happy ending, magical golden sunlight |

### How the video was made

No video-generation model was available here, so the MP4 is rendered procedurally
from **two AI keyframes** (start + a moment later) by `render_scene05.py`:

1. `scene-05-sachchi-dosti.png` — start frame
2. `scene-05-keyframe-b.png` — generated from the start frame as an edit (head pressed
   closer, paw lifted, butterflies drifted)
3. Render: slow 8.5% push-in + gentle drift, eased A→B dissolve so the nuzzle
   settles, floating pollen (3 depth layers), 9 wing-flapping butterflies,
   pulsing golden bloom, warm grade + vignette + grain, fade in/out
4. Audio: synthesized pad swell (A-minor add9), filtered forest wind, quiet warm
   purr, three distant bird chirps — mixed and limited in Python

Re-render anytime:

```bash
python3 -m venv .venv && .venv/bin/pip install imageio-ffmpeg pillow numpy
.venv/bin/python scenes/render_scene05.py
```

### Image prompt (used)

> The same golden lion wearing a golden crown and red royal cape sits peacefully beside the same adorable orange and white kitten in a beautiful sunny forest. The lion affectionately touches his head against the kitten, both smiling happily. Colorful butterflies fly around them. Cinematic wide camera shot, magical golden sunlight, emotional happy ending, beautiful 3D animated movie style.

### Video / animation prompt (for image-to-video, 5–6s)

> Slow cinematic push-in on the golden lion and orange-white kitten sitting together on sunlit forest grass. The lion gently lowers his head and nuzzles the kitten's head; the kitten leans in and purrs, tail curling. Butterflies drift and flutter around them in soft arcs, floating pollen and golden sunbeams shimmer. Gentle breeze moves the mane, cape edge, leaves and wildflowers. Both smile, eyes closing contentedly. Camera: slow dolly-in with a slight upward drift, wide 16:9, shallow depth of field. Sound: soft orchestral swell, birds, warm purring. Emotional, warm, magical happy-ending mood, high-end 3D animation look.

### Continuity notes

- Lion: golden mane, **golden crown**, **red cape with gold trim**
- Kitten: orange tabby patches + white chest, paws and muzzle
- Setting: lush sun-dappled forest, wildflowers, ferns, light rays from upper left
