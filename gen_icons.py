"""Generate 64×64 white-channel element icons via 256×256 supersampling.

Draws at 256×256 with smooth parametric curves and organic polygon outlines,
then Lanczos-downscales to 64×64 for clean anti-aliased edges.

Pure white (#FFF) body, light gray (#CCC / #AAA) for subtle 3D shading.
Transparent background. Runtime tint via WandscapeTheme.drawIcon().
"""
from PIL import Image, ImageDraw
import os, math

OUT = r"C:\Users\huhai\Desktop\Java-mcmod\wandscape\src\main\resources\assets\wandscape\textures\gui\icons"
S = 256          # draw resolution
F = 64           # final 64×64
W = (255, 255, 255, 255)
T = (0, 0, 0, 0)
G1 = (200, 200, 200, 255)   # light gray (left face of 3D blocks)
G2 = (155, 155, 155, 255)   # dark gray (right face of 3D blocks)


def finalize(img, name):
    """Lanczos downscale and save."""
    small = img.resize((F, F), Image.LANCZOS)
    path = os.path.join(OUT, name)
    small.save(path)
    print(f"  {name}: {os.path.getsize(path)} bytes")


# ══════════════════════════════════════════════════════════════════
# Earth — isometric block (MC players recognise cubes instantly)
# ══════════════════════════════════════════════════════════════════

def earth():
    img = Image.new("RGBA", (S, S), T)
    d = ImageDraw.Draw(img)
    cx, cy = S // 2, S // 2

    # Isometric cube proportions
    top_h  = 44    # half-height of top diamond
    top_w  = 90    # half-width of top diamond
    side_h = 72    # visible side depth

    # Top face
    d.polygon([
        (cx,        cy - top_h),
        (cx + top_w, cy),
        (cx,        cy + top_h),
        (cx - top_w, cy),
    ], fill=W)

    # Left visible face
    d.polygon([
        (cx - top_w, cy),
        (cx,         cy + top_h),
        (cx,         cy + top_h + side_h),
        (cx - top_w, cy + side_h),
    ], fill=G1)

    # Right visible face
    d.polygon([
        (cx,         cy + top_h),
        (cx + top_w, cy),
        (cx + top_w, cy + side_h),
        (cx,         cy + top_h + side_h),
    ], fill=G2)

    finalize(img, "element_earth.png")


# ══════════════════════════════════════════════════════════════════
# Wood — simple tree (triangular crown + rectangular trunk)
# ══════════════════════════════════════════════════════════════════

def wood():
    img = Image.new("RGBA", (S, S), T)
    d = ImageDraw.Draw(img)
    cx = S // 2

    # Two-tier crown
    d.polygon([(cx, 10), (cx + 60, 120), (cx - 60, 120)], fill=W)
    d.polygon([(cx, 60), (cx + 78, 180), (cx - 78, 180)], fill=W)

    # Trunk
    trunk_w = 28
    d.rectangle([(cx - trunk_w, 180), (cx + trunk_w, 250)], fill=W)

    finalize(img, "element_wood.png")


# ══════════════════════════════════════════════════════════════════
# Water — parametric teardrop (r(θ) = r₀·(1 + ε·sin θ))
# ══════════════════════════════════════════════════════════════════

def water():
    img = Image.new("RGBA", (S, S), T)
    d = ImageDraw.Draw(img)

    cx, cy = S // 2, S // 2 + 18
    r0, eps = 78, 0.48
    n = 100  # high-res polygon → smooth curve after downscale

    points = []
    for i in range(n):
        t = i / (n - 1) * 2 * math.pi - math.pi / 2   # start from top
        r = r0 * (1 + eps * math.sin(t))
        points.append((cx + r * math.cos(t), cy + r * math.sin(t)))

    d.polygon(points, fill=W)
    finalize(img, "element_water.png")


