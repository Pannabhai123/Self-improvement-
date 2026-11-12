#!/usr/bin/env python3
"""1080p build: cover art + effects + big law cards + processed (FX) audio.

Same story/flow as build_video.py, but 1920x1080 and tuned so the final file
stays under ~90 MB (Arena can only push <100 MB to Drive).
"""
import json, os, subprocess, sys, time
import imageio_ffmpeg
import textrender as T
from PIL import Image

FF = imageio_ffmpeg.get_ffmpeg_exe()
HERE = os.path.dirname(os.path.abspath(__file__))
AUD = os.path.abspath(os.path.join(HERE, "..", "audio"))
THUMB = os.path.abspath(os.path.join(HERE, "..", "thumbnail"))
CARDS = os.path.join(HERE, "cards1080")
SEGS = os.path.join(HERE, "segs1080")
W, H, FPS = 1920, 1080, 25
SRC_W, SRC_H = 2640, 1485
CRF = os.environ.get("CRF", "31")
AUDIO_IN = os.environ.get("AUDIO", os.path.join(AUD, "pura-voiceover-fx.mp3"))

os.makedirs(CARDS, exist_ok=True)
os.makedirs(SEGS, exist_ok=True)

times = json.load(open(os.path.join(AUD, "clip_times.json")))
TOTAL = times["total"] + 39 * 0.4          # includes the 0.4 s gaps
LAW = {int(k): v for k, v in times["laws"].items()}
LAW[1] = min(LAW[1], 118)

TITLES = {1:"अपने मालिक/गुरु से ज़्यादा चमकते मत दिखो",2:"दोस्तों पर आँख बंद भरोसा मत करो",3:"अपने इरादे छिपाओ",
4:"ज़रूरत से ज़्यादा मत बोलो",5:"प्रतिष्ठा पर जान लगा दो",6:"ध्यान खींचो",7:"दूसरों से काम करवाओ, श्रेय अपने नाम",
8:"लोगों को अपने पास आने दो",9:"लड़ाई बहस से नहीं, काम से जीतो",10:"दुखी लोगों से दूरी रखो",
11:"लोगों को थोड़ा निर्भर रहने दो",12:"चुनिंदा ईमानदारी से हथियार छीनो",13:"मदद माँगते वक्त उसका फ़ायदा दिखाओ",
14:"दोस्त जैसा दिखो, जासूस जैसा काम करो",15:"दुश्मन को पूरी तरह कुचलो — या माफ़ करो",16:"दूरी से इज़्ज़त बढ़ती है",
17:"डर की अनिश्चितता बनाए रखो",18:"अपना किला मत बनाओ",19:"गलत आदमी को मत छेड़ो",
20:"किसी के पक्ष में पूरी तरह मत बंधो",21:"बेवकूफ़ बनो ताकि बेवकूफ़ फँसे",22:"हार मानने की कला",
23:"अपनी ताकत एक ही जगह लगाओ",24:"बेहतरीन दरबारी बनो",25:"खुद को नए सिरे से गढ़ो",26:"अपने हाथ साफ़ रखो",
27:"लोगों के विश्वास पर चलो",28:"काम शुरू करो दिलेरी से",29:"आख़िरी नतीजे तक योजना बनाओ",
30:"अपनी मेहनत दिखाओ ही मत",31:"विकल्प तुम दो, चुनने दो उन्हें",32:"लोगों के सपनों पर चलो",
33:"कमज़ोर पेंच खोजो",34:"अपने हिसाब से राजा बनो",35:"समय की कला सीखो",36:"जो नहीं मिल सकता, उसे नज़रअंदाज़ करो",
37:"दमदार नज़ारे बनाओ",38:"सोचो जो चाहो, बर्ताव दूसरों जैसा",39:"पानी में उथल-पुथल करो",40:"मुफ़्त को तुच्छ समझो",
41:"बड़े आदमी की जगह मत लो",42:"गड़रिये को मारो",43:"दिल और दिमाग पर काम करो",44:"आइना दिखाओ",
45:"बदलाव की बात करो, ज़्यादा मत बदलो",46:"पूरी तरह परफ़ेक्ट मत दिखो",47:"जीत के बाद रुकना सीखो",48:"निराकार हो जाओ"}

GOLD, WHITE = (255, 205, 66), (255, 250, 244)


def make_law_card(n):
    num = T.render_text(f"नियम {n}", 68, GOLD, stroke=4)
    lines = T.wrap(TITLES[n], 58, 1150, color=WHITE, stroke=4)
    pad, gap = 46, 8
    h = pad * 2 + num.height + sum(l.height - 12 for l in lines) + 4
    w = max(num.width, max(l.width for l in lines)) + pad * 2
    card = T.plate((int(w), int(h)), radius=30)
    y = pad
    card.alpha_composite(num, (pad, y))
    y += num.height - 4
    for l in lines:
        card.alpha_composite(l, (pad, y))
        y += l.height - 12
    p = os.path.join(CARDS, f"law{n:02d}.png")
    card.save(p)
    return p


def make_intro_card():
    a = T.render_text("शक्ति के 48 नियम", 116, GOLD, stroke=6)
    b = T.render_text("पूरी किताब · 1 घंटे में · बोलचाल की हिंदी", 52, WHITE, stroke=4)
    w = max(a.width, b.width) + 72
    card = T.plate((w, a.height + b.height + 60), radius=34)
    card.alpha_composite(a, (36, 26))
    card.alpha_composite(b, (36, a.height + 26))
    p = os.path.join(CARDS, "intro.png")
    card.save(p)
    return p


