#!/usr/bin/env python3
"""
Dead code diagnostic for the Wandscape Minecraft mod.

For each class in src/main, checks whether any OTHER source file (main only)
mentions its simple class name. Classes never mentioned outside their own
definition, their own package-internal files, or test files are reported.

Also known patterns that keep things alive:
  - WandscapeApis.setXxxApi() → XxxImpl
  - world.addSystem(new Xxx()) → Xxx
  - event handler registration
  - DeferredRegister / @ObjectHolder

Usage: python tools/dead_code_scanner.py
Output: tools/dead_code_report.md
"""

import os
import re
import json
from pathlib import Path
from collections import defaultdict

SRC_MAIN = Path("src/main/java")
SRC_TEST = Path("src/test/java")
OUTPUT = Path("tools/dead_code_report.md")

# ── Phase 1: collect all classes ──

class ClassInfo:
    __slots__ = ('pkg', 'simple', 'fqn', 'file', 'in_test', 'text')
    def __init__(self, pkg: str, simple: str, file: Path, in_test: bool):
        self.pkg = pkg
        self.simple = simple
        self.fqn = f"{pkg}.{simple}"
        self.file = file
        self.in_test = in_test
        self.text = ""

def collect_classes(root: Path, in_test: bool) -> dict[str, ClassInfo]:
    """Returns dict fqn → ClassInfo for all .java files under root."""
    result = {}
    for f in root.rglob("*.java"):
        rel = f.relative_to(root).with_suffix('')
        pkg = str(rel.parent).replace(os.sep, '.')
        simple = rel.name
        ci = ClassInfo(pkg, simple, f, in_test)
        try:
            ci.text = f.read_text(encoding='utf-8', errors='replace')
        except Exception:
            ci.text = ""
        result[ci.fqn] = ci
    return result

# ── Known lifecycle patterns ──

# Special files that are alive by definition
ENTRY_POINTS = {
    "com.wsteam.wandscape.Wandscape",
    "com.wsteam.wandscape.WandscapeClient",
    "com.wsteam.wandscape.Config",
}

# Patterns that keep an impl alive
API_IMPL_SET_PATTERN = re.compile(r'WandscapeApis\.set(\w+)Api\(new (\w+)Impl\(\)\)')
SYSTEM_ADD_PATTERN = re.compile(r'world\.addSystem\(new (\w+)\(\)\)')
OPEXEC_REGISTER_PATTERN = re.compile(r'opExecutors\.register\(new (\w+)\(')
REGISTRY_PATTERN = re.compile(r'(register|DeferredRegister|REGISTRY|@ObjectHolder)')
EVENT_SUBSCRIBE_PATTERN = re.compile(r'@SubscribeEvent')
IMPL_PATTERN = re.compile(r'\bimplements\s+\w*(\w+Api)\b')

def find_api_impl_links(text: str, this_pkg: str) -> set[str]:
    """Find implementation classes referenced via locator patterns."""
    fqns = set()
    # WandscapeApis.setXxxApi(new XxxImpl())
    for m in API_IMPL_SET_PATTERN.finditer(text):
        fqns.add(f"com.wsteam.wandscape.{m.group(2).lower()}.internal.{m.group(2)}Impl")
    # world.addSystem(new Xxx())
    for m in SYSTEM_ADD_PATTERN.finditer(text):
        fqns.add(f"{this_pkg}.{m.group(1)}")
    # opExecutors.register(new Xxx())
    for m in OPEXEC_REGISTER_PATTERN.finditer(text):
        fqns.add(f"{this_pkg}.{m.group(1)}")
    return fqns

# ── Phase 2: reference search ──

