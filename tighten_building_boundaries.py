#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Tighten every building's `boundary` AABB to the building's real extent.

Building JSON layout (docs/data-formats.md): `pattern` lists [x,y,z] offsets,
`block_indices[i]` picks into `palette` for the block at pattern[i], and
`boundary` is the AABB that drives box clearing (`EnqueueHelper.fillBoundaryAsAir`
expands it into air ops), area rendering and frustum culling.

The scanner cannot tell where a building ends and the terrain it sits in begins,
so it frames the whole scanned region — the boundary (and the pattern) can stick
out past the building. Positions that are scanned terrain but not part of the
building were already marked by hand as `minecraft:air` (see
BuildingConfig.NON_CELL_BLOCK_ID), so those entries are skipped here: the tight
boundary is the AABB of the **non-air** entries only.

Why it matters: box clearing costs one air op per boundary cell that has no
pattern entry, and those ops are executed one per NPC per tick
(`TaskExecutionSystem` — "one side-effect per tick"). A boundary that frames the
terrain inflates that by the terrain's extent. This script is a hygiene fix, not
a cure: measured over the current catalog the total saving is ~162k cells, and
for the one oversized building (magic_academy) it is 1.4%.

Usage:
    python tighten_building_boundaries.py [--dry-run] [--all-entries] [DIR]
        default DIR = src/main/resources/data/wandscape/buildings
        --all-entries  frame every pattern entry (air markers included) instead
                       of only the real blocks — the boundary then still covers
                       the terrain extent, i.e. little or no change.
        --dry-run      report only, do not write.

Serialization safety: these files are NOT machine-canonical — some were written
by Gson (escaping `= < > & '` as \\uXXXX) and some by hand (e.g. `"first_free":true`
with no space after the colon next to `"id": "..."` with one), so re-serializing
a whole file would reformat megabytes of unrelated lines. Instead the boundary is
patched in place: only the six integers inside the `"boundary"` block are
rewritten, every other byte is preserved. After a rewrite the script re-reads the
file and asserts that only `boundary` changed and that every framed pattern entry
is still inside it.