def make_closing_card():
    a = T.render_text("आख़िरी बात", 108, GOLD, stroke=6)
    b = T.render_text("5 ज़रूरी नियम · 5 खतरनाक नियम · किताब की कमज़ोरी", 54, WHITE, stroke=4)
    w = max(a.width, b.width) + 72
    card = T.plate((w, a.height + b.height + 60), radius=34)
    card.alpha_composite(a, (36, 26))
    card.alpha_composite(b, (36, a.height + 28))
    p = os.path.join(CARDS, "closing.png")
    card.save(p)
    return p


def run(cmd):
    r = subprocess.run(cmd, capture_output=True, text=True)
    if r.returncode:
        print("FFMPEG FAILED:\n", " ".join(cmd)[:900], "\n", r.stderr[-1500:], flush=True)
        sys.exit(1)


def render_segment(idx, start, dur, img, card, card_at, zoom_in=True, fade_in=0.35,
                   bar_from=None, hold=7.4, card_y=56, card_x="(W-w)/2"):
    out = os.path.join(SEGS, f"seg{idx:03d}.mp4")
    if os.path.exists(out):
        return out
    frames = max(int(round(dur * FPS)), 2)
    z = ("min(1+0.00035*on,1.12)" if zoom_in else "max(1.12-0.00035*on,1)")
    inputs = ["-loop", "1", "-i", img]
    fc = (f"[0:v]scale={SRC_W}:{SRC_H}:force_original_aspect_ratio=increase,"
          f"crop={SRC_W}:{SRC_H},"
          f"zoompan=z='{z}':x='iw/2-(iw/zoom/2)':y='ih/2-(ih/zoom/2)':d={frames}:s={W}x{H}:fps={FPS},"
          f"eq=contrast=1.07:saturation=1.10:brightness=-0.02,vignette=PI/4.6")
    if card:
        inputs += ["-loop", "1", "-i", card]
        fc += (f"[bg];[1:v]format=rgba,fade=in:st={card_at}:d=0.7:alpha=1,"
               f"fade=out:st={card_at + hold}:d=0.7:alpha=1[card];"
               f"[bg][card]overlay=x={card_x}:y={card_y}:shortest=1")
    else:
        fc += "[bg];[bg]null"
    base = f"iw*({bar_from}+t)/{TOTAL}" if bar_from else f"iw*t/{TOTAL}"
    fc += (f",drawbox=x=0:y={H - 10}:w='{base}':h=10:color=0xFFCD42@0.9:t=fill"
           f",fade=in:st=0:d={fade_in},fade=out:st={max(dur - 0.3, 0):.2f}:d=0.3[v]")
    cmd = [FF, "-y"] + inputs + ["-t", f"{dur:.2f}", "-filter_complex", fc, "-map", "[v]",
           "-c:v", "libx264", "-preset", "veryfast", "-tune", "stillimage",
           "-crf", CRF, "-pix_fmt", "yuv420p", "-r", str(FPS), "-g", "50", "-an", out,
           "-loglevel", "error"]
    run(cmd)
    return out


def main():
    t0 = time.time()
    laws = sorted(LAW)
    print(f"total {TOTAL:.0f}s | law1 at {LAW[1]:.0f}s | crf {CRF}", flush=True)
    for n in laws:
        make_law_card(n)
    intro, closing = make_intro_card(), make_closing_card()
    print("cards ready", flush=True)

    cover = os.path.join(THUMB, "cover.png")
    seg_list = [render_segment(0, 0, LAW[1], cover, intro, 1.0, True, fade_in=1.2,
                               hold=LAW[1] - 3, card_y=56)]
    for i, n in enumerate(laws):
        start = LAW[n]
        end = LAW[laws[i + 1]] if i + 1 < len(laws) else TOTAL
        dur = end - start
        if dur < 1.5:
            continue
        card = make_law_card(n)
        if n == 48:
            seg_list.append(render_segment(n, start, 96, cover, card, 0.8, True, bar_from=start))
            seg_list.append(render_segment(100, start + 96, TOTAL - (start + 96), cover, closing,
                                           1.0, False, bar_from=start + 96, hold=9.0))
            continue
        seg_list.append(render_segment(n, start, dur, cover, card, 0.8,
                                       zoom_in=(i % 2 == 0), bar_from=start))
    print(f"segments: {len(seg_list)} | build {time.time() - t0:.0f}s", flush=True)

    lst = os.path.join(SEGS, "list.txt")
    with open(lst, "w") as f:
        for s in seg_list:
            f.write(f"file '{s}'\n")
    silent = os.path.join(HERE, "video_silent_1080.mp4")
    run([FF, "-y", "-f", "concat", "-safe", "0", "-i", lst, "-c", "copy", silent, "-loglevel", "error"])
    print("concat done", flush=True)

    final = os.path.join(HERE, "shakti-48-niyam-1080p.mp4")
    run([FF, "-y", "-i", silent, "-i", AUDIO_IN,
         "-map", "0:v:0", "-map", "1:a:0", "-c:v", "copy", "-c:a", "aac", "-b:a", "64k", "-ac", "1",
         "-ar", "44100", "-shortest", "-movflags", "+faststart",
         "-metadata", "title=शक्ति के 48 नियम — पूरा सार (1080p)",
         final, "-loglevel", "error"])
    print("FINAL:", final, round(os.path.getsize(final) / 1048576, 1), "MB", flush=True)
    print("done in", int(time.time() - t0), "s", flush=True)


if __name__ == "__main__":
    main()
