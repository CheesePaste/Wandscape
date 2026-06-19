#!/usr/bin/env bash
# Auto-detect Fabric vs NeoForge and dispatch to the correct decompile script.
#
# Usage: decompile.sh <class-name>

set -euo pipefail

CLASS_INPUT="$1"
SKILL_DIR="$(cd "$(dirname "$0")" && pwd)"

# ---------- Find project root ----------
find_project_root() {
    local dir
    dir="$(pwd)"
    while [[ "$dir" != "/" && "$dir" != "" ]]; do
        if [[ -f "$dir/gradle.properties" ]]; then
            echo "$dir"
            return 0
        fi
        dir="$(dirname "$dir")"
    done
    echo ""
}

PROJECT_ROOT="$(find_project_root)"
if [[ -z "$PROJECT_ROOT" ]]; then
    echo "ERROR: Not in a Fabric or NeoForge mod project." >&2
    exit 1
fi

# ---------- Detect project type ----------
# NeoForge: build/moddev/artifacts/ exists with neoforge-*.jar
# Fabric:   .gradle/loom-cache/minecraftMaven/ exists
if [[ -d "$PROJECT_ROOT/build/moddev/artifacts" ]] && \
   ls "$PROJECT_ROOT/build/moddev/artifacts"/neoforge-*.jar >/dev/null 2>&1; then
    exec bash "$SKILL_DIR/decompile-neoforge.sh" "$CLASS_INPUT"
elif [[ -d "$PROJECT_ROOT/.gradle/loom-cache/minecraftMaven" ]]; then
    exec bash "$SKILL_DIR/decompile-fabric.sh" "$CLASS_INPUT"
else
    echo "ERROR: Cannot detect project type." >&2
    echo "       Fabric:   .gradle/loom-cache/minecraftMaven/ not found" >&2
    echo "       NeoForge: build/moddev/artifacts/neoforge-*.jar not found" >&2
    echo "       Run './gradlew build' or './gradlew genSources' first." >&2
    exit 1
fi
