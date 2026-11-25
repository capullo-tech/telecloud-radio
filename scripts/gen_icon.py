"""
Draws the Telecloud Radio launcher icon from geometry and writes every icon asset:
- docs/icon/ic_launcher.svg, the master: white mark on the black rounded square;
- app/src/main/res/drawable/ic_launcher_foreground.xml, the adaptive icon's foreground and
  themed (monochrome) layer, for API 26+;
- app/src/main/res/mipmap-*/ic_launcher.png and ic_launcher_round.png, for API 23 to 25.

The coordinates are measured against the previous 192 px icon, whose source image (IMG_3120.PNG)
was never committed; the 192-unit grid below is that icon's pixel grid.

Needs: python3 with shapely (pip install shapely), and rsvg-convert (librsvg) for the PNGs.
Run from anywhere: python3 scripts/gen_icon.py
"""
import math
import os
import subprocess
from shapely.geometry import Point, LineString, Polygon, box
from shapely.geometry.polygon import orient
from shapely.ops import unary_union

BASE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RES = os.path.join(BASE, "app", "src", "main", "res")
MASTER = os.path.join(BASE, "docs", "icon", "ic_launcher.svg")

Q = 32  # buffer segments per quarter circle


def rrect(x0, y0, x1, y1, r):
    return box(x0 + r, y0 + r, x1 - r, y1 - r).buffer(r, quad_segs=Q)


def line(p, q, w):
    return LineString([p, q]).buffer(w / 2, quad_segs=Q, cap_style="round")


def disk(c, r):
    return Point(c).buffer(r, quad_segs=Q * 2)


def rounded(poly_pts, r):
    """A polygon with its convex corners rounded by r."""
    return Polygon(poly_pts).buffer(-r, join_style="mitre").buffer(r, quad_segs=Q)


def catmull(pts, n=24):
    """Points along a Catmull-Rom spline through pts (x, y, w); w is interpolated the same way."""
    p = [pts[0]] + pts + [pts[-1]]
    out = []
    for i in range(1, len(p) - 2):
        p0, p1, p2, p3 = p[i - 1], p[i], p[i + 1], p[i + 2]
        for s in range(n):
            t = s / n
            t2, t3 = t * t, t * t * t
            out.append(tuple(0.5 * (2 * p1[k] + (-p0[k] + p2[k]) * t + (2 * p0[k] - 5 * p1[k] + 4 * p2[k] - p3[k]) * t2 + (-p0[k] + 3 * p1[k] - 3 * p2[k] + p3[k]) * t3) for k in range(3)))
    out.append(tuple(pts[-1]))
    return out


def stroke(pts):
    """A stroke along the spline through pts = [(x, y, width)], round where the width is not zero."""
    c = catmull(pts)
    left, right = [], []
    for i, (x, y, w) in enumerate(c):
        j0, j1 = max(i - 1, 0), min(i + 1, len(c) - 1)
        dx, dy = c[j1][0] - c[j0][0], c[j1][1] - c[j0][1]
        ln = math.hypot(dx, dy) or 1
        nx, ny = -dy / ln, dx / ln
        left.append((x + nx * w / 2, y + ny * w / 2))
        right.append((x - nx * w / 2, y - ny * w / 2))
    shape = Polygon(left + right[::-1]).buffer(0)
    caps = [disk(c[k][:2], c[k][2] / 2) for k in (0, -1) if c[k][2] > 0]
    return unary_union([shape] + caps)


# Radio body with the antenna knob, and the telescopic antenna with its ball.
body = rrect(37.6, 76.2, 152.2, 148.4, 6.5)
knob = rrect(54.2, 69.2, 67.3, 81, 4.2)
antenna = stroke([(64.5, 73.4, 4.0), (96, 57, 3.6), (128.4, 41.4, 2.8)])
ball = disk((128.6, 41.3), 3.8)

# Dial: a dark face with a white hub, needle and 13 ticks over 270 degrees.
dc, dr = (70, 112.8), 23.8
face = disk(dc, dr)
hub = disk(dc, 7.5)
na = math.radians(-59)
needle = stroke([(dc[0], dc[1], 4.4), (dc[0] + 12.4 * math.cos(na), dc[1] + 12.4 * math.sin(na), 2.6)])
ticks = []
for i in range(13):
    a = math.radians(135 + 22.5 * i)
    ticks.append(line((dc[0] + 15.6 * math.cos(a), dc[1] + 15.6 * math.sin(a)), (dc[0] + 19.8 * math.cos(a), dc[1] + 19.8 * math.sin(a)), 1.9))