# Common words that happen to look like class names
IGNORE_SIMPLE_NAMES = {
    "Main", "Builder", "Test", "Entry", "Mode", "Type", "Types",
    "Result", "Event", "State", "Status", "Log", "Stage", "Phase",
    "Action", "Node", "Edge", "Plan", "View", "Config", "Configs",
    "Screen", "Panel", "Overlay", "Model", "Scene", "Key", "Keys",
    "Utils", "Util", "Helper", "Helpers", "Constants", "Options",
    "Flags", "Feature", "Features", "Layout", "Style", "Color", "Colors",
    "ClientHandler", "Render", "Texture", "Font", "Animation",
    "Background", "Border", "Size", "Point", "Vector", "Rect",
    "Circle", "Bar", "Button", "List", "Grid", "Box", "Group",
    "Widget", "Input", "Menu", "Tab", "Row", "Column", "Cell",
    "Section", "Block", "Zone", "Area", "Region", "Slot",
    "Source", "Sink", "Pipe", "Buffer", "Pool", "Queue", "Stack",
    "Path", "Route", "Edge", "Node", "Data", "Info", "Context",
    "Manager", "Admin", "Controller", "Proxy", "Service", "Handler",
    "Listener", "Observer", "Broadcaster", "Scheduler", "Monitor",
    "Mapper", "Builder", "Factory", "Provider", "Supplier", "Config",
}

def is_likely_class_ref(name: str, context: str) -> bool:
    """Quick check if the name as a word appears in context text."""
    # Match as a standalone word with word boundaries
    pattern = re.compile(r'(?<![a-zA-Z])\b' + re.escape(name) + r'\b(?![a-zA-Z])')
    return bool(pattern.search(context))

# ── Main analysis ──

