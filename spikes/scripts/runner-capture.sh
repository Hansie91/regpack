#!/usr/bin/env bash
# Usage: runner-capture.sh <result directory>
# Writes <result directory>/runner.txt: what the runner is (kernel, CPU, memory, disks, tool versions).
# Only the allow-listed variables of EnvCapture are printed: the runner holds tokens, so the whole
# environment is never dumped. Every probe may fail on a machine that lacks the tool; a failure is
# recorded as "(unavailable)" and never stops the capture.
set -euo pipefail

OUT="${1:?usage: runner-capture.sh <result directory>}"
mkdir -p "$OUT"

# the same ten names as EnvCapture.ALLOWED_VARIABLES
ALLOWED=(ImageOS ImageVersion RUNNER_OS RUNNER_ARCH GITHUB_RUN_ID GITHUB_RUN_ATTEMPT GITHUB_SHA GITHUB_REPOSITORY GITHUB_WORKFLOW GITHUB_REF_NAME)

section() {
  local title="$1"
  shift
  printf '== %s ==\n' "$title"
  "$@" 2>&1 || echo "(unavailable)"
}

{
  section "uname -a" uname -a
  section "lscpu" lscpu
  printf '== model name ==\n'
  if [ -r /proc/cpuinfo ]; then
    grep -m1 '^model name' /proc/cpuinfo || echo "(unavailable)"
  else
    echo "(unavailable)"
  fi
  section "free -b" free -b
  printf '== /proc/meminfo (head) ==\n'
  if [ -r /proc/meminfo ]; then
    head -n 5 /proc/meminfo
  else
    echo "(unavailable)"
  fi
  section "df -B1 -T / /mnt" df -B1 -T / /mnt
  section "lsblk -b" lsblk -b
  printf '== allow-listed variables ==\n'
  for name in "${ALLOWED[@]}"; do
    if [ -n "${!name+x}" ]; then
      printf '%s=%s\n' "$name" "${!name}"
    fi
  done
  section "docker --version" docker --version
  section "java -version" java -version
  printf '== /sys/fs/cgroup/cpu.max ==\n'
  if [ -r /sys/fs/cgroup/cpu.max ]; then
    cat /sys/fs/cgroup/cpu.max
  else
    echo "(absent)"
  fi
} > "$OUT/runner.txt"
echo "runner-capture: wrote $OUT/runner.txt"
