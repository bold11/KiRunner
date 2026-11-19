"""
Turns an animated (or still) background image into the ADVENTURE parallax background.

    python tools/make_background.py "C:/path/to/Pixilart - Space.gif"

Writes app/src/main/res/drawable-nodpi/bg_space.png: every frame scaled to FRAME_H pixels tall
(the game's buffer is 224 px tall, the extra height is room for vertical parallax) and stacked
top to bottom. AdvRenderer plays the frames in a loop and scrolls them slower than the level.
"""
import os
import sys

from PIL import Image, ImageSequence

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, "app", "src", "main", "res", "drawable-nodpi", "bg_space.png")
FRAME_H = 300


def main():
    if len(sys.argv) < 2:
        sys.exit("usage: python tools/make_background.py <image.gif|png>")
    src = Image.open(sys.argv[1])
    frames = [f.convert("RGB") for f in ImageSequence.Iterator(src)]
    w = round(frames[0].width * FRAME_H / frames[0].height)
    sheet = Image.new("RGB", (w, FRAME_H * len(frames)))
    for i, f in enumerate(frames):
        sheet.paste(f.resize((w, FRAME_H), Image.BOX), (0, i * FRAME_H))
    sheet.save(OUT, optimize=True)
    print("wrote %d frames of %dx%d to %s" % (len(frames), w, FRAME_H, OUT))


if __name__ == "__main__":
    main()
