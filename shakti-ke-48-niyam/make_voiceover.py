#!/usr/bin/env python3
"""01-pura-saar-hindi.md  ->  bolkar sunane layak clean narration + 1500-char clips."""
import re, os, math

SRC = "01-pura-saar-hindi.md"
OUT = "audio"
CHUNK_DIR = os.path.join(OUT, "chunks")
LIMIT = 1450  # generate_speech hard limit 1500

HINDI_NUM = {1:"एक",2:"दो",3:"तीन",4:"चार",5:"पाँच",6:"छह",7:"सात",8:"आठ",9:"नौ",10:"दस",
11:"ग्यारह",12:"बारह",13:"तेरह",14:"चौदह",15:"पंद्रह",16:"सोलह",17:"सत्रह",18:"अठारह",19:"उन्नीस",20:"बीस",
21:"इक्कीस",22:"बाईस",23:"तेईस",24:"चौबीस",25:"पच्चीस",26:"छब्बीस",27:"सत्ताईस",28:"अट्ठाईस",29:"उनतीस",30:"तीस",
31:"इकतीस",32:"बत्तीस",33:"तैंतीस",34:"चौंतीस",35:"पैंतीस",36:"छत्तीस",37:"सैंतीस",38:"अड़तीस",39:"उनतालीस",40:"चालीस",
41:"इकतालीस",42:"बयालीस",43:"तैंतालीस",44:"चवालीस",45:"पैंतालीस",46:"छियालीस",47:"सैंतालीस",48:"अड़तालीस"}

LATIN = {
    "reputation": "रेपुटेशन", "image": "इमेज", "formless": "फ़ॉर्मलेस",
    "The 48 Laws of Power": "द फ़ोर्टी एट लॉज़ ऑफ़ पावर", "Laws of Power": "लॉज़ ऑफ़ पावर",
    "Robert Greene": "रॉबर्ट ग्रीन", "Talleyrand": "तैलेराँ", "Fouquet": "फूके",
    "Fouché": "फ़ूशे", "Fouch": "फ़ूशे", "De Gaulle": "द गॉल", "Danton": "दांतों",
    "HR": "एच आर", "WhatsApp": "व्हाट्सएप", "system": "सिस्टम", "process": "प्रोसेस",
}

OPENING = """1661, फ्रांस। एक आदमी ने अपने राजा के लिए ऐसी पार्टी दी जो इतिहास में अमर हो गई। सोने के बर्तन, हज़ार मोमबत्तियाँ, पूरा बगीचा जगमग। राजा ने मुस्कुराकर सबकी तारीफ़ की, और उसके कुछ ही हफ़्ते बाद वो आदमी जेल में मर गया। उसने गुनाह किया था सिर्फ़ एक — वो अपने राजा से ज़्यादा चमक गया। यही बात आज ऑफिस में, दुकान में, घर में, हर जगह चलती है, बस तरीका बदल गया है। आज मैं आपको वो किताब बताऊँगा, जो पिछले पच्चीस साल से दुनिया के सबसे ताकतवर लोगों के बेडसाइड पर रखी है। शक्ति के 48 नियम। पूरे अड़तालीस नियम, आसान भाषा में। बीच में निकलना मत।"""

META_DROP = [
    "यह सार **\"शक्ति के 48 नियम\"**",
    "> यह सार",
    "> यह किताब की नकल नहीं है",
    "> हर नियम पर लगभग",
]

