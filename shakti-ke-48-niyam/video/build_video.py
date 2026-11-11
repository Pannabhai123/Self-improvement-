#!/usr/bin/env python3
"""Build the full 60-minute video: cover art + effects + per-law text cards + final audio."""
import json, os, subprocess, sys, time
import imageio_ffmpeg
import textrender as T
from PIL import Image

FF = imageio_ffmpeg.get_ffmpeg_exe()
HERE = os.path.dirname(os.path.abspath(__file__))
AUD = os.path.abspath(os.path.join(HERE, "..", "audio"))
THUMB = os.path.abspath(os.path.join(HERE, "..", "thumbnail"))
CARDS = os.path.join(HERE, "cards")
SEGS = os.path.join(HERE, "segs")
W, H, FPS = 1280, 720, 25

os.makedirs(CARDS, exist_ok=True)
os.makedirs(SEGS, exist_ok=True)

times = json.load(open(os.path.join(AUD, "clip_times.json")))
TOTAL = times["total"] + 39 * 0.4          # 3603 s = 1:00:03
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

SECTIONS = {1: "भाग 1 · पहचान और छिपना", 7: "भाग 2 · दूसरों से काम करवाना",
            15: "भाग 3 · दुश्मन, दूरी और डर", 23: "भाग 4 · ध्यान, समय और छवि",
            31: "भाग 5 · चालें, सपने और नज़ारे", 41: "भाग 6 · खतरनाक नियम और आख़िरी सीख"}
GOLD, WHITE = (255, 205, 66), (255, 250, 244)


def make_law_card(n):
    num = T.render_text(f"नियम {n}", 46, GOLD, stroke=3)
    lines = T.wrap(TITLES[n], 40, 760, color=WHITE, stroke=3)
    pad, gap = 32, 6
    h = pad * 2 + num.height + sum(l.height - 8 for l in lines) + 4
    w = max(num.width, max(l.width for l in lines)) + pad * 2
    card = T.plate((int(w), int(h)))
    y = pad
    card.alpha_composite(num, (pad, y))
    y += num.height - 2
    for l in lines:
        card.alpha_composite(l, (pad, y))
        y += l.height - 8
    p = os.path.join(CARDS, f"law{n:02d}.png")
    card.save(p)
    return p


def make_section_card(txt):
    t = T.render_text(txt, 44, GOLD, stroke=3)
    card = T.plate((t.width + 56, t.height + 34), radius=16)
    card.alpha_composite(t, (28, 16))
    p = os.path.join(CARDS, f"sec_{abs(hash(txt)) % 9999}.png")
    card.save(p)
    return p


def make_closing_card():
    a = T.render_text("आख़िरी बात", 74, GOLD, stroke=5)
    b = T.render_text("5 ज़रूरी नियम · 5 खतरनाक नियम · किताब की कमज़ोरी", 38, WHITE, stroke=3)
    w = max(a.width, b.width) + 64
    card = T.plate((w, a.height + b.height + 54))
    card.alpha_composite(a, (32, 24))
    card.alpha_composite(b, (32, a.height + 22))
    p = os.path.join(CARDS, "closing.png")
    card.save(p)
    return p


def run(cmd):
    r = subprocess.run(cmd, capture_output=True, text=True)
    if r.returncode:
        print("FFMPEG FAILED:\n", " ".join(cmd)[:900], "\n", r.stderr[-1500:], flush=True)
        sys.exit(1)


