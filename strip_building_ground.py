#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Remove grass_block / dirt from building schematics, turning those cells to air.

Building JSON layout (docs/data-formats.md): "pattern" is the list of [x,y,z]
offsets, "block_indices[i]" indexes into "palette" for the block at pattern[i].
At build time the build:clear_and_build blueprint clears the whole boundary
AABB to air first, then places pattern blocks. Dropping a pattern entry (and
its palette reference) therefore makes that position air — no "air" entry is
ever written into the palette.

Per file this script:
  * drops every pattern entry whose palette block is minecraft:grass_block or
    minecraft:dirt (block-state suffix stripped, so [snowy=true] etc. match);
  * drops the matching block_indices entries.

The palette is left untouched. Its grass/dirt strings become unreferenced and
inert — keeping them means every surviving block_indices value stays the same,
so the git diff stays small and focused instead of renumbering thousands of
index lines. (The intent is to make those cells air, not to rewrite the block
catalog; positions that were grass/dirt become air because the build blueprint
clears the whole boundary AABB first.)

Usage:
    python strip_building_ground.py [DIR]
        default DIR = src/main/resources/data/wandscape/buildings
    python strip_building_ground.py --dry-run [DIR]   # report only, no writes

Exit code 0 on success (files without grass/dirt are skipped), non-zero on a
malformed file or a validation failure.
"""
import argparse
import json
import sys
from pathlib import Path

TARGET_BASES = frozenset({"minecraft:grass_block", "minecraft:dirt"})
DEFAULT_DIR = Path("src/main/resources/data/wandscape/buildings")


def base_name(block_state):
    """minecraft:grass_block[snowy=true] -> minecraft:grass_block"""
    return block_state.split("[", 1)[0]


# ── minimal JSON-aware text scanner (quoted strings may contain '[' ']' …) ──

def skip_string(s, i):
    """s[i] is a double quote; return index just past the closing quote."""
    i += 1
    n = len(s)
    while i < n:
        c = s[i]
        if c == "\\":
            i += 2
            continue
        if c == '"':
            return i + 1
        i += 1
    raise ValueError("unterminated string")


def skip_value(s, i):
    """s[i] starts a JSON value; return index just past its end."""
    c = s[i]
    if c == '"':
        return skip_string(s, i)
    if c in "[{":
        close = "]" if c == "[" else "}"
        depth = 1
        i += 1
        n = len(s)
        while i < n:
            ch = s[i]
            if ch == '"':
                i = skip_string(s, i)
            elif ch == c:
                depth += 1
                i += 1
            elif ch == close:
                depth -= 1
                if depth == 0:
                    return i + 1
                i += 1
            else:
                i += 1
        raise ValueError("unbalanced %s" % c)
    n = len(s)
    while i < n and not s[i].isspace() and s[i] not in ",]}":
        i += 1
    return i


def find_matching_bracket(s, open_idx):
    """open_idx points at '['; return index of the matching ']'."""
    depth = 1
    i = open_idx + 1
    n = len(s)
    while i < n:
        c = s[i]
        if c == '"':
            i = skip_string(s, i)
        elif c == "[":
            depth += 1
            i += 1
        elif c == "]":
            depth -= 1
            if depth == 0:
                return i
            i += 1
        else:
            i += 1
    raise ValueError("unbalanced '['")


def top_level_key_open(s, key):
    """Index of the '[' opening the top-level array stored under \"key\"."""
    needle = '"%s":' % key
    start = 0
    while True:
        pos = s.find(needle, start)
        if pos == -1:
            raise ValueError('no "%s" array found' % key)
        j = pos + len(needle)
        while j < len(s) and s[j].isspace():
            j += 1
        if j < len(s) and s[j] == "[":
            return j
        start = pos + 1  # same key nested somewhere else — keep looking


def split_array(s, open_idx):
    """Split an array body into top-level elements.

    Returns (elements, tail, close_idx):
      * elements[i] = (pre, value) — value is one top-level element's raw text;
        pre is the text between the previous value's end and this value's start,
        so every pre after the first carries that element's separating comma.
      * tail = whitespace between the last element and the closing ']'.
    """
    close_idx = find_matching_bracket(s, open_idx)
    elements = []
    prev_end = open_idx + 1
    i = open_idx + 1
    while i < close_idx:
        while i < close_idx and s[i].isspace():
            i += 1
        if i >= close_idx:
            break
        pre = s[prev_end:i]
        value_end = skip_value(s, i)
        elements.append((pre, s[i:value_end]))
        prev_end = value_end
        i = value_end
        while i < close_idx and s[i].isspace():
            i += 1
        if i < close_idx and s[i] == ",":
            i += 1  # that comma becomes the next element's pre
    return elements, s[prev_end:close_idx], close_idx


# ── transform ─────────────────────────────────────────────────────────

def rebuild_array_body(elements, tail, keep, value_of):
    """Join kept original elements back into an array body.

    keep = sorted original element indexes to retain; value_of(i) returns the
    raw text to emit for element i (identity for pattern/palette, renumbered
    int for block_indices). pre of each non-first element carries its leading
    comma; the first kept element must not start with one.
    """
    parts = []
    for k, i in enumerate(sorted(keep)):
        pre, _ = elements[i]
        if k == 0 and pre.startswith(","):
            pre = pre[1:]
        parts.append(pre + value_of(i))
    return "".join(parts) + tail


