"""Fix 5 icons using Font Awesome SVG bezier paths → polygon sampling.

Sources:
  water  — FA droplet-solid   (384×512 viewBox)
  fire   — FA fire-solid      (448×512 viewBox)
  earth  — clean mountain shape (2D, wide peak + snow cap + strata)
  dark   — crescent moon only
  tourist — big round head + short trapezoid body

Draws at 128×128, Lanczos downscales to 64×64.
"""
from PIL import Image, ImageDraw
import os, math

OUT = r"C:\Users\huhai\Desktop\Java-mcmod\wandscape\src\main\resources\assets\wandscape\textures\gui\icons"
DS = 128          # draw size
FS = 64           # final size
W = (255, 255, 255, 255)
T = (0, 0, 0, 0)


def finalize(img, name):
    small = img.resize((FS, FS), Image.LANCZOS)
    small.save(os.path.join(OUT, name))
    print(f"  {name}: {os.path.getsize(os.path.join(OUT, name))} bytes")


# ── Cubic bezier helpers ──

def bezier_pt(t, p0, p1, p2, p3):
    """Evaluate cubic bezier at t ∈ [0, 1]."""
    u = 1 - t
    x = u**3 * p0[0] + 3*u**2*t * p1[0] + 3*u*t**2 * p2[0] + t**3 * p3[0]
    y = u**3 * p0[1] + 3*u**2*t * p1[1] + 3*u*t**2 * p2[1] + t**3 * p3[1]
    return (x, y)


def sample_bezier_segments(segments, n_per=20):
    """Sample a list of (p0, cp1, cp2, p3) segments → flat polygon.

    Each segment contributes n_per sample points plus its endpoint.
    Consecutive segments share the p0/p3 junction so we skip the
    first point of each subsequent segment to avoid duplicates.
    """
    pts = []
    for si, (p0, cp1, cp2, p3) in enumerate(segments):
        start = 0 if si == 0 else 1
        for i in range(start, n_per + 1):
            pts.append(bezier_pt(i / n_per, p0, cp1, cp2, p3))
    return pts


def scale_pts(pts, src_w, src_h, dst_w, dst_h, margin=4):
    """Uniform-scale points from src viewBox to dst canvas, preserving aspect."""
    scale = min((dst_w - 2 * margin) / src_w, (dst_h - 2 * margin) / src_h)
    ox = (dst_w - src_w * scale) / 2
    oy = (dst_h - src_h * scale) / 2
    return [(x * scale + ox, y * scale + oy) for x, y in pts]


# ══════════════════════════════════════════════════════════════════
# Water — FA droplet-solid  viewBox="0 0 384 512"
# Path: M192 512 C86 512 0 426 0 320 C0 228.8 130.2 57.7 192 0
#        c61.8 57.7 192 228.8 192 320 c0 106-86 192-192 192z
# ══════════════════════════════════════════════════════════════════

def water():
    segments = [
        # bottom-center → left-mid
        ((192, 512), (86,  512), (0,    426),  (0,   320)),
        # left-mid → top point
        ((0,   320), (0,   228.8), (130.2, 57.7), (192,   0)),
        # top point → right-mid
        ((192,   0), (253.8, 57.7), (384, 228.8), (384, 320)),
        # right-mid → bottom-center
        ((384, 320), (384, 426),   (298,  512),  (192, 512)),
    ]
    pts = sample_bezier_segments(segments, n_per=25)
    pts = scale_pts(pts, 384, 512, DS, DS, margin=4)

    img = Image.new("RGBA", (DS, DS), T)
    d = ImageDraw.Draw(img)
    d.polygon(pts, fill=W)
    finalize(img, "element_water.png")


# ══════════════════════════════════════════════════════════════════
# Fire — FA fire-solid  viewBox="0 0 448 512"  (outer path only)
# ══════════════════════════════════════════════════════════════════

