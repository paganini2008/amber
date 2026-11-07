#!/usr/bin/env bash
# Smoke-tests the runtime and engine inside a packaged (unpacked) Amber app.
#
# Usage: smoke-test.sh <release-dir> <expected-version>
#
# electron-builder leaves the unpacked app next to the installers
# (win-unpacked/, linux-unpacked/, mac*/Amber.app). This finds the bundled
# runtime/bin/java there, then checks that:
#   - `engine.jar version` prints the expected version,
#   - the AppCDS archive maps,
#   - `engine.jar analyze` succeeds on a sample script.
set -euo pipefail

if [[ $# -ne 2 ]]; then
  echo "usage: $0 <release-dir> <expected-version>" >&2
  exit 2
fi

RELEASE_DIR=${1%/}
EXPECTED=$2
HERE=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)

JAVA_BIN=$(find "$RELEASE_DIR" -type f \( -path '*/runtime/bin/java' -o -path '*/runtime/bin/java.exe' \) | head -n 1)
if [[ -z "$JAVA_BIN" ]]; then
  echo "error: no runtime/bin/java under $RELEASE_DIR" >&2
  exit 1
fi
RES=$(dirname "$(dirname "$(dirname "$JAVA_BIN")")")
JAR="$RES/engine/engine.jar"
JSA="$RES/engine/amber.jsa"
echo "Packaged resources: $RES"

for f in "$JAR" "$JSA"; do
  if [[ ! -f "$f" ]]; then
    echo "error: $f is missing from the package" >&2
    exit 1
  fi
done

OUT=$("$JAVA_BIN" -XX:SharedArchiveFile="$JSA" -jar "$JAR" version | tr -d '\r')
echo "engine version: $OUT"
if [[ "$OUT" != *" $EXPECTED" && "$OUT" != "$EXPECTED" ]]; then
  echo "error: expected version $EXPECTED" >&2
  exit 1
fi

CDS_LOG=$("$JAVA_BIN" -XX:SharedArchiveFile="$JSA" -Xlog:cds=info -jar "$JAR" version 2>&1 | tr -d '\r')
if ! grep -q 'Mapped dynamic region' <<< "$CDS_LOG"; then
  echo "error: the AppCDS archive was not used:" >&2
  grep -i 'cds' <<< "$CDS_LOG" | tail -n 20 >&2 || true
  exit 1
fi
echo "AppCDS archive maps"

JSON=$("$JAVA_BIN" -XX:SharedArchiveFile="$JSA" -jar "$JAR" \
  analyze "$HERE/appcds-sample.sql" --dialect postgresql --json | tr -d '\r')
if ! grep -Eq '"status"[[:space:]]*:[[:space:]]*"SUCCESS"' <<< "$JSON"; then
  echo "error: analyze did not succeed:" >&2
  head -c 2000 <<< "$JSON" >&2
  exit 1
fi
echo "engine analyze: SUCCESS"
