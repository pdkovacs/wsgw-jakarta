#!/usr/bin/env bash
# Usage: status.sh          -> last 20 runs, plus the in-progress run if any
#        status.sh <n>      -> last n runs
#        status.sh log      -> full log of the latest run
set -euo pipefail

CI_HOME="${WSGW_CI_HOME:-/tmp/wsgw-ci}"

if [ "${1:-}" = "log" ]; then
  exec cat "$CI_HOME/latest/log.txt"
fi

RUNNING_LINE=""
if [ -e "$CI_HOME/latest/status" ] && [ "$(cat "$CI_HOME/latest/status")" = "RUNNING" ]; then
  META="$CI_HOME/latest/meta.txt"
  SHA="$(grep -m1 '^sha=' "$META" | cut -d= -f2-)"
  SUBJECT="$(grep -m1 '^subject=' "$META" | cut -d= -f2-)"
  BRANCH="$(grep -m1 '^branch=' "$META" | cut -d= -f2-)"
  STARTED="$(grep -m1 '^started=' "$META" | cut -d= -f2-)"
  ELAPSED=$(( $(date +%s) - $(date -d "$STARTED" +%s) ))
  RUNNING_LINE="$(printf '%s\t%s\t%s\t%s\tRUNNING\t%ss\t%s' "$STARTED" "$SHA" "$SUBJECT" "$BRANCH" "$ELAPSED" "$CI_HOME/latest")"
fi

HISTORY=""
[ -f "$CI_HOME/index.tsv" ] && HISTORY="$(tail -n "${1:-20}" "$CI_HOME/index.tsv")"

COMBINED="$(printf '%s\n%s\n' "$HISTORY" "$RUNNING_LINE" | sed '/^$/d')"

if [ -z "$COMBINED" ]; then
  echo "no runs yet"
else
  printf '%s\n' "$COMBINED" | column -t -s $'\t'
fi
