# 🔮 Magic Circle Web Editor API Guide

The Magic Circle Web Editor lives at `tools/magic-circle-editor/` — a Web (Vite + TypeScript) visual particle effect designer.

![Magic circle web editor diagram](wandscape:textures/gui/guide/magic_editor_diagram.png =200x100)

---

## 📖 UI Controls & Fields API Reference

| Control / Property | Type / Range | Default | Details |
| :--- | :--- | :--- | :--- |
| **`shape`** (geometry) | Enum (`String`) | `circle` | Determines the base geometry of the magic circle. Values: `circle`, `polygon`, `star`. |
| **`polygon_sides`** (side/vertex count) | Integer (`3 ~ 12`) | `5` | Valid when `shape` is `polygon` or `star`; controls polygon sides or star vertex count (5 = five-pointed star). |
| **`beads`** (perimeter stroke) | Boolean (`true/false`) | `true` | **Uniform perimeter stroking algorithm**. When `true`, distributes particle highlights evenly along the polygon/star perimeter, avoiding dense clumping at sharp star tips. |
| **`density`** (particle density) | Float (`0.1 ~ 5.0`) | `1.0` | Particles per unit length. Higher values give a denser glowing stroke; lower values a sparser rune-like feel. |
| **`pulse_interval`** (pulse period) | Integer (`0 ~ 200`) | `40` | Tick period of the magic circle's breathing pulse (20 ticks = 1s). Controls the ring expansion/contraction rhythm. |
| **`smoothstep`** (smooth interpolation) | Boolean (`true/false`) | `true` | Enables Hermite smooth fade in/out (Smoothstep) so particles transition more naturally. |
| **`axis`** (projection axis) | Enum (`XZ / Y`) | `XZ` | Projection plane in-game. `XZ` lays flat on the ground (normal ritual circles); `Y` floats vertically (portals/defensive shields). |
| **`radius`** (outer radius) | Float (`0.5 ~ 10.0`) | `2.5` | Outer radius of the circle, in MC blocks. |
| **`color_start / color_end`** | RGBA Hex (`#RRGGBBAA`) | `#A020F0FF` | Color gradient at particle birth and death, with alpha fade support. |
| **`curve_alpha`** (alpha curve) | Bezier 4-Points | `(0,0)->(1,1)` | 4-point cubic Bezier curve to fine-tune the alpha over the particle lifetime. |
| **`curve_scale`** (scale curve) | Bezier 4-Points | `(0,0)->(1,1)` | 4-point cubic Bezier curve to fine-tune scale expansion/shrink over the particle lifetime. |
| **`Export Spec`** (export contract) | Action Button | — | Exports a JSON spec file conforming to the `MagicCircleSpec` schema, loadable into the mod's `magic_circles/`. |

---

## 🚀 4-Step Simple Effect Design Workflow

### Step 1: Choose the base shape (Shape & Geometry)
Pick `circle`, `polygon` or `star` in the dropdown, and adjust `polygon_sides`.

### Step 2: Configure animation & easing (Animation & Smoothstep)
Enable `beads` and `smoothstep` smooth interpolation, and set the `pulse_interval` breathing period.

### Step 3: Adjust Bezier curves (Curve Editor)
Drag the Bezier curve nodes to customize the `curve_alpha` and `curve_scale` animation curves.

### Step 4: Export the Spec JSON contract
Click **Export Spec** to export the JSON contract file and drop it into the mod's resource pack.

---

## 🛠️ Troubleshooting & FAQ

### Q1: The exported magic circle only stands vertically in MC — it won't lie flat on the ground?
- **Fix**: switch `axis` to `XZ` (flat on the ground) in the editor.

---

👉 [Jump to Building Scanner guide](guide:scanner_guide)  
👉 [Back to main test page](guide:test_guide)