def transform_text(text):
    """Return (new_text, report) — report is None when nothing changes."""
    data = json.loads(text)
    pattern = data["pattern"]
    palette = data["palette"]
    indices = data["block_indices"]
    if len(pattern) != len(indices):
        raise ValueError("pattern.size()=%d != block_indices.size()=%d"
                         % (len(pattern), len(indices)))
    for i in indices:
        if not 0 <= i < len(palette):
            raise ValueError("block_indices value %d out of palette range [0,%d)"
                             % (i, len(palette)))

    remove = {i for i, idx in enumerate(indices) if base_name(palette[idx]) in TARGET_BASES}
    if not remove:
        return None, None
    keep_pattern = [i for i in range(len(pattern)) if i not in remove]

    # ── splice pattern + block_indices bodies ──
    # Both spans are computed against the untouched original text (they do not
    # overlap), then applied from the higher offset downward so earlier
    # character positions stay valid. Palette is not touched.
    spans = []
    for key in ("pattern", "block_indices"):
        open_idx = top_level_key_open(text, key)
        elements, tail, close_idx = split_array(text, open_idx)
        if key == "block_indices":
            # keep the ORIGINAL index values (palette is unchanged, no renumber)
            def value_of(i, _idx=indices):
                return str(_idx[i])
        else:
            def value_of(i, _el=elements):
                return _el[i][1]
        spans.append((open_idx, close_idx,
                      "[" + rebuild_array_body(elements, tail, keep_pattern, value_of) + "]"))
    spans.sort(key=lambda t: t[0], reverse=True)
    out = text
    for open_idx, close_idx, replacement in spans:
        out = out[:open_idx] + replacement + out[close_idx + 1:]

    report = {"removed": len(remove), "old_pattern": len(pattern),
              "new_pattern": len(keep_pattern)}
    return out, report


# ── validation ────────────────────────────────────────────────────────

def validate(text, out_text):
    """Reference-model + byte-level checks. Raise ValueError on mismatch."""
    old = json.loads(text)
    new = json.loads(out_text)

    palette = old["palette"]
    indices = old["block_indices"]
    keep_pattern = [i for i, idx in enumerate(indices)
                    if base_name(palette[idx]) not in TARGET_BASES]

    ref = dict(old)
    ref["pattern"] = [old["pattern"][i] for i in keep_pattern]
    ref["block_indices"] = [indices[i] for i in keep_pattern]
    # palette intentionally unchanged (dead grass/dirt entries stay)
    if new != ref:
        raise ValueError("semantic mismatch vs reference model")

    if new["palette"] != palette:
        raise ValueError("palette must not change")
    if len(new["pattern"]) != len(new["block_indices"]):
        raise ValueError("output pattern/block_indices sizes differ")
    for idx in new["block_indices"]:
        if not 0 <= idx < len(new["palette"]):
            raise ValueError("block_indices value %d out of range" % idx)
    if len(new["pattern"]) != len(keep_pattern):
        raise ValueError("unexpected pattern size after edit")

    # byte-level: everything outside pattern and block_indices (including the
    # palette) must be unchanged
    def mask(s):
        m = s
        for key in ("pattern", "block_indices"):
            o = top_level_key_open(m, key)
            c = find_matching_bracket(m, o)
            m = m[:o] + "@@" + key + "@@" + m[c + 1:]
        return m
    if mask(text) != mask(out_text):
        raise ValueError("bytes outside pattern/block_indices changed")


def main(argv):
    ap = argparse.ArgumentParser(
        description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("dir", nargs="?", type=Path, default=DEFAULT_DIR,
                    help="directory to scan recursively (default: %(default)s)")
    ap.add_argument("--dry-run", action="store_true",
                    help="report only, write nothing")
    args = ap.parse_args(argv)

    if not args.dir.is_dir():
        print("no such directory: %s" % args.dir, file=sys.stderr)
        return 2
    files = sorted(args.dir.rglob("*.json"))
    if not files:
        print("no .json files under %s" % args.dir, file=sys.stderr)
        return 2

    changed = []
    skipped = 0
    failed = []
    total_removed = 0
    for f in files:
        text = f.read_text(encoding="utf-8", newline="")  # keep CRLF intact
        try:
            out, report = transform_text(text)
            if out is not None:
                validate(text, out)
        except Exception as exc:  # noqa: BLE001 - surface per-file failures
            failed.append((f, exc))
            continue
        if out is None:
            skipped += 1
            continue
        total_removed += report["removed"]
        changed.append((f, report))
        if not args.dry_run:
            with f.open("w", encoding="utf-8", newline="") as fh:
                fh.write(out)

    for f, r in changed:
        print("%s  removed=%d  pattern %d->%d%s"
              % (f, r["removed"], r["old_pattern"], r["new_pattern"],
                 "  [dry-run]" if args.dry_run else ""))
    for f, exc in failed:
        print("FAIL %s: %s" % (f, exc), file=sys.stderr)

    print("scanned %d files, changed %d, skipped %d, failed %d, positions cleared %d"
          % (len(files), len(changed), skipped, len(failed), total_removed))
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