def render_segment(idx, start, dur, img, card, card_at, zoom_in=True, fade_in=0.35, bar_from=None):
    out = os.path.join(SEGS, f"seg{idx:03d}.mp4")
    if os.path.exists(out):
        return out
    frames = max(int(round(dur * FPS)), 2)
    z = (f"min(1+0.00035*on,1.12)" if zoom_in else f"max(1.12-0.00035*on,1)")
    inputs = ["-loop", "1", "-i", img]
    fc = (f"[0:v]scale=1760:990:force_original_aspect_ratio=increase,crop=1760:990,"
          f"zoompan=z='{z}':x='iw/2-(iw/zoom/2)':y='ih/2-(ih/zoom/2)':d={frames}:s={W}x{H}:fps={FPS},"
          f"eq=contrast=1.07:saturation=1.10:brightness=-0.02,vignette=PI/4.6,noise=alls=4:allf=t+u")
    if card:
        inputs += ["-loop", "1", "-i", card]
        fc += (f"[bg];[1:v]format=rgba,fade=in:st={card_at}:d=0.7:alpha=1,"
               f"fade=out:st={card_at + 7.4}:d=0.7:alpha=1[card];"
               f"[bg][card]overlay=x='(W-w)/2':y=38:shortest=1")
    else:
        fc += "[bg];[bg]null"
    base = f"iw*({bar_from}+t)/{TOTAL}" if bar_from else f"iw*t/{TOTAL}"
    fc += (f",drawbox=x=0:y={H - 7}:w='{base}':h=7:color=0xFFCD42@0.9:t=fill"
           f",fade=in:st=0:d={fade_in},fade=out:st={max(dur - 0.3, 0):.2f}:d=0.3[v]")
    cmd = [FF, "-y"] + inputs + ["-t", f"{dur:.2f}", "-filter_complex", fc, "-map", "[v]",
           "-c:v", "libx264", "-preset", "veryfast", "-crf", "26", "-pix_fmt", "yuv420p",
           "-r", str(FPS), "-g", "50", "-an", out, "-loglevel", "error"]
    run(cmd)
    return out


def main():
    t0 = time.time()
    laws = sorted(LAW)
    print(f"total {TOTAL:.0f}s | law1 at {LAW[1]:.0f}s", flush=True)
    for n in laws:
        make_law_card(n)
    sec_cards = {k: make_section_card(v) for k, v in SECTIONS.items()}
    closing = make_closing_card()
    print("cards ready", flush=True)

    seg_list = []
    # segment 0: the cover itself (thumbnail with text), until law 1
    seg_list.append(render_segment(0, 0, LAW[1], os.path.join(THUMB, "youtube-thumbnail.png"),
                                   None, 0, True, fade_in=1.2))
    for i, n in enumerate(laws):
        start = LAW[n]
        end = LAW[laws[i + 1]] if i + 1 < len(laws) else TOTAL
        dur = end - start
        if dur < 1.5:
            continue
        card = make_law_card(n)
        if n == 48:
            seg_list.append(render_segment(n, start, 96, os.path.join(THUMB, "cover.png"), card,
                                           0.8, True, bar_from=start))
            seg_list.append(render_segment(100, start + 96, TOTAL - (start + 96),
                                           os.path.join(THUMB, "cover.png"), closing, 1.0,
                                           False, bar_from=start + 96))
            continue
        seg_list.append(render_segment(n, start, dur, os.path.join(THUMB, "cover.png"), card, 0.8,
                                       zoom_in=(i % 2 == 0), bar_from=start))
    print(f"segments: {len(seg_list)} | build {time.time() - t0:.0f}s", flush=True)

    lst = os.path.join(SEGS, "list.txt")
    with open(lst, "w") as f:
        for s in seg_list:
            f.write(f"file '{s}'\n")
    silent = os.path.join(HERE, "video_silent.mp4")
    run([FF, "-y", "-f", "concat", "-safe", "0", "-i", lst, "-c", "copy", silent, "-loglevel", "error"])
    print("concat done", flush=True)

    final = os.path.join(HERE, "shakti-48-niyam-final.mp4")
    run([FF, "-y", "-i", silent, "-i", os.path.join(AUD, "pura-voiceover.mp3"),
         "-map", "0:v:0", "-map", "1:a:0", "-c:v", "copy", "-c:a", "aac", "-b:a", "96k",
         "-shortest", "-movflags", "+faststart",
         "-metadata", "title=शक्ति के 48 नियम — पूरा सार",
         final, "-loglevel", "error"])
    print("FINAL:", final, os.path.getsize(final) // 1048576, "MB", flush=True)
    print("done in", int(time.time() - t0), "s", flush=True)


if __name__ == "__main__":
    main()
