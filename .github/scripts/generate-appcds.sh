#!/usr/bin/env bash
# Creates the AppCDS archive (amber.jsa) for a jlink runtime and engine.
#
# Usage: generate-appcds.sh <runtime-dir> <engine-dir>
#   <runtime-dir>  output of build-runtime.sh (must contain lib/server/classes.jsa,
#                  the base archive that jlink --generate-cds-archive writes)
#   <engine-dir>   folder holding engine.jar and lib/; amber.jsa is written here
#
# The archive must be produced by the runtime that ships with the app. It stays
# valid when the app is installed elsewhere (relocated class path).
set -euo pipefail

if [[ $# -ne 2 ]]; then
  echo "usage: $0 <runtime-dir> <engine-dir>" >&2
  exit 2
fi

RUNTIME=${1%/}
ENGINE=${2%/}
HERE=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
SAMPLE="$HERE/appcds-sample.sql"
ARCHIVE="$ENGINE/amber.jsa"

# Absolute path in the platform's own form. On Windows (Git Bash) the JVM can only relocate the archived class
# path when the archive records native paths (D:\a\...\engine.jar): with '/' it finds no common prefix and
# rejects the archive ("shared class paths mismatch") once the app is installed elsewhere.
native_path() {
  local abs
  abs="$(cd "$(dirname "$1")" && pwd)/$(basename "$1")"
  if command -v cygpath >/dev/null 2>&1; then cygpath -w "$abs"; else printf '%s\n' "$abs"; fi
}

rm -f "$ARCHIVE"
# The archive records the jars' modification times, and the packaged app gets copies whose times are restored by
# the afterPack hook (ui/amber-desktop/scripts/after-pack.mjs). Node sets times with millisecond precision, NTFS
# keeps 100 ns: a fractional time then differs ("timestamp has changed"). Whole seconds survive exactly everywhere.
STAMP=$(date +%Y%m%d%H%M.%S)
find "$ENGINE" -type f -name '*.jar' -exec touch -t "$STAMP" {} +
"$RUNTIME/bin/java" -XX:ArchiveClassesAtExit="$(native_path "$ARCHIVE")" \
  -jar "$(native_path "$ENGINE/engine.jar")" analyze "$SAMPLE" --dialect postgresql

if [[ ! -s "$ARCHIVE" ]]; then
  echo "error: $ARCHIVE was not created" >&2
  exit 1
fi
echo "AppCDS archive: $ARCHIVE ($(du -h "$ARCHIVE" | cut -f1 | tr -d ' '))"