dial_detail = unary_union([hub, needle] + ticks)

# Speaker grille: five dark bars.
grille = unary_union([line((101.9, y), (141.2, y), 3.8) for y in (92, 98, 104, 110.8, 117.4)])

# Paper plane: the upper wing and the lower wing meet at the tip, the fold between them stays dark.
tip, left_tip, low_tip = (165.4, 53.8), (102.8, 79.8), (153.4, 108.2)
fold_top = (155.6, 64.2)
upper_wing = rounded([left_tip, tip, fold_top, (120.6, 86.4)], 0.5)
lower_wing = rounded([tip, low_tip, (137.0, 97.2), (127.6, 104.6), (128.0, 91.6), fold_top], 0.5)
plane = unary_union([upper_wing, lower_wing])
outline = plane.buffer(2.1, quad_segs=Q)

# The plane's trail: two dark strokes sweeping down past the dial, a white line between them.
trail_upper = stroke([(124.6, 89, 4.6), (122.5, 95, 4.8), (120.3, 101, 4.6), (117, 107, 4.1), (113.1, 113, 3.7), (106.9, 121, 3.0), (103, 125, 2.8), (98.5, 129, 2.4), (94, 132.6, 1.4), (90.5, 135, 0)])
trail_lower = stroke([(130.6, 105.6, 6.6), (127.8, 109.2, 5.4), (124.4, 113, 4.6), (115.9, 121, 4.2), (110.6, 125, 3.9), (104.2, 129, 3.7), (97.5, 132.6, 2.6), (90.5, 135, 0)])

radio = unary_union([body, knob]).difference(face).union(dial_detail.intersection(face))
radio = radio.difference(grille).difference(trail_upper).difference(trail_lower).difference(outline)
mark = unary_union([radio, plane, antenna, ball]).simplify(0.015)


def ring_d(coords, f):
    pts = list(coords)[:-1]
    return "M" + " L".join(f(x, y) for x, y in pts) + " Z"


def path_d(geom, f):
    out = []
    # Outer rings counter-clockwise, holes clockwise: the non-zero rule then needs no fillType.
    for p in (orient(q, 1.0) for q in getattr(geom, "geoms", [geom])):
        out.append(ring_d(p.exterior.coords, f))
        out += [ring_d(r.coords, f) for r in p.interiors]
    return " ".join(out)


d192 = path_d(mark, lambda x, y: f"{x:.2f},{y:.2f}")
with open(MASTER, "w") as f:
    f.write(f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 192 192"><rect width="192" height="192" rx="47.8" fill="#000"/><path fill="#fff" d="{d192}"/></svg>\n')

# Adaptive foreground: the 192 grid is the 72dp visible square, placed at 18dp of the 108dp canvas.
k, off = 72 / 192, 18
d108 = path_d(mark, lambda x, y: f"{x * k + off:.2f},{y * k + off:.2f}")
subpaths = "\n".join("            " + s.strip() + " Z" for s in d108.split("Z") if s.strip())
with open(os.path.join(RES, "drawable", "ic_launcher_foreground.xml"), "w") as f:
    f.write(f"""<?xml version="1.0" encoding="utf-8"?>
<!-- Generated by scripts/gen_icon.py from geometry; edit that script, not this file. The mark sits in
     the 66dp safe zone of the 108dp canvas. Outer outlines run counter-clockwise and holes clockwise,
     so the default non-zero fill draws the detail as holes and the themed (monochrome) icon keeps it. -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp"
    android:height="108dp"
    android:viewportWidth="108"
    android:viewportHeight="108">
    <path
        android:fillColor="#FFFFFFFF"
        android:pathData="
{subpaths}" />
</vector>
""")

for density, size in (("mdpi", 48), ("hdpi", 72), ("xhdpi", 96), ("xxhdpi", 144), ("xxxhdpi", 192)):
    for name in ("ic_launcher", "ic_launcher_round"):
        out = os.path.join(RES, f"mipmap-{density}", f"{name}.png")
        subprocess.run(["rsvg-convert", "-w", str(size), "-h", str(size), MASTER, "-o", out], check=True)
print("wrote", MASTER, "the foreground drawable and 10 mipmap PNGs")
