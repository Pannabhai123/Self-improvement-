#!/usr/bin/env python3
"""Join the 40 clips (0.4 s gaps) and add the audio FX chain.

step 1  every clip -> loudness-normalised mono 44.1 kHz
step 2  concat with 0.4 s silence  ->  pura-voiceover-raw.mp3
step 3  FX: high-pass, warmth/presence EQ, gentle compression, short room ambience,
        de-esser-ish top taming, limiter, final loudness -15 LUFS
        ->  pura-voiceover-fx.mp3   (this is what the video is muxed with)
"""
import glob, os, re, subprocess, sys
import imageio_ffmpeg

FF = imageio_ffmpeg.get_ffmpeg_exe()
HERE = os.path.dirname(os.path.abspath(__file__))
FIN = os.path.join(HERE, "final")


def run(cmd):
    r = subprocess.run(cmd, capture_output=True, text=True)
    if r.returncode:
        print("FFMPEG FAILED:", " ".join(cmd)[:500], "\n", r.stderr[-1200:], flush=True)
        sys.exit(1)


def duration(f):
    e = subprocess.run([FF, "-i", f], capture_output=True, text=True).stderr
    m = re.search(r"Duration: (\d+):(\d+):([\d.]+)", e)
    return int(m.group(1)) * 3600 + int(m.group(2)) * 60 + float(m.group(3))


def main():
    os.makedirs(FIN, exist_ok=True)
    clips = sorted(glob.glob(os.path.join(HERE, "vo-*.mp3")))
    print(len(clips), "clips", flush=True)
    for f in clips:
        out = os.path.join(FIN, os.path.basename(f))
        if os.path.exists(out):
            continue
        run([FF, "-y", "-i", f, "-af", "loudnorm=I=-16:TP=-1.5:LRA=11",
             "-ac", "1", "-ar", "44100", "-b:a", "128k", out, "-loglevel", "error"])

    sil = os.path.join(FIN, "silence.mp3")
    if not os.path.exists(sil):
        run([FF, "-y", "-f", "lavfi", "-i", "anullsrc=r=44100:cl=mono", "-t", "0.4",
             "-b:a", "128k", sil, "-loglevel", "error"])

    lst = os.path.join(FIN, "list.txt")
    with open(lst, "w") as fh:
        for i, f in enumerate(sorted(glob.glob(os.path.join(FIN, "vo-*.mp3")))):
            fh.write(f"file '{f}'\n")
            if i != len(clips) - 1:
                fh.write(f"file '{sil}'\n")

    raw = os.path.join(HERE, "pura-voiceover-raw.mp3")
    run([FF, "-y", "-f", "concat", "-safe", "0", "-i", lst, "-ac", "1", "-ar", "44100",
         "-b:a", "128k", raw, "-loglevel", "error"])
    print("raw:", round(duration(raw), 2), "s", flush=True)

    fx = ("highpass=f=85:width_type=q:w=0.7,"
          "equalizer=f=190:t=q:w=1.2:g=1.8,"
          "equalizer=f=2800:t=q:w=1.0:g=2.6,"
          "equalizer=f=6500:t=q:w=1.4:g=1.2,"
          "acompressor=threshold=-19dB:ratio=3:attack=12:release=220:makeup=2.0:knee=4,"
          "aecho=0.85:0.7:38|95:0.055|0.03,"
          "alimiter=level_in=1:limit=0.94,"
          "volume=1.0dB,"
          "loudnorm=I=-15:TP=-1.5:LRA=11")
    out = os.path.join(HERE, "pura-voiceover-fx.mp3")
    run([FF, "-y", "-i", raw, "-af", fx, "-ac", "1", "-ar", "44100", "-b:a", "96k",
         out, "-loglevel", "error"])
    print("fx:", round(duration(out), 2), "s", round(os.path.getsize(out) / 1048576, 1), "MB", flush=True)


if __name__ == "__main__":
    main()