def clean(txt: str) -> str:
    # drop the top meta block (first blockquote block) and the closing italic note
    txt = txt.split("\n---\n", 1)[-1] if txt.lstrip().startswith("# ") else txt
    txt = re.sub(r"^\*\(.*?\)\*$", "", txt, flags=re.M | re.S)
    # headings
    txt = re.sub(r"^# भाग (\d+): (.*?) \(नियम [\d–]+\)\s*$",
                 lambda m: f"भाग {HINDI_NUM[int(m.group(1))]}। {m.group(2)}।", txt, flags=re.M)
    txt = re.sub(r"^# शक्ति के 48 नियम.*$", "", txt, flags=re.M)
    txt = re.sub(r"^# (?!भाग|शक्ति)(.*)$", r"\1।", txt, flags=re.M)
    txt = re.sub(r"^## नियम (\d+): (.*)$",
                 lambda m: f"नियम {HINDI_NUM[int(m.group(1))]}। {m.group(2)}।", txt, flags=re.M)
    # labels -> spoken
    txt = txt.replace("**किताब क्या कहती है:**", "किताब कहती है —")
    txt = txt.replace("**किस्सा:**", "इसका एक किस्सा सुनो।")
    txt = txt.replace("**आज की ज़िंदगी में:**", "अब आज की ज़िंदगी में —")
    txt = txt.replace("**ज़हर की मात्रा:**", "लेकिन ध्यान रखो —")
    txt = txt.replace("आख़िरी लाइन, जो वीडियो में कहनी है:", "और आख़िर में एक बात याद रखना —")
    txt = txt.replace("वीडियो में भी यही लाइन बोलना — दर्शक को आप पर भरोसा होगा।", "")
    txt = txt.replace("(नोट: यह सार समझाने के लिए लिखा गया है", "")
    # bold -> plain
    txt = txt.replace("**", "")
    # latin words
    for k, v in sorted(LATIN.items(), key=lambda x: -len(x[0])):
        txt = txt.replace(k, v)
    # leftover markdown / bracket noise
    txt = re.sub(r"^\s*#{1,6}\s*", "", txt, flags=re.M)
    txt = re.sub(r"^\s*\d+\.\s+", "", txt, flags=re.M)
    txt = re.sub(r"([^\s()]+) \(\1\)", r"\1", txt)
    txt = re.sub(r"^\s*[-*]\s+", "", txt, flags=re.M)
    txt = re.sub(r"^\s*---+\s*$", "", txt, flags=re.M)
    txt = re.sub(r"\(\s*\)", "", txt)
    txt = re.sub(r"`+", "", txt)
    txt = re.sub(r"[ \t]+", " ", txt)
    txt = re.sub(r"\n{2,}", "\n\n", txt)
    return txt.strip()

def chunks(txt: str, limit=LIMIT):
    paras = [p.strip() for p in txt.split("\n\n") if p.strip()]
    out, buf = [], ""
    for p in paras:
        if len(buf) + len(p) + 2 <= limit:
            buf = (buf + "\n\n" + p).strip()
            continue
        if buf:
            out.append(buf)
        if len(p) <= limit:
            buf = p
        else:  # very long paragraph: split on sentences
            sents = re.split(r"(?<=[।?!])\s+", p)
            buf = ""
            for s in sents:
                if len(buf) + len(s) + 1 > limit:
                    out.append(buf.strip()); buf = s
                else:
                    buf = (buf + " " + s).strip()
    if buf:
        out.append(buf)
    return out

raw = open(SRC, encoding="utf-8").read()
txt = OPENING + "\n\n" + clean(raw)
os.makedirs(CHUNK_DIR, exist_ok=True)
for f in os.listdir(CHUNK_DIR):
    os.remove(os.path.join(CHUNK_DIR, f))
cs = chunks(txt)
# merge very short chunks into the previous one
merged = []
for c in cs:
    if merged and len(c) < 500 and len(merged[-1]) + len(c) <= 1500:
        merged[-1] = merged[-1] + "\n\n" + c
    else:
        merged.append(c)
cs = merged
# rebalance the tail so the final clip doesn't start mid-sentence
if len(cs) >= 2 and len(cs[-1]) < 300:
    tail = cs[-2] + "\n\n" + cs[-1]
    sents = re.split(r"(?<=[।?!])\s+", tail)
    a = ""
    for k, s2 in enumerate(sents):
        if len(a) + len(s2) + 1 > 1200:
            b = " ".join(sents[k:])
            if a and b:
                cs = cs[:-2] + [a.strip(), b.strip()]
            break
        a = (a + " " + s2).strip()
for i, c in enumerate(cs, 1):
    open(os.path.join(CHUNK_DIR, f"{i:03d}.txt"), "w", encoding="utf-8").write(c)
open(os.path.join(OUT, "narration-clean.txt"), "w", encoding="utf-8").write(txt)

total = len(txt)
print(f"clean chars: {total} | clips: {len(cs)} | est. audio: {total/850:.1f} min")
print(f"clip sizes: min={min(map(len,cs))} max={max(map(len,cs))}")
print("=" * 60)
print(cs[0][:600])
print("...")
print(cs[1][:400])
