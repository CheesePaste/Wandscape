#!/usr/bin/env bash
# Extract and display a Minecraft or NeoForge source file from a NeoForge MDG project.
#
# Source jar hierarchy (searched in priority order):
#   1. build/moddev/artifacts/neoforge-{version}-sources.jar   — vanilla MC (Mojang)
#   2. build/moddev/artifacts/neoforge-{version}-merged.jar    — vanilla + NeoForge patches
#   3. ~/.gradle/caches/.../net.neoforged/**/*-sources.jar     — NeoForge library sources
#      (event bus, registries, capabilities, coremods, etc.)
#
# Usage: decompile.sh <class-name>
# Examples:
#   decompile.sh net.minecraft.world.item.ItemStack
#   decompile.sh net/minecraft/world/item/ItemStack
#   decompile.sh ItemStack
#   decompile.sh ItemStack.Builder                     (inner class, extracts outer file)
#   decompile.sh net.neoforged.neoforge.event.Event
#   decompile.sh net.neoforged.bus.api.Event            (event bus base class)

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
    echo "ERROR: Not in a NeoForge mod project." >&2
    exit 1
fi

# ---------- 2. Find the jars ----------
ARTIFACTS_DIR="$PROJECT_ROOT/build/moddev/artifacts"

if [[ ! -d "$ARTIFACTS_DIR" ]]; then
    echo "ERROR: Artifacts directory not found at: $ARTIFACTS_DIR" >&2
    echo "       Run './gradlew build' or import the project in your IDE first." >&2
    exit 1
fi

NEO_VERSION=$(grep -E '^neo_version=' "$PROJECT_ROOT/gradle.properties" | cut -d= -f2 | tr -d '[:space:]' || true)

SOURCES_JAR="$ARTIFACTS_DIR/neoforge-${NEO_VERSION}-sources.jar"
MERGED_JAR="$ARTIFACTS_DIR/neoforge-${NEO_VERSION}-merged.jar"

# Fallback: find any versioned jar if exact version not found
if [[ ! -f "$SOURCES_JAR" ]]; then
    SOURCES_JAR=$(find "$ARTIFACTS_DIR" -maxdepth 1 -name "neoforge-*-sources.jar" 2>/dev/null | head -1 || echo "")
fi
if [[ ! -f "$MERGED_JAR" ]]; then
    MERGED_JAR=$(find "$ARTIFACTS_DIR" -maxdepth 1 -name "neoforge-*-merged.jar" 2>/dev/null | head -1 || echo "")
fi

# ---------- 3. Collect NeoForge library source jars from Gradle cache ----------
# MDG artifacts only contain ~950 NeoForge .java files (the patched-in classes).
# The full NeoForge library source (event bus, registries, capabilities, coremods,
# FML, etc.) lives in separate Gradle dependency source jars. We collect them all
# so any net.neoforged.* class can be read.
GRADLE_CACHE_DIR="$HOME/.gradle/caches/modules-2/files-2.1/net.neoforged"
GRADLE_NEOFORGE_JARS=()
if [[ -d "$GRADLE_CACHE_DIR" ]]; then
    while IFS= read -r jar; do
        [[ -n "$jar" ]] && GRADLE_NEOFORGE_JARS+=("$jar")
    done < <(find "$GRADLE_CACHE_DIR" -name "*-sources.jar" 2>/dev/null)
fi

# ---------- 4. Jar selection & search helpers ----------

class_in_jar() {
    local jar="$1"
    local path="$2"
    local rc
    # pipefail + SIGPIPE: when grep finds a match and exits, unzip gets SIGPIPE
    # (exit 141). With pipefail the pipeline fails despite grep succeeding.
    # Disable pipefail so grep exit 0 = pipeline success, grep exit 1 = not found.
    set +o pipefail
    unzip -Z1 "$jar" 2>/dev/null | grep -xF "$path" >/dev/null
    rc=$?
    if [[ $rc -eq 0 ]]; then set -o pipefail; return 0; fi
    unzip -Z1 "$jar" 2>/dev/null | grep -xi "^${path}$" >/dev/null
    rc=$?
    set -o pipefail
    if [[ $rc -eq 0 ]]; then return 0; fi
    return 1
}

# Search a list of jars for a file; prints the first jar containing it.
find_file_in_jars() {
    local path="$1"
    shift
    for jar in "$@"; do
        if [[ -n "$jar" && -f "$jar" ]] && class_in_jar "$jar" "$path"; then
            echo "$jar"
            return 0
        fi
    done
    return 1
}

