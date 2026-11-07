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

rm -f "$ARCHIVE"
"$RUNTIME/bin/java" -XX:ArchiveClassesAtExit="$ARCHIVE" \
  -jar "$ENGINE/engine.jar" analyze "$SAMPLE" --dialect postgresql

if [[ ! -s "$ARCHIVE" ]]; then
  echo "error: $ARCHIVE was not created" >&2
  exit 1
fi
echo "AppCDS archive: $ARCHIVE ($(du -h "$ARCHIVE" | cut -f1 | tr -d ' '))"
