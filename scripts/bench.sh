#!/usr/bin/env bash
# Usage: scripts/bench.sh <command> [options]   (run the bench command line; build first: scripts/t.sh or mvnw install)
set -euo pipefail
cd "$(dirname "$0")/.."
# java.exe needs ';' as the class-path separator under Git Bash
case "$(uname -s)" in MINGW*|MSYS*|CYGWIN*) S=';' ;; *) S=':' ;; esac
exec "${REGPACK_JAVA:-java}" -cp "regpack-bench/target/classes${S}regpack-model/target/classes" dev.regpack.bench.cli.Bench "$@"
