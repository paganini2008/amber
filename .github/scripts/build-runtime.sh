#!/usr/bin/env bash
# Builds the trimmed Java runtime that ships inside the Amber installers.
#
# Usage: build-runtime.sh <engine-dir> <output-dir>
#   <engine-dir>  folder holding engine.jar and lib/*.jar
#                 (backend/amber-core/target/engine after `mvn package`)
#   <output-dir>  where the jlink image is written; replaced if it exists
#
# The JDK is taken from $JAVA_HOME (falls back to the jdeps/jlink on PATH).
# The module list is computed with jdeps at build time, so JDBC drivers added to
# lib/ later are covered automatically, then unioned with a baseline that drivers
# load reflectively (JNDI, JMX, Kerberos/SASL, extra charsets, PKCS#11, ...).
#
# Works on Linux, macOS and Git Bash on Windows. jlink must run on the target
# platform and architecture: the output is native.
#
# JDK 24+ builds of Eclipse Temurin are built with JEP 493 and ship without
# jmods/; jlink then links from the JDK's own run-time image, which is fine for
# a same-platform runtime. If a jmods/ folder is present it is used instead.
set -euo pipefail

if [[ $# -ne 2 ]]; then
  echo "usage: $0 <engine-dir> <output-dir>" >&2
  exit 2
fi

ENGINE_DIR=${1%/}
OUT_DIR=${2%/}
RELEASE=25

BASELINE=(
  java.sql
  java.naming
  java.management
  java.security.jgss
  java.security.sasl
  java.xml
  jdk.charsets
  jdk.zipfs
  jdk.unsupported
  jdk.crypto.cryptoki
)

# Resolve tools; Git Bash finds the .exe without the suffix.
if [[ -n "${JAVA_HOME:-}" ]]; then
  JAVA_HOME_UNIX=$JAVA_HOME
  if command -v cygpath >/dev/null 2>&1; then
    JAVA_HOME_UNIX=$(cygpath -u "$JAVA_HOME")
  fi
  BIN="$JAVA_HOME_UNIX/bin/"
else
  BIN=""
  JAVA_HOME_UNIX=""
fi
JAVA="${BIN}java"
JDEPS="${BIN}jdeps"
JLINK="${BIN}jlink"

for tool in "$JAVA" "$JDEPS" "$JLINK"; do
  if ! command -v "$tool" >/dev/null 2>&1; then
    echo "error: $tool not found (set JAVA_HOME to a JDK $RELEASE)" >&2
    exit 1
  fi
done

if [[ ! -f "$ENGINE_DIR/engine.jar" ]]; then
  echo "error: $ENGINE_DIR/engine.jar not found" >&2
  exit 1
fi

echo "JDK: $("$JAVA" -version 2>&1 | head -n 1 | tr -d '\r')"

# Jars to analyze: engine.jar plus every dependency in lib/.
JARS=("$ENGINE_DIR/engine.jar")
shopt -s nullglob
for jar in "$ENGINE_DIR"/lib/*.jar; do
  JARS+=("$jar")
done
shopt -u nullglob
echo "Analyzing ${#JARS[@]} jar(s) with jdeps"

# --print-module-deps prints one comma-separated line; keep the last line that
# looks like a module list and drop CRs that Windows tools add.
JDEPS_OUT=$("$JDEPS" --ignore-missing-deps --multi-release "$RELEASE" \
  --print-module-deps "${JARS[@]}" | tr -d '\r')
DETECTED=$(printf '%s\n' "$JDEPS_OUT" | grep -E '^[a-z][a-zA-Z0-9._]*(,[a-z][a-zA-Z0-9._]*)*$' | tail -n 1 || true)
if [[ -z "$DETECTED" ]]; then
  echo "error: jdeps printed no module list:" >&2
  printf '%s\n' "$JDEPS_OUT" >&2
  exit 1
fi
echo "jdeps modules: $DETECTED"

# Modules this JDK actually has (drop baseline entries that do not exist).
AVAILABLE=$("$JAVA" --list-modules | tr -d '\r' | sed 's/@.*//')
has_module() { printf '%s\n' "$AVAILABLE" | grep -qx "$1"; }

declare -a MODULES=()
add_module() {
  local m=$1 existing
  [[ -z "$m" ]] && return
  for existing in "${MODULES[@]+"${MODULES[@]}"}"; do
    [[ "$existing" == "$m" ]] && return
  done
  MODULES+=("$m")
}

IFS=',' read -r -a DETECTED_LIST <<< "$DETECTED"
for m in "${DETECTED_LIST[@]}"; do
  add_module "$m"
done
for m in "${BASELINE[@]}"; do
  if has_module "$m"; then
    add_module "$m"
  else
    echo "note: baseline module $m does not exist in this JDK, skipped"
  fi
done

MODULE_LIST=$(printf '%s\n' "${MODULES[@]}" | sort | paste -sd, -)
echo "Modules ($(printf '%s\n' "${MODULES[@]}" | wc -l | tr -d ' ')): $MODULE_LIST"

JLINK_ARGS=()
if [[ -n "$JAVA_HOME_UNIX" && -d "$JAVA_HOME_UNIX/jmods" ]]; then
  echo "Linking from $JAVA_HOME/jmods"
  JLINK_ARGS+=(--module-path "$JAVA_HOME/jmods")
else
  echo "No jmods/ folder: linking from the JDK run-time image (JEP 493)"
fi

# --generate-cds-archive writes the base CDS archive (lib/server/classes.jsa).
# Without it the image has no base archive and -XX:ArchiveClassesAtExit (the
# AppCDS archive made by generate-appcds.sh) is refused by the JVM.
rm -rf "$OUT_DIR"
mkdir -p "$(dirname "$OUT_DIR")"

"$JLINK" "${JLINK_ARGS[@]+"${JLINK_ARGS[@]}"}" \
  --add-modules "$MODULE_LIST" \
  --strip-debug \
  --no-header-files \
  --no-man-pages \
  --compress zip-6 \
  --generate-cds-archive \
  --output "$OUT_DIR"

# jlink writes the legal/ notices read-only. Make the image owner-writable so
# installed copies can be updated, removed or have attributes cleared
# (e.g. xattr -dr com.apple.quarantine on macOS) without permission errors.
chmod -R u+w "$OUT_DIR"

SIZE=$(du -sh "$OUT_DIR" | cut -f1 | tr -d " ")
echo "Runtime written to $OUT_DIR ($SIZE)"
"$OUT_DIR/bin/java" -version 2>&1 | head -n 1 | tr -d '\r'
