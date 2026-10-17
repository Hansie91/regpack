#!/usr/bin/env bash
# Usage: scripts/pack.sh <pack command and options>   (run the regpack pack commands; build first: scripts/t.sh or mvnw install)
# Examples: scripts/pack.sh test packs/sample.s0 | scripts/pack.sh seal packs/sample.s0
# Relative paths are read from the repository root (the script changes to it, as scripts/bench.sh does).
set -euo pipefail
cd "$(dirname "$0")/.."
# java.exe needs ';' as the class-path separator under Git Bash
case "$(uname -s)" in MINGW*|MSYS*|CYGWIN*) S=';' ;; *) S=':' ;; esac
CP="regpack-model/target/classes${S}regpack-ports/target/classes${S}regpack-pack/target/classes${S}regpack-engine/target/classes${S}regpack-inmemory/target/classes${S}regpack-app/target/classes"
exec "${REGPACK_JAVA:-java}" -cp "$CP" dev.regpack.app.Main pack "$@"