# ══════════════════════════════════════════════════════════════════
# Fire — organic 3-tip flame outline
# ══════════════════════════════════════════════════════════════════

def fire():
    img = Image.new("RGBA", (S, S), T)
    d = ImageDraw.Draw(img)

    # Hand-crafted flame polygon at 256×256.
    # Three visible tips at top, undulating sides, pointed bottom.
    points = [
        # ── Top: three flame tips ──
        (108, 24),    # left tip
        (128,  0),    # center tip (highest)
        (148, 24),    # right tip

        # ── Right side (wavy descent) ──
        (140, 44),
        (162, 56),
        (152, 78),
        (176, 100),
        (164, 126),
        (184, 152),
        (168, 178),
        (190, 206),
        (170, 228),

        # ── Bottom point ──
        (128, 254),

        # ── Left side (mirror with slight organic asymmetry) ──
        (86, 228),
        (66, 206),
        (88, 178),
        (72, 152),
        (92, 126),
        (80, 100),
        (104, 78),
        (94, 56),
        (116, 44),
    ]

    d.polygon(points, fill=W)
    finalize(img, "element_fire.png")


# ══════════════════════════════════════════════════════════════════
# Metal — gear with 8 pronounced teeth
# ══════════════════════════════════════════════════════════════════

def metal():
    img = Image.new("RGBA", (S, S), T)
    d = ImageDraw.Draw(img)
    cx, cy = S // 2, S // 2
    orr, irr = 106, 54     # outer ring radius, inner ring radius
    cr = 22                 # center hub radius
    teeth = 8
    tooth_h = 20            # tooth protrusion height

    # Star-gear polygon: alternate outer (tooth tip) and inner (tooth valley)
    pts = []
    for i in range(teeth * 2):
        angle = (i / (teeth * 2)) * 2 * math.pi - math.pi / 2
        r = orr + tooth_h if i % 2 == 0 else orr - 6
        pts.append((cx + r * math.cos(angle), cy + r * math.sin(angle)))
    d.polygon(pts, fill=W)

    # Hollow ring
    d.ellipse([(cx - irr, cy - irr), (cx + irr, cy + irr)], fill=T)

    # Center hub
    d.ellipse([(cx - cr, cy - cr), (cx + cr, cy + cr)], fill=W)

    # 4 inner spokes connecting hub to ring
    for i in range(4):
        angle = i * math.pi / 2
        sx = cx + (cr + 4) * math.cos(angle)
        sy = cy + (cr + 4) * math.sin(angle)
        ex = cx + (irr - 4) * math.cos(angle)
        ey = cy + (irr - 4) * math.sin(angle)
        d.line([(sx, sy), (ex, ey)], fill=W, width=12)

    finalize(img, "element_metal.png")


# ══════════════════════════════════════════════════════════════════
# Wind — three swooping arc bands
# ══════════════════════════════════════════════════════════════════

def wind():
    img = Image.new("RGBA", (S, S), T)
    d = ImageDraw.Draw(img)

    # Three stacked swoosh arcs — top → bottom
    d.arc([(16, 28), (240, 120)], 180, 270, fill=W, width=18)
    d.arc([(16, 80), (240, 172)], 180, 258, fill=W, width=18)
    d.arc([(32, 136), (208, 224)], 190, 272, fill=W, width=14)

    finalize(img, "element_wind.png")


# ══════════════════════════════════════════════════════════════════
# Dark — crescent moon + 4-point star
# ══════════════════════════════════════════════════════════════════

