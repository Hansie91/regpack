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
echo "check-gates: all gates passed"