def fire():
    segments = [
        # upper-left → upper-right (across flame tips)
        ((159.3,   5.4), (167.1,  -1.9), (186.9,  -1.8), (187.0,   5.5)),
        # upper-right → mid-right
        ((187.0,   5.5), (214.6,  31.4), (240.5,  59.3), (264.7,  89.5)),
        # mid-right → further right
        ((264.7,  89.5), (275.7,  75.1), (288.2,  59.4), (301.7,  46.6)),
        # further right → right shoulder
        ((301.7,  46.6), (309.6,  39.2), (321.8,  39.2), (329.7,  46.7)),
        # right shoulder → right bulge
        ((329.7,  46.7), (364.3,  79.7), (393.6, 123.3), (414.2, 164.7)),
        # right bulge → right-bottom
        ((414.2, 164.7), (434.5, 205.5), (448.0, 247.2), (448.0, 276.6)),
        # right-bottom → bottom-center
        ((448.0, 276.6), (448.0, 404.2), (348.2, 512.0), (224.0, 512.0)),
        # bottom-center → left-bottom
        ((224.0, 512.0),  (98.4, 512.0),   (0.0, 404.1),   (0.0, 276.5)),
        # left-bottom → left-mid
        ((0.0,   276.5),   (0.0, 238.1),  (17.8, 191.2),  (45.4, 144.8)),
        # left-mid → back to upper-left (close path)
        ((45.4,  144.8),  (73.3,  97.7), (112.7,  48.6), (159.3,   5.4)),
    ]
    pts = sample_bezier_segments(segments, n_per=25)
    pts = scale_pts(pts, 448, 512, DS, DS, margin=4)

    img = Image.new("RGBA", (DS, DS), T)
    d = ImageDraw.Draw(img)
    d.polygon(pts, fill=W)
    finalize(img, "element_fire.png")


# ══════════════════════════════════════════════════════════════════
# Earth — clean 2D mountain: wide peak + snow cap + rock strata
# ══════════════════════════════════════════════════════════════════

def earth():
    img = Image.new("RGBA", (DS, DS), T)
    d = ImageDraw.Draw(img)
    cx, cy = DS // 2, DS // 2
    hw, hh = 54, 58  # half-width, half-height (slightly taller than wide)

    # Outer diamond — classic RPG earth-element symbol
    d.polygon([(cx, cy - hh), (cx + hw, cy), (cx, cy + hh), (cx - hw, cy)], fill=W)

    # Inner diamond — crystal/ore core (cutout)
    d.polygon([(cx, cy - 24), (cx + 18, cy), (cx, cy + 24), (cx - 18, cy)], fill=T)

    finalize(img, "element_earth.png")


# ══════════════════════════════════════════════════════════════════
# Dark — crescent moon via two overlapping ellipses
# ══════════════════════════════════════════════════════════════════

def dark():
    img = Image.new("RGBA", (DS, DS), T)
    d = ImageDraw.Draw(img)

    d.ellipse([(10, 10), (118, 118)], fill=W)   # moon disc
    d.ellipse([(34, 18), (126, 110)], fill=T)    # cutout → crescent

    finalize(img, "element_dark.png")


# ══════════════════════════════════════════════════════════════════
# Tourist — big round head + short trapezoid body
# ══════════════════════════════════════════════════════════════════

def tourist():
    img = Image.new("RGBA", (DS, DS), T)
    d = ImageDraw.Draw(img)
    cx = DS // 2

    # Head — large circle
    head_cy, head_r = 30, 20
    d.ellipse([
        (cx - head_r, head_cy - head_r),
        (cx + head_r, head_cy + head_r),
    ], fill=W)

    # Body — short trapezoid (narrow at shoulders, wider at base)
    neck_y = head_cy + head_r
    body_bottom = DS - 8
    shoulder_w = 30
    base_w = 40

    d.polygon([
        (cx - shoulder_w, neck_y),
        (cx + shoulder_w, neck_y),
        (cx + base_w,     body_bottom),
        (cx - base_w,     body_bottom),
    ], fill=W)

    finalize(img, "icon_tourist.png")


# ══════════════════════════════════════════════════════════════════

if __name__ == "__main__":
    print("Fixing 5 icons (FA bezier → polygon, 128→64 Lanczos)...")
    water()
    fire()
    earth()
    dark()
    tourist()
    print("Done.")