def dark():
    img = Image.new("RGBA", (S, S), T)
    d = ImageDraw.Draw(img)

    # Crescent moon (two overlapping circles)
    d.ellipse([(20, 28), (196, 204)], fill=W)    # main moon disc
    d.ellipse([(56, 36), (228, 192)], fill=T)     # cutout → crescent

    # 4-point sparkle star
    scx, scy, sr = 204, 196, 34
    d.polygon([
        (scx,       scy - sr),
        (scx + 10,  scy - 3),
        (scx + sr,  scy - 3),
        (scx + 14,  scy + 3),
        (scx + sr,  scy + sr),
        (scx + 10,  scy + 8),
        (scx,       scy + sr),
        (scx - 10,  scy + 8),
        (scx - sr,  scy + sr),
        (scx - 14,  scy + 3),
        (scx - sr,  scy - 3),
        (scx - 10,  scy - 3),
    ], fill=W)

    finalize(img, "element_dark.png")


# ══════════════════════════════════════════════════════════════════
# Tourist — proportional person silhouette
# ══════════════════════════════════════════════════════════════════

def tourist():
    img = Image.new("RGBA", (S, S), T)
    d = ImageDraw.Draw(img)
    cx = S // 2

    # Head — circle at top (radius ≈ 20, ~25% of visible body)
    head_cy = 36
    head_r = 22
    d.ellipse([(cx - head_r, head_cy - head_r),
               (cx + head_r, head_cy + head_r)], fill=W)

    # Neck — narrow connector
    neck_y = head_cy + head_r  # 58
    neck_h = 10
    neck_w = 12

    # Torso — wider at shoulders, narrows slightly at waist
    shoulder_y = neck_y + neck_h   # 68
    waist_y = 214
    shoulder_w = 52
    waist_w = 34

    d.polygon([
        (cx - shoulder_w, shoulder_y),
        (cx + shoulder_w, shoulder_y),
        (cx + waist_w,    waist_y),
        (cx - waist_w,    waist_y),
    ], fill=W)

    # Legs — two separated rounded rectangles
    leg_top = waist_y + 4
    leg_bottom = 250
    leg_w = 18
    gap = 10

    d.rounded_rectangle(
        [(cx - gap // 2 - leg_w, leg_top),
         (cx - gap // 2,         leg_bottom)],
        radius=6, fill=W)
    d.rounded_rectangle(
        [(cx + gap // 2,         leg_top),
         (cx + gap // 2 + leg_w, leg_bottom)],
        radius=6, fill=W)

    finalize(img, "icon_tourist.png")


# ══════════════════════════════════════════════════════════════════
# Warning — triangle with exclamation mark
# ══════════════════════════════════════════════════════════════════

def warning():
    img = Image.new("RGBA", (S, S), T)
    d = ImageDraw.Draw(img)
    cx = S // 2

    # Rounded triangle
    margin = 12
    top_y = margin
    bottom_y = S - margin
    half_w = 110

    d.polygon([
        (cx,        top_y),
        (cx + half_w, bottom_y),
        (cx - half_w, bottom_y),
    ], fill=W)

    # Exclamation bar
    bar_w, bar_h = 14, 72
    d.rectangle([
        (cx - bar_w // 2, top_y + 58),
        (cx + bar_w // 2, top_y + 58 + bar_h),
    ], fill=T)

    # Exclamation dot
    dot_r = 12
    dot_cy = top_y + 58 + bar_h + 28
    d.ellipse([
        (cx - dot_r, dot_cy - dot_r),
        (cx + dot_r, dot_cy + dot_r),
    ], fill=T)

    finalize(img, "icon_warning.png")


# ══════════════════════════════════════════════════════════════════
# Main
# ══════════════════════════════════════════════════════════════════

if __name__ == "__main__":
    print("Generating icons (256→64 via Lanczos, white channel)...")

    elements = [
        ("earth", earth),
        ("wood",  wood),
        ("water", water),
        ("fire",  fire),
        ("metal", metal),
        ("wind",  wind),
        ("dark",  dark),
    ]
    for name, fn in elements:
        fn()

    tourist()
    warning()

    total = 0
    for f in os.listdir(OUT):
        if f.endswith(".png"):
            total += os.path.getsize(os.path.join(OUT, f))
    print(f"Done — {total} bytes total in {OUT}")