# Convert class name → file path
class_to_path() {
    local name="$1"
    # Strip $Inner notation
    name="${name%%\$*}"
    # Strip .Inner notation: if the last dot-separated component starts with
    # uppercase and there are no other dots before it (i.e., it's
    # "Outer.Inner" not "pkg.Outer"), treat it as an inner class reference.
    local before="${name%.*}"
    local after="${name##*.}"
    if [[ "$before" != *.* ]] && [[ "$after" != "$name" ]] && [[ "${after:0:1}" == [[:upper:]] ]]; then
        name="$before"
    fi
    name="${name//.//}"
    echo "${name}.java"
}

# ---------- 3b. Project source search ----------
find_in_project_src() {
    local path="$1"
    local full
    full=$(find "$PROJECT_ROOT/src" -path "*/$path" 2>/dev/null | head -1 || true)
    if [[ -n "$full" && -f "$full" ]]; then
        echo "$full"
        return 0
    fi
    # Try short name: just the basename
    local basename="${path##*/}"
    if [[ "$basename" != "$path" ]]; then
        full=$(find "$PROJECT_ROOT/src" -name "$basename" 2>/dev/null | head -1 || true)
        if [[ -n "$full" && -f "$full" ]]; then
            echo "$full"
            return 0
        fi
    fi
    return 1
}

# ---------- 3c. Source caching ----------
CACHE_DIR="$(cd "$(dirname "$0")" && pwd)/cache"
mkdir -p "$CACHE_DIR"
MAX_CACHE_FILES=50

cache_key() {
    local input="$1"
    if command -v md5sum &>/dev/null; then
        echo -n "$input" | md5sum | cut -d' ' -f1
    elif command -v md5 &>/dev/null; then
        echo -n "$input" | md5 -r | cut -d' ' -f1
    else
        echo "$input" | sha256sum | cut -d' ' -f1
    fi
}

cache_get() {
    local key="$1"
    local cache_file="$CACHE_DIR/${key}.java"
    if [[ -f "$cache_file" ]]; then
        # Touch to update LRU time
        touch "$cache_file" 2>/dev/null || true
        cat "$cache_file"
        return 0
    fi
    return 1
}

cache_put() {
    local key="$1"
    local content="$2"
    # Enforce limit
    local count
    count=$(ls -1 "$CACHE_DIR" 2>/dev/null | wc -l || true)
    if [[ "$count" -ge "$MAX_CACHE_FILES" ]]; then
        # Remove oldest files
        ls -t "$CACHE_DIR" | tail -n +"$((MAX_CACHE_FILES / 2))" | while read -r f; do
            rm -f "$CACHE_DIR/$f"
        done 2>/dev/null
    fi
    echo "$content" > "$CACHE_DIR/${key}.java"
}

# ---------- 3d. Fuzzy name suggestion ----------
suggest_similar_classes() {
    local jar="$1"
    local name="$2"
    local basename="${name%.java}"
    unzip -l "$jar" 2>/dev/null | grep -i "/${basename}" | head -10 | awk '{$1=$2=$3=""; print substr($0,4)}' || true
}

find_in_jar() {
    local jar="$1"
    local query="$2"
    unzip -Z1 "$jar" 2>/dev/null | grep -i "/${query}\.java$" || true
}

search_file() {
    local short_name="$1"
    local jar="$2"
    find_in_jar "$jar" "$short_name"
}

# Determine primary jar for a class, considering all available sources.
# For vanilla MC:  sources.jar → merged.jar
# For NeoForge:    Gradle SDK sources → Gradle bus/other sources → merged.jar → sources.jar
pick_primary_jar() {
    local class_name="$1"
    local file_path="$2"

    # NeoForge classes: search all available jars
    if [[ "$class_name" == net.neoforged* ]] || [[ "$class_name" == net/neoforged* ]]; then
        # Try Gradle cache jars first (best source quality for NeoForge libs)
        local found
        found=$(find_file_in_jars "$file_path" "${GRADLE_NEOFORGE_JARS[@]}") || true
        if [[ -n "$found" ]]; then
            echo "gradle:$found"
            return 0
        fi
        # Fall back to MDG merged jar
        if [[ -f "$MERGED_JAR" ]] && class_in_jar "$MERGED_JAR" "$file_path"; then
            echo "merged:$MERGED_JAR"
            return 0
        fi
        # Last resort: MDG sources jar
        if [[ -f "$SOURCES_JAR" ]] && class_in_jar "$SOURCES_JAR" "$file_path"; then
            echo "sources:$SOURCES_JAR"
            return 0
        fi
        echo ""
        return 1
    fi

    # Vanilla Minecraft classes: prefer sources jar
    if [[ -f "$SOURCES_JAR" ]]; then
        echo "sources:$SOURCES_JAR"
    elif [[ -f "$MERGED_JAR" ]]; then
        echo "merged:$MERGED_JAR"
    else
        echo ""
    fi
}

