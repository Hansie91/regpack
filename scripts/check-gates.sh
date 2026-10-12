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
gates traceability --file docs/traceability.md --root . "${scan[@]}"
gates pins --dockerfile packaging/container/Dockerfile --workflows .github/workflows
git ls-files | gates eol --root .
# G3 and isolation (P1a): tracked and untracked-not-ignored files, so a leak is caught before it is added
{ git ls-files; git ls-files -o --exclude-standard; } | gates g3 --root .
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
echo "check-gates: all gates passed"