Exit code 0 on success, non-zero on a malformed file or a validation failure.
"""
import argparse
import json
import re
import sys
from pathlib import Path

DEFAULT_DIR = Path("src/main/resources/data/wandscape/buildings")
AIR_ID = "minecraft:air"

BOUNDARY_RE = re.compile(r'"boundary"\s*:\s*\{.*?\}', re.S)
INT_RE = re.compile(r"-?\d+")


def patch_boundary_text(raw: str, lo, hi):
    """Rewrite only the six ints inside the `boundary` block. None when absent."""
    match = BOUNDARY_RE.search(raw)
    if match is None:
        return None
    body = match.group(0)
    spans = [m.span() for m in INT_RE.finditer(body)]
    if len(spans) != 6:
        raise RuntimeError(f"expected 6 ints in the boundary block, found {len(spans)}")
    values = list(lo) + list(hi)
    out = []
    prev = 0
    for (start, end), value in zip(spans, values):
        out.append(body[prev:start])
        out.append(str(value))
        prev = end
    out.append(body[prev:])
    return raw[:match.start()] + "".join(out) + raw[match.end():]


def framed_offsets(obj, all_entries: bool):
    """The offsets the boundary must still contain."""
    pattern = obj.get("pattern")
    if not pattern:
        return None
    if all_entries:
        return pattern
    palette = obj.get("palette") or []
    indices = obj.get("block_indices") or []
    if len(indices) != len(pattern):
        return None
    out = []
    for off, idx in zip(pattern, indices):
        if not isinstance(idx, int) or idx < 0 or idx >= len(palette):
            return None
        if palette[idx].split("[", 1)[0] != AIR_ID:
            out.append(off)
    return out


def aabb(offsets):
    return (
        min(o[0] for o in offsets), min(o[1] for o in offsets), min(o[2] for o in offsets),
        max(o[0] for o in offsets), max(o[1] for o in offsets), max(o[2] for o in offsets),
    )


def volume(lo, hi):
    return (hi[0] - lo[0] + 1) * (hi[1] - lo[1] + 1) * (hi[2] - lo[2] + 1)


def volume_of(boundary):
    mn, mx = boundary["min"], boundary["max"]
    return (mx[0] - mn[0] + 1) * (mx[1] - mn[1] + 1) * (mx[2] - mn[2] + 1)


def tighten(path: Path, all_entries: bool, dry_run: bool):
    """Return (changed, before_volume, after_volume) or raise on bad data."""
    raw = path.read_text(encoding="utf-8")
    obj = json.loads(raw)

    boundary = obj.get("boundary")
    if not isinstance(boundary, dict):
        return None  # no boundary to tighten

    framed = framed_offsets(obj, all_entries)
    if not framed:
        return None  # empty or malformed pattern

    solid = framed_offsets(obj, False)
    if not solid:
        return None  # no real blocks at all — leave it alone

    lo = (min(o[0] for o in solid), min(o[1] for o in solid), min(o[2] for o in solid))
    hi = (max(o[0] for o in solid), max(o[1] for o in solid), max(o[2] for o in solid))

    before = volume_of(boundary)
    after = volume(lo, hi)
    if (boundary["min"], boundary["max"]) == (list(lo), list(hi)):
        return (False, before, after)

    before_obj = json.loads(raw)
    text = patch_boundary_text(raw, lo, hi)
    if text is None:
        return None  # no textual boundary block (should not happen once parsed)

    if not dry_run:
        path.write_text(text, encoding="utf-8", newline="")
        check = json.loads(path.read_text(encoding="utf-8"))
        for key in set(before_obj) | set(check):
            if key == "boundary":
                continue
            if before_obj.get(key) != check.get(key):
                raise RuntimeError(f"validation failed: field '{key}' changed")
        chk = check["boundary"]
        inside = all(
            chk["min"][a] <= o[a] <= chk["max"][a]
            for o in framed for a in range(3))
        if not inside:
            raise RuntimeError("validation failed: a framed pattern entry fell outside")

    return (True, before, after)


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("dir", nargs="?", default=str(DEFAULT_DIR))
    ap.add_argument("--dry-run", action="store_true", help="report only, write nothing")
    ap.add_argument("--all-entries", action="store_true",
                    help="frame every pattern entry (air markers included)")
    ap.add_argument("--exclude", action="append", default=[],
                    help="skip files whose stem matches (repeatable); use while a "
                         "building is still being hand-edited, since its tight "
                         "boundary depends on the final set of real blocks")
    args = ap.parse_args(argv)

    root = Path(args.dir)
    if not root.is_dir():
        print(f"not a directory: {root}", file=sys.stderr)
        return 2

    changed = skipped = untouched = 0
    saved = 0
    errors = []
    for path in sorted(root.rglob("*.json")):
        if path.stem in args.exclude:
            skipped += 1
            continue
        try:
            result = tighten(path, args.all_entries, args.dry_run)
        except Exception as exc:                       # noqa: BLE001 - report and continue
            errors.append((path, exc))
            continue
        if result is None:
            skipped += 1
            continue
        did, before, after = result
        if not did:
            untouched += 1
            continue
        changed += 1
        saved += before - after
        rel = path.relative_to(root.parent.parent.parent) if path.is_absolute() else path
        print(f"{'DRY ' if args.dry_run else ''}{rel}: {before:,} -> {after:,} "
              f"(-{before - after:,}, {100.0 * after / before:.1f}%)")

    print(f"\nchanged={changed}  untouched={untouched}  skipped={skipped}  "
          f"cells saved={saved:,}")
    for path, exc in errors:
        print(f"ERROR {path}: {exc}", file=sys.stderr)
    return 1 if errors else 0


if __name__ == "__main__":
    sys.exit(main())