def analyze():
    mains = collect_classes(SRC_MAIN, in_test=False)
    tests = collect_classes(SRC_TEST, in_test=True)
    all_classes = {**mains, **tests}

    # Step 1: Seed alive set with entry points
    alive: set[str] = set(ENTRY_POINTS)

    # Step 2: Find all "new Xxx()" creations within main code
    new_pattern = re.compile(r'new\s+(\w+)\s*\(')
    for fqn, ci in mains.items():
        for m in new_pattern.finditer(ci.text):
            name = m.group(1)
            # Could be this package, or an imported class
            candidates = [f"{ci.pkg}.{name}", name]
            # Check project-wide
            if name in {x.simple for x in mains.values()}:
                # Add all matching mains
                for cfqn, cci in mains.items():
                    if cci.simple == name:
                        alive.add(cfqn)

    # Step 3: Find all "Xxx.class" references
    class_literal_pattern = re.compile(r'(\w+)\.class')
    for fqn, ci in mains.items():
        for m in class_literal_pattern.finditer(ci.text):
            name = m.group(1)
            if name in {x.simple for x in mains.values()}:
                for cfqn, cci in mains.items():
                    if cci.simple == name:
                        alive.add(cfqn)

    # Step 4: Find all imports, resolve them
    import_pattern = re.compile(r'^import\s+(?:static\s+)?(com\.wsteam\.wandscape\.\S+);', re.MULTILINE)
    for fqn, ci in mains.items():
        for m in import_pattern.finditer(ci.text):
            imp_fqn = m.group(1).replace('$', '.')
            # Remove method name from static imports
            if '.' in imp_fqn:
                last = imp_fqn.split('.')[-1]
                if last[0].islower():
                    imp_fqn = '.'.join(imp_fqn.split('.')[:-1])
            if imp_fqn in mains:
                alive.add(imp_fqn)

    # Step 5: Find API impl patterns
    for fqn, ci in mains.items():
        linked = find_api_impl_links(ci.text, ci.pkg)
        alive.update(linked)

    # Step 6: For each class not yet alive, check if its simple name
    # appears in any OTHER main file as a word
    # (catches generic references not caught by imports/new patterns)
    for fqn, ci in mains.items():
        if fqn in alive:
            continue
        for ofqn, oci in mains.items():
            if ofqn == fqn:
                continue
            if is_likely_class_ref(ci.simple, oci.text):
                # Must also verify import exists or same package
                if oci.pkg == ci.pkg:
                    alive.add(fqn)
                    break
                # Check if oci imports this specific FQN
                import_check = re.compile(
                    r'^import\s+(?:static\s+)?' + re.escape(ci.fqn.replace('$', '.')) + r'\s*;',
                    re.MULTILINE
                )
                if import_check.search(oci.text):
                    alive.add(fqn)
                    break
                wild_import = re.compile(
                    r'^import\s+' + re.escape('.'.join(ci.fqn.split('.')[:-1])) + r'\.\*\s*;',
                    re.MULTILINE
                )
                if wild_import.search(oci.text):
                    alive.add(fqn)
                    break

    # Step 7: Classes only mentioned in tests
    only_in_test: set[str] = set()
    # Step 8: Classes never mentioned anywhere outside own file
    never_mentioned: set[str] = set()

    for fqn, ci in mains.items():
        if fqn in alive:
            continue
        # Check if mentioned in OTHER main files
        mentioned_by_main = False
        for ofqn, oci in mains.items():
            if ofqn == fqn:
                continue
            if is_likely_class_ref(ci.simple, oci.text):
                # Same-package or imported?
                if oci.pkg == ci.pkg:
                    mentioned_by_main = True
                    break
                imp_check = re.compile(
                    r'^import\s+(?:static\s+)?' + re.escape(ci.fqn.replace('$', '.')) + r'\s*;',
                    re.MULTILINE
                )
                if imp_check.search(oci.text):
                    mentioned_by_main = True
                    break
                wc_check = re.compile(
                    r'^import\s+' + re.escape('.'.join(ci.fqn.split('.')[:-1])) + r'\.\*\s*;',
                    re.MULTILINE
                )
                if wc_check.search(oci.text):
                    mentioned_by_main = True
                    break

        if mentioned_by_main:
            # Skip - mentioned in main
            if True:
                pass
            continue

        # Check if mentioned in tests
        mentioned_by_test = False
        for ofqn, oci in tests.items():
            if is_likely_class_ref(ci.simple, oci.text):
                mentioned_by_test = True
                break

        if mentioned_by_test:
            only_in_test.add(fqn)
        else:
            never_mentioned.add(fqn)

    # ── Format output ──
    from datetime import datetime

    lines = ["# Dead Code Scan Report",
             f"Generated: {datetime.now().strftime('%Y-%m-%d %H:%M')}",
             "",
             f"Total main classes: {len(mains)}",
             f"Total test classes: {len(tests)}",
             f"Marked alive: {len(alive)}",
             "",
             ]

    if never_mentioned:
        lines.append("## NEVER REFERENCED outside own file")
        lines.append("")
        lines.append("| # | Package | Class | File |")
        lines.append("|---|---------|-------|------|")
        for i, fqn in enumerate(sorted(never_mentioned), 1):
            ci = mains[fqn]
            fp = ci.file.relative_to(Path("src"))
            lines.append(f"| {i} | `{ci.pkg}` | `{ci.simple}` | `{fp}` |")
        lines.append("")

    if only_in_test:
        lines.append("## ONLY referenced in test files")
        lines.append("")
        lines.append("| # | Package | Class | File |")
        lines.append("|---|---------|-------|------|")
        for i, fqn in enumerate(sorted(only_in_test), 1):
            ci = mains[fqn]
            fp = ci.file.relative_to(Path("src"))
            lines.append(f"| {i} | `{ci.pkg}` | `{ci.simple}` | `{fp}` |")
        lines.append("")

    lines.append(f"---")
    lines.append(f"Alive: {len(alive)}, Only-in-tests: {len(only_in_test)}, Never-referenced: {len(never_mentioned)}")

    text = '\n'.join(lines)
    text_clean = text.encode('utf-8', errors='replace').decode('utf-8', errors='replace')
    OUTPUT.write_text(text, encoding='utf-8')
    print(text_clean)

if __name__ == "__main__":
    analyze()
