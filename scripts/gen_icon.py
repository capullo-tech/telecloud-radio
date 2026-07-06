"""
Generates app icon PNGs for Telecloud Radio from IMG_3120.PNG.
Processing: removes outer white border, replaces gradient background with black,
keeps white icon elements white.
"""

from PIL import Image, ImageDraw
import numpy as np
import os

BASE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RES  = os.path.join(BASE, 'app', 'src', 'main', 'res')
SRC  = os.path.join(BASE, 'IMG_3120.PNG')


def process_icon(size: int) -> Image.Image:
    img = Image.open(SRC).convert('RGBA')

    # Flood-fill the outer white border from all 4 corners using a sentinel colour
    # that cannot appear naturally in the image.
    work = img.copy()
    sentinel = (1, 2, 3, 255)
    for corner in [(0, 0), (img.width - 1, 0), (0, img.height - 1), (img.width - 1, img.height - 1)]:
        ImageDraw.floodfill(work, corner, sentinel, thresh=30)

    data = np.array(work)
    r, g, b = data[:, :, 0], data[:, :, 1], data[:, :, 2]

    outer = (r == 1) & (g == 2) & (b == 3)   # flood-filled outer border
    icon  = (r > 200) & (g > 200) & (b > 200) & ~outer  # white icon shapes
    bg    = ~icon & ~outer                               # coloured gradient → black

    result = np.zeros_like(data)
    result[outer] = [0,   0,   0,   0  ]   # transparent
    result[icon]  = [255, 255, 255, 255]   # white
    result[bg]    = [0,   0,   0,   255]   # black

    processed = Image.fromarray(result)

    # Crop to the tight squircle boundary, then scale to fill the target square
    bbox = processed.getbbox()
    if bbox:
        processed = processed.crop(bbox)

    return processed.resize((size, size), Image.LANCZOS)


def save_all():
    preview = process_icon(1024)
    preview.save(os.path.join(BASE, 'icon_preview.png'))
    print('icon_preview.png (1024×1024)')

    configs = [
        ('mipmap-mdpi',    48),
        ('mipmap-hdpi',    72),
        ('mipmap-xhdpi',   96),
        ('mipmap-xxhdpi',  144),
        ('mipmap-xxxhdpi', 192),
    ]
    for folder, sz in configs:
        out_dir = os.path.join(RES, folder)
        os.makedirs(out_dir, exist_ok=True)
        icon = process_icon(sz)
        icon.save(os.path.join(out_dir, 'ic_launcher.png'))
        icon.save(os.path.join(out_dir, 'ic_launcher_round.png'))
        print(f'{folder}/ic_launcher.png  ({sz}×{sz})')

    web = process_icon(512)
    web.save(os.path.join(BASE, 'ic_launcher_web.png'))
    print('ic_launcher_web.png (512×512)')
    print('Done.')


if __name__ == '__main__':
    save_all()
