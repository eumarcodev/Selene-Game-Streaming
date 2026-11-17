"""Render the Selene branding SVGs and write every raster icon of this repository.

Usage (from the repository root, after `node branding/generate.cjs`):
    python branding/export.py [--chrome PATH]

Needs Google Chrome or Microsoft Edge (headless rendering) and Pillow.
"""
import argparse
import shutil
import subprocess
import tempfile
from pathlib import Path

from PIL import Image

ROOT = Path(__file__).resolve().parent.parent
SVG = ROOT / "branding" / "svg"
CHROMES = [
    r"C:\Program Files\Google\Chrome\Application\chrome.exe",
    r"C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe",
    "google-chrome",
    "chromium",
]


def find_chrome(explicit):
    for c in ([explicit] if explicit else []) + CHROMES:
        if c and (Path(c).exists() or shutil.which(c)):
            return c
    raise SystemExit("Chrome or Edge not found; pass --chrome")


class Renderer:
    def __init__(self, chrome):
        self.chrome = chrome
        self.tmp = Path(tempfile.mkdtemp(prefix="branding-"))
        self.cache = {}

    def render(self, name, w=1024, h=1024):
        key = (name, w, h)
        if key in self.cache:
            return self.cache[key].copy()
        src = SVG / name
        out = self.tmp / f"{src.stem}-{w}x{h}.png"
        subprocess.run(
            [self.chrome, "--headless=new", "--disable-gpu", "--hide-scrollbars",
             "--force-device-scale-factor=1", "--default-background-color=00000000",
             f"--window-size={w},{h}", f"--screenshot={out}", src.as_uri()],
            check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        img = Image.open(out).convert("RGBA")
        if img.size != (w, h):
            raise SystemExit(f"{name}: rendered {img.size}, expected {(w, h)}")
        self.cache[key] = img
        return img.copy()

    def sized(self, name, size):
        return self.render(name).resize((size, size), Image.LANCZOS)


def save_png(img, path):
    path.parent.mkdir(parents=True, exist_ok=True)
    img.save(path, optimize=True)
    print("  ", path)


def save_ico(r, path, small_svg, large_svg, sizes, split=32):
    """ICO with the small mark up to `split` px and the large icon above it."""
    frames = [r.sized(small_svg if s <= split else large_svg, s) for s in sizes]
    biggest = max(frames, key=lambda i: i.size[0])
    others = [i for i in frames if i is not biggest]
    biggest.save(path, format="ICO", sizes=[(s, s) for s in sizes], append_images=others)
    got = sorted(Image.open(path).info.get("sizes", []))
    print("  ", path, "sizes", got)


def export(r):
    res = ROOT / "app/src/main/res"
    densities = {"mdpi": 1, "hdpi": 1.5, "xhdpi": 2, "xxhdpi": 3, "xxxhdpi": 4}
    for d, k in densities.items():
        legacy, adaptive = round(48 * k), round(108 * k)
        save_png(r.sized("selene-icon-rounded.svg", legacy), res / f"mipmap-{d}/ic_launcher.png")
        save_png(r.sized("selene-foreground.svg", adaptive), res / f"mipmap-{d}/ic_launcher_foreground.png")
        save_png(r.sized("selene-background.svg", adaptive), res / f"mipmap-{d}/ic_launcher_background.png")
        save_png(r.sized("selene-monochrome.svg", adaptive), res / f"mipmap-{d}/ic_launcher_monochrome.png")
    save_png(r.sized("selene-icon.svg", 512), ROOT / "app/src/main/ic_launcher-web.png")
    save_png(r.sized("selene-monochrome-small.svg", 96), res / "drawable/app_icon.png")
    save_png(r.render("selene-banner-tv.svg", 320, 180), res / "drawable-xhdpi/atv_banner.png")
    save_png(r.render("selene-banner-ouya.svg", 732, 412).convert("RGB"), res / "drawable-xhdpi/ouya_icon.png")
    shutil.copyfile(SVG / "selene-monochrome.xml", res / "drawable/ic_lime_layer.xml")
    print("  ", res / "drawable/ic_lime_layer.xml")

    # README icon.
    save_png(r.sized("selene-icon-rounded.svg", 256), ROOT / "branding/selene-icon.png")



def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--chrome")
    args = ap.parse_args()
    export(Renderer(find_chrome(args.chrome)))


if __name__ == "__main__":
    main()
