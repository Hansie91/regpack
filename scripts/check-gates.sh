#!/usr/bin/env bash
# Repository gates that need build output. Run after: ./mvnw verify
set -euo pipefail
cd "$(dirname "$0")/.."
BUILD_ROOT="${REGPACK_BUILD_ROOT:-}"
if [ -n "$BUILD_ROOT" ]; then TOOLS="$BUILD_ROOT/regpack-tools/classes"; else TOOLS="regpack-tools/target/classes"; fi
[ -d "$TOOLS" ] || { echo "check-gates: $TOOLS not found; run ./mvnw verify first"; exit 1; }
JAVA="${REGPACK_JAVA:-java}"
gates() { "$JAVA" -cp "$TOOLS" dev.regpack.tools.Gates "$@"; }

scan=(--scan .)
if [ -n "$BUILD_ROOT" ]; then scan+=(--scan "$BUILD_ROOT"); fi
gates traceability --file docs/traceability.md --root . --realstore skip "${scan[@]}"
gates pins --dockerfile packaging/container/Dockerfile --workflows .github/workflows
# NUL-terminated names with quoting off: a tracked file whose name holds non-ASCII characters is listed
# as itself (git would quote it and the gate would then skip or choke on the quoted name). A file that
# is tracked but deleted from the working tree is left out; any other listed path that is missing is
# reported by the gate.
tracked_files() {
  local f
  local -A gone=()
  while IFS= read -r -d '' f; do gone["$f"]=1; done < <(git -c core.quotepath=false ls-files -z --deleted)
  while IFS= read -r -d '' f; do
    if [ -z "${gone[$f]:-}" ]; then printf '%s\0' "$f"; fi
  done < <(git -c core.quotepath=false ls-files -z)
}
tracked_files | gates eol --root .
# G3 and isolation (P1a): tracked and untracked-not-ignored files, so a leak is caught before it is added
{ git -c core.quotepath=false ls-files -z; git -c core.quotepath=false ls-files -z -o --exclude-standard; } | gates g3 --root .
gates isolation --root .

# P1a: the committed spike reports verify, and the generated documents equal a fresh regeneration
for classes in regpack-bench/target/classes regpack-model/target/classes; do
  [ -d "$classes" ] || { echo "check-gates: build first: $classes missing"; exit 1; }
done
if ! scripts/bench.sh verify-committed; then
  echo "check-gates: FAILED: bench verify-committed rejected a committed report"
  exit 1
fi
echo "verify-committed: OK"
GEN_DIR="$(mktemp -d "${TMPDIR:-/tmp}/regpack-gates.XXXXXX")"
trap 'rm -rf "$GEN_DIR"' EXIT
# java.exe cannot read a /tmp style path under Git Bash: hand it a mixed (C:/...) path
GEN_ARG="$GEN_DIR"
if command -v cygpath >/dev/null 2>&1; then GEN_ARG="$(cygpath -m "$GEN_DIR")"; fi
for pair in results:results.md budget:latency-budget.md; do
  cmd="${pair%%:*}"; doc="${pair#*:}"
  if ! scripts/bench.sh "$cmd" --write "$GEN_ARG/$doc"; then
    echo "check-gates: FAILED: bench $cmd could not regenerate docs/spikes/$doc"
    exit 1
  fi
  if ! cmp -s "$GEN_DIR/$doc" "docs/spikes/$doc"; then
    echo "check-gates: FAILED: docs/spikes/$doc differs from a fresh regeneration; run scripts/bench.sh $cmd --write docs/spikes/$doc and review the diff"
    exit 1
  fi
  echo "$doc: OK"
done

# P2a: every committed pack passes `pack test` (checks, coverage gates, golden cases with provenance)
for classes in regpack-pack/target/classes regpack-engine/target/classes regpack-app/target/classes; do
  [ -d "$classes" ] || { echo "check-gates: build first: $classes missing"; exit 1; }
done
for dir in packs/*/; do
  dir="${dir%/}"
  if ! PACK_OUT="$(bash scripts/pack.sh test "$dir" 2>&1)"; then
    echo "$PACK_OUT"
    echo "check-gates: FAILED: pack test of $dir"
    exit 1
  fi
  echo "pack test $dir: OK"
done
echo "check-gates: all gates passed"
