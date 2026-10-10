#!/usr/bin/env bash
# Fallback JVM for the gates: runs `java` from the same digest-pinned image. Usage: toolchain/docker-java.sh <java arguments>
set -euo pipefail
IMAGE='maven:3.9.16-eclipse-temurin-25@sha256:93b8a14ea2f412782e4e842651273b4d903e35cc496284f178fbbe2d67d00976'
cd "$(dirname "$0")/.."
HOST_DIR="$(pwd -W 2>/dev/null || pwd)"
MSYS_NO_PATHCONV=1 exec docker run --rm -i -v "${HOST_DIR}:/work" -w /work "$IMAGE" java "$@"
