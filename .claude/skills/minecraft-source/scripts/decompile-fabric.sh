#!/usr/bin/env bash
# Extract and display a Minecraft source file using Fabric Loom's generated sources jar.
# The sources jar already contains decompiled Java with Yarn (human-readable) names.
#
# Usage: decompile.sh <class-name>
# Examples:
#   decompile.sh net.minecraft.util.ActionResult
#   decompile.sh net/minecraft/util/ActionResult
#   decompile.sh ActionResult
#   decompile.sh ActionResult.Success       (inner class, extracts outer file)

set -euo pipefail

CLASS_INPUT="$1"

# ---------- 1. Find project root ----------
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
    echo "ERROR: Not in a Fabric mod project." >&2
    exit 1
fi

# ---------- 2. Find the Yarn-mapped sources jar ----------
# Location: .gradle/loom-cache/minecraftMaven/net/minecraft/minecraft-merged-*/
#           .../<version>-net.fabricmc.yarn.<version>-v2/*-sources.jar
LOOM_MC_DIR="$PROJECT_ROOT/.gradle/loom-cache/minecraftMaven/net/minecraft"

if [[ ! -d "$LOOM_MC_DIR" ]]; then
    echo "ERROR: Loom cache not found at: $LOOM_MC_DIR" >&2
    echo "       Run './gradlew build' or './gradlew genSources' first." >&2
    exit 1
fi

MC_VERSION=$(grep -E '^minecraft_version=' "$PROJECT_ROOT/gradle.properties" | cut -d= -f2 | tr -d '[:space:]')
# Match jar whose path contains the MC version (e.g. "1.21.10-net.fabricmc.yarn...")
SOURCES_JAR=$(find "$LOOM_MC_DIR" -name "*-sources.jar" 2>/dev/null | grep -F "${MC_VERSION}-" | head -1)
if [[ -z "$SOURCES_JAR" ]]; then
    # Fallback: take newest jar if exact version match fails
    SOURCES_JAR=$(find "$LOOM_MC_DIR" -name "*-sources.jar" 2>/dev/null | head -1)
fi

if [[ -z "$SOURCES_JAR" ]]; then
    echo "ERROR: No sources jar found in $LOOM_MC_DIR" >&2
    echo "       Run './gradlew genSources' first to generate decompiled sources." >&2
    exit 1
fi

# ---------- 3. Convert class name to file path ----------
# Input:  net.minecraft.util.ActionResult, net/minecraft/util/ActionResult, ActionResult
# Output: net/minecraft/util/ActionResult.java
class_to_path() {
    local name="$1"
    # Strip inner class suffix ($Foo or .Foo) for file lookup (inner classes are in the same file)
    name="${name%%\$*}"
    name="${name%%.*}"          # if using dot notation for inner class
    # Convert dots to slashes: net.minecraft.util.ActionResult -> net/minecraft/util/ActionResult
    name="${name//.//}"
    echo "${name}.java"
}

# Short name search: find full path from jar contents
if [[ "$CLASS_INPUT" != *"."* && "$CLASS_INPUT" != *"/"* && "$CLASS_INPUT" != *'$'* ]]; then
    SHORT_NAME="$CLASS_INPUT"
    MATCHES=$(jar tf "$SOURCES_JAR" 2>/dev/null | grep -i "/${SHORT_NAME}\.java" || true)
    MATCH_COUNT=$(echo "$MATCHES" | wc -l | tr -d '[:space:]')
    if [[ "$MATCH_COUNT" -eq 0 ]]; then
        echo "ERROR: No source file found with name '${SHORT_NAME}.java'" >&2
        exit 1
    elif [[ "$MATCH_COUNT" -gt 1 ]]; then
        echo "Multiple matches for '${SHORT_NAME}':" >&2
        echo "$MATCHES" | while read -r line; do echo "  $line" >&2; done
        echo "Please re-run with a full class name." >&2
        exit 1
    fi
    FILE_PATH="$MATCHES"
else
    FILE_PATH=$(class_to_path "$CLASS_INPUT")
    # If result has no package path (e.g. inner class "ActionResult.Success" -> "ActionResult.java"),
    # search the jar for the full path
    if [[ "$FILE_PATH" != *"/"* ]]; then
        SHORT_NAME="${FILE_PATH%.java}"
        MATCHES=$(jar tf "$SOURCES_JAR" 2>/dev/null | grep -i "/${SHORT_NAME}\.java" || true)
        MATCH_COUNT=$(echo "$MATCHES" | wc -l | tr -d '[:space:]')
        if [[ "$MATCH_COUNT" -ge 1 ]]; then
            FILE_PATH=$(echo "$MATCHES" | head -1)
        fi
    fi
fi

# Verify file exists in jar (avoid grep -q: SIGPIPE + pipefail = false negative)
if ! jar tf "$SOURCES_JAR" 2>/dev/null | grep "^${FILE_PATH}$" >/dev/null; then
    # Try fallback: maybe the user provided dot notation for inner class
    # (class_to_path already strips inner class, but let's try more aggressively)
    BASE_NAME="${CLASS_INPUT##*.}"
    MATCHES=$(jar tf "$SOURCES_JAR" 2>/dev/null | grep -i "/${BASE_NAME}\.java" | grep -v '[$]' || true)
    if [[ -n "$MATCHES" ]]; then
        FILE_PATH=$(echo "$MATCHES" | head -1)
    else
        echo "ERROR: '${FILE_PATH}' not found in sources jar." >&2
        echo "       Check the class name." >&2
        exit 1
    fi
fi

# ---------- 4. Extract and print ----------
echo "--- Minecraft ${MC_VERSION} ---"
echo "Source:  ${FILE_PATH}"
echo "Jar:     ${SOURCES_JAR}"
echo ""

unzip -p "$SOURCES_JAR" "$FILE_PATH" 2>/dev/null || {
    echo "ERROR: Failed to extract ${FILE_PATH} from sources jar." >&2
    exit 1
}