# ---------- 5. Resolve file path ----------
# Fast path: check project source first
PROJECT_FILE=$(find_in_project_src "$(class_to_path "$CLASS_INPUT")") || true
if [[ -z "$PROJECT_FILE" ]]; then
    PROJECT_FILE=$(find_in_project_src "$CLASS_INPUT") || true
fi
if [[ -n "$PROJECT_FILE" ]]; then
    FILE_PATH=$(class_to_path "$CLASS_INPUT")
    echo "--- Project source ---"
    echo "Source:  ${FILE_PATH}"
    echo ""
    cat "$PROJECT_FILE"
    exit 0
fi
# Build initial candidate path
if [[ "$CLASS_INPUT" != *"."* && "$CLASS_INPUT" != *"/"* && "$CLASS_INPUT" != *'$'* ]]; then
    # Short name search — try all jars
    SHORT_NAME="$CLASS_INPUT"

    # Search MDG jars first
    PRIMARY_JAR="$SOURCES_JAR"
    [[ ! -f "$PRIMARY_JAR" ]] && PRIMARY_JAR="$MERGED_JAR"

    if [[ -f "$PRIMARY_JAR" ]]; then
        MATCHES=$(search_file "$SHORT_NAME" "$PRIMARY_JAR")
        MATCH_COUNT=$(echo "$MATCHES" | grep -c . || true)
    else
        MATCHES=""
        MATCH_COUNT=0
    fi

    # If not found in MDG jars, search Gradle cache
    if [[ "$MATCH_COUNT" -eq 0 ]]; then
        for jar in "${GRADLE_NEOFORGE_JARS[@]}"; do
            if [[ -f "$jar" ]]; then
                MATCHES=$(search_file "$SHORT_NAME" "$jar")
                MATCH_COUNT=$(echo "$MATCHES" | grep -c . || true)
                if [[ "$MATCH_COUNT" -ge 1 ]]; then
                    PRIMARY_JAR="$jar"
                    break
                fi
            fi
        done
    fi

    # Also try the other MDG jar
    if [[ "$MATCH_COUNT" -eq 0 && "$PRIMARY_JAR" == "$SOURCES_JAR" && -f "$MERGED_JAR" ]]; then
        MATCHES=$(search_file "$SHORT_NAME" "$MERGED_JAR")
        MATCH_COUNT=$(echo "$MATCHES" | grep -c . || true)
        [[ "$MATCH_COUNT" -ge 1 ]] && PRIMARY_JAR="$MERGED_JAR"
    elif [[ "$MATCH_COUNT" -eq 0 && "$PRIMARY_JAR" == "$MERGED_JAR" && -f "$SOURCES_JAR" ]]; then
        MATCHES=$(search_file "$SHORT_NAME" "$SOURCES_JAR")
        MATCH_COUNT=$(echo "$MATCHES" | grep -c . || true)
        [[ "$MATCH_COUNT" -ge 1 ]] && PRIMARY_JAR="$SOURCES_JAR"
    fi

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
    USE_JAR="$PRIMARY_JAR"
    JAR_TYPE=""
