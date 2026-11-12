#!/usr/bin/env python3
"""Accurate clip durations (via ffmpeg), audio/README.md index, and YouTube chapter timings."""
import glob, os, re, json, subprocess, imageio_ffmpeg

FF = imageio_ffmpeg.get_ffmpeg_exe()

def duration(f):
    err = subprocess.run([FF, "-i", f], capture_output=True, text=True).stderr
    m = re.search(r"Duration: (\d+):(\d+):([\d.]+)", err)
    return int(m.group(1)) * 3600 + int(m.group(2)) * 60 + float(m.group(3)) if m else 0.0

def tc(s):
    m, sec = divmod(int(round(s)), 60)
    return f"{m:02d}:{sec:02d}" if m < 60 else f"{m//60}:{m%60:02d}:{sec:02d}"

HINDI = dict(zip("एक दो तीन चार पाँच छह सात आठ नौ दस ग्यारह बारह तेरह चौदह पंद्रह सोलह सत्रह अठारह उन्नीस बीस "
                 "इक्कीस बाईस तेईस चौबीस पच्चीस छब्बीस सत्ताईस अट्ठाईस उनतीस तीस इकतीस बत्तीस तैंतीस चौंतीस पैंतीस "
                 "छत्तीस सैंतीस अड़तीस उनतालीस चालीस इकतालीस बयालीस तैंतालीस चवालीस पैंतालीस छियालीस सैंतालीस अड़तालीस".split(),
                 range(1, 49)))

files = sorted(glob.glob("vo-*.mp3"))
starts, durs, t = {}, {}, 0.0
for f in files:
    i = int(re.search(r"vo-(\d+)", f).group(1))
    d = duration(f)
    starts[i], durs[i] = t, d
    t += d + 0.4          # 0.4 s pause after every clip (matches the joined file)
total = t - 0.4

law = {}
for i in sorted(starts):
    txt = open(f"chunks/{i:03d}.txt", encoding="utf-8").read()
    for m in re.finditer(r"नियम ([^\s।]+)।", txt):
        w = m.group(1)
        if w in HINDI and HINDI[w] not in law:
            law[HINDI[w]] = starts[i] + (m.start() / max(len(txt), 1)) * durs[i]

json.dump({"total": round(total, 1), "clips": {str(k): [round(starts[k], 1), round(durs[k], 1)] for k in starts},
           "laws": {str(k): round(v, 1) for k, v in law.items()}},
          open("clip_times.json", "w"), indent=1)

# ---------- audio/README.md ----------
def label(i):
    p = f"chunks/{i:03d}.txt"
    txt = re.sub(r"\s+", " ", open(p, encoding="utf-8").read()).strip().strip('"')
    return txt[:70] + ("…" if len(txt) > 70 else "")

lines = ["# वॉइस-ओवर — फाइनल ऑडियो इंडेक्स", "",
         f"**कुल अवधि: {tc(total)}** (60 मिनट) · 40 क्लिप · हर क्लिप के बीच 0.4 सेकंड का पॉज़", "",
         "| फाइल | शुरू | लंबाई | कहाँ से शुरू होता है |", "|---|---|---|---|"]
for i in sorted(starts):
    lines.append(f"| `vo-{i:03d}.mp3` | {tc(starts[i])} | {tc(durs[i])} | {label(i)} |")

lines += ["", "## सेक्शन मैप", "", "| सेक्शन | शुरू |", "|---|---|"]
for name, k in [("हुक + किताब का परिचय", 1), ("भाग 1 — पहचान और छिपना (नियम 1–6)", 2),
                ("भाग 2 — दूसरों से काम करवाना (नियम 7–14)", 7), ("भाग 3 — दुश्मन, दूरी, डर (नियम 15–22)", 13),
                ("भाग 4 — ध्यान, समय, छवि (नियम 23–30)", 18), ("भाग 5 — चालें, सपने, नज़ारे (नियम 31–40)", 25),
                ("भाग 6 — खतरनाक नियम (नियम 41–48)", 33), ("क्लोज़िंग — आख़िरी बात", 39)]:
    lines.append(f"| {name} | {tc(starts[k])} |")

lines += ["", "## फाइलें", "",
          "| क्या | कहाँ |", "|---|---|",
          f"| **फाइनल (सब जुड़ा हुआ)** | `pura-voiceover.mp3` — {tc(total + 39*0.4)}, 41 MB, 96 kbps |",
          "| अलग क्लिप (एडिटिंग के लिए) | `vo-001.mp3` … `vo-040.mp3` |",
          "| साफ़ टेक्स्ट | `narration-clean.txt` |",
          "| 40 हिस्से | `chunks/` |",
          "| टाइमकोड (JSON) | `clip_times.json` |", "",
          "## दोबारा जोड़ना हो तो", "",
          "```bash",
          "printf \"file '%s'\\nfile 'silence.mp3'\\n\" vo-*.mp3 > list.txt",
          "ffmpeg -f concat -safe 0 -i list.txt -ac 1 -ar 44100 -b:a 96k pura-voiceover.mp3",
          "```"]
open("README.md", "w", encoding="utf-8").write("\n".join(lines) + "\n")

# ---------- chapters for the upload package ----------
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

ch = ["0:00 शुरुआत — फूके की पार्टी और किताब का परिचय"]
for k in range(1, 49):
    ch.append(f"{tc(law[k])} नियम {k} — {TITLES[k]}")
ch.append(f"{tc(starts[39] + (total + 39*0.4 - starts[39]) * 0.15)} आख़िरी बात — 5 ज़रूरी, 5 खतरनाक नियम")
json.dump(ch, open("chapters.json", "w"), ensure_ascii=False, indent=1)
print(f"total {tc(total)} | file {tc(total + 39*0.4)} | chapters {len(ch)}")
print(ch[0], "|", ch[1], "|", ch[-1])