else
    FILE_PATH=$(class_to_path "$CLASS_INPUT")

    # If result has no package path (e.g. inner class dot notation "ItemStack.Builder"),
    # search for the full path
    if [[ "$FILE_PATH" != *"/"* ]]; then
        SHORT_NAME="${FILE_PATH%.java}"

        # Determine which jar to search
        if [[ "$CLASS_INPUT" == net.neoforged* ]] || [[ "$CLASS_INPUT" == net/neoforged* ]]; then
            SEARCH_JAR="$MERGED_JAR"
            [[ ! -f "$SEARCH_JAR" ]] && SEARCH_JAR="$SOURCES_JAR"
        else
            SEARCH_JAR="$SOURCES_JAR"
            [[ ! -f "$SEARCH_JAR" ]] && SEARCH_JAR="$MERGED_JAR"
        fi

        MATCHES=$(search_file "$SHORT_NAME" "$SEARCH_JAR")
        MATCH_COUNT=$(echo "$MATCHES" | grep -c . || true)

        # Search Gradle cache if not found
        if [[ "$MATCH_COUNT" -eq 0 ]]; then
            for jar in "${GRADLE_NEOFORGE_JARS[@]}"; do
                if [[ -f "$jar" ]]; then
                    MATCHES=$(search_file "$SHORT_NAME" "$jar")
                    MATCH_COUNT=$(echo "$MATCHES" | grep -c . || true)
                    if [[ "$MATCH_COUNT" -ge 1 ]]; then
                        SEARCH_JAR="$jar"
                        break
                    fi
                fi
            done
        fi

        if [[ "$MATCH_COUNT" -ge 1 ]]; then
            FILE_PATH=$(echo "$MATCHES" | head -1)
        fi
    fi

    # Use the smart jar picker for full path resolution
    JAR_SELECTION=$(pick_primary_jar "$CLASS_INPUT" "$FILE_PATH") || true
    if [[ -z "$JAR_SELECTION" ]]; then
        # pick_primary_jar failed — try exhaustive search across all jars
        ALL_JARS=()
        [[ -f "$SOURCES_JAR" ]] && ALL_JARS+=("$SOURCES_JAR")
        [[ -f "$MERGED_JAR" ]] && ALL_JARS+=("$MERGED_JAR")
        ALL_JARS+=("${GRADLE_NEOFORGE_JARS[@]}")

        FOUND_JAR=$(find_file_in_jars "$FILE_PATH" "${ALL_JARS[@]}") || true
        if [[ -z "$FOUND_JAR" ]]; then
            # Last resort: fuzzy search in all jars
            BASE_NAME="${CLASS_INPUT##*.}"
            for jar in "${ALL_JARS[@]}"; do
                FOUND=$(unzip -Z1 "$jar" 2>/dev/null | grep -i "/${BASE_NAME}\.java$" | grep -v '[$]' | head -1 || true)
                if [[ -n "$FOUND" ]]; then
                    FILE_PATH="$FOUND"
                    FOUND_JAR="$jar"
                    break
                fi
            done
        fi

        if [[ -z "$FOUND_JAR" ]]; then
            echo "ERROR: '${FILE_PATH}' not found in any jar." >&2
            echo "       Check the class name." >&2
            exit 1
        fi
        USE_JAR="$FOUND_JAR"
        JAR_TYPE=""
    else
        JAR_TYPE="${JAR_SELECTION%%:*}"
        USE_JAR="${JAR_SELECTION#*:}"
    fi
fi

# ---------- 6. Extract and print ----------
MC_VERSION=$(grep -E '^minecraft_version=' "$PROJECT_ROOT/gradle.properties" | cut -d= -f2 | tr -d '[:space:]' || true)

# Determine display label for the jar
JAR_DISPLAY=$(basename "$USE_JAR")
if [[ "$USE_JAR" == "$SOURCES_JAR" ]]; then
    if [[ "$CLASS_INPUT" == net.neoforged* ]] || [[ "$CLASS_INPUT" == net/neoforged* ]] || [[ "$FILE_PATH" == net/neoforged* ]]; then
        JAR_LABEL="sources (MC + NeoForge patches)"
    else
        JAR_LABEL="sources (vanilla MC)"
    fi
elif [[ "$USE_JAR" == "$MERGED_JAR" ]]; then
    JAR_LABEL="merged (MC + NeoForge patches)"
elif [[ "$USE_JAR" == *bus* ]]; then
    JAR_LABEL="NeoForge Event Bus"
elif [[ "$USE_JAR" == */neoforge/* ]]; then
    JAR_LABEL="NeoForge SDK"
elif [[ "$USE_JAR" == *gradle* ]]; then
    JAR_LABEL="NeoForge (Gradle cache)"
else
    JAR_LABEL="$JAR_DISPLAY"
fi

echo "--- Minecraft ${MC_VERSION} (NeoForge ${NEO_VERSION}) ---"
echo "Source:  ${FILE_PATH}"
echo "Jar:     ${JAR_DISPLAY} (${JAR_LABEL})"
echo ""

CACHE_KEY=$(cache_key "${USE_JAR}:${FILE_PATH}")
CACHED=$(cache_get "$CACHE_KEY") || true
if [[ -n "$CACHED" ]]; then
    echo "$CACHED"
else
    CONTENT=$(unzip -p "$USE_JAR" "$FILE_PATH" 2>/dev/null) || {
        _basename="${FILE_PATH##*/}"
        _basename="${_basename%.java}"
        echo "ERROR: Failed to extract ${FILE_PATH} from $(basename "$USE_JAR")." >&2
        echo "" >&2
        _candidates=$(suggest_similar_classes "$USE_JAR" "$_basename")
        if [[ -n "$_candidates" ]]; then
            echo "Did you mean one of these?" >&2
            echo "$_candidates" | while read -r line; do
                echo "  $line" >&2
            done
        fi
        exit 1
    }
    cache_put "$CACHE_KEY" "$CONTENT"
    echo "$CONTENT"
fi
