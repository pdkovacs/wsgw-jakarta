#!/usr/bin/env bash
# Usage: status.sh          -> last 20 runs, plus whatever is running or queued
#        status.sh <n>      -> last n runs
#        status.sh log      -> full log of the latest run
set -euo pipefail

CI_HOME="${WSGW_CI_HOME:-/tmp/wsgw-ci}"
PENDING_DIR="$CI_HOME/pending"

if [ "${1:-}" = "log" ]; then
  exec cat "$CI_HOME/latest/log.txt"
fi

meta() { grep -m1 "^$1=" "$2" | cut -d= -f2-; }

RUNNING_LINE=""
if [ -e "$CI_HOME/latest/status" ] && [ "$(cat "$CI_HOME/latest/status")" = "RUNNING" ]; then
  META="$CI_HOME/latest/meta.txt"
  STARTED="$(meta started "$META")"
  ELAPSED=$(( $(date +%s) - $(date -d "$STARTED" +%s) ))
  RUNNING_LINE="$(printf '%s\t%s\t%s\t%s\t%s\tRUNNING\t%ss\t%s' \
    "$STARTED" "$(meta sha "$META")" "$(meta subject "$META")" "$(meta branch "$META")" \
    "$(meta worktree "$META")" "$ELAPSED" "$CI_HOME/latest")"
fi

# One line per worktree with a request waiting for the lock, showing what that
# request would test if it drained now: the request names a worktree, not a
# commit, so the answer is that worktree's HEAD as it stands at this moment.
QUEUED_LINES=""
declare -A QUEUED_SINCE
if [ -d "$PENDING_DIR" ]; then
  for MARKER in "$PENDING_DIR"/*; do
    [ -e "$MARKER" ] || continue
    # Nothing else clears the marker of a request killed before it drained.
    # Either process may outlive the other - the subshell keeps waiting on the
    # lock if its parent is killed - so the request is gone only if both are.
    WAITER="$(meta pid "$MARKER")"
    if ! kill -0 "${MARKER##*.}" 2>/dev/null \
       && { [ -z "$WAITER" ] || ! kill -0 "$WAITER" 2>/dev/null; }; then
      rm -f "$MARKER"
      continue
    fi
    WT="$(meta worktree_path "$MARKER")"
    SINCE="$(meta queued "$MARKER")"
    [ -n "${QUEUED_SINCE[$WT]:-}" ] && [ "${QUEUED_SINCE[$WT]}" \< "$SINCE" ] || QUEUED_SINCE[$WT]="$SINCE"
  done
fi
for WT in "${!QUEUED_SINCE[@]}"; do
  SINCE="${QUEUED_SINCE[$WT]}"
  SHA="$(git -C "$WT" rev-parse HEAD 2>/dev/null || echo '?')"
  SUBJECT="$(git -C "$WT" log -1 --format=%s 2>/dev/null || echo '?')"
  [ "${#SUBJECT}" -gt 72 ] && SUBJECT="${SUBJECT:0:69}..."
  BRANCH="$(git -C "$WT" rev-parse --abbrev-ref HEAD 2>/dev/null || echo '?')"
  [ "$BRANCH" != HEAD ] || BRANCH="(detached)"
  ELAPSED=$(( $(date +%s) - $(date -d "$SINCE" +%s) ))
  QUEUED_LINES+="$(printf '%s\t%s\t%s\t%s\t%s\tQUEUED\t%ss\t-' \
    "$SINCE" "$SHA" "$SUBJECT" "$BRANCH" "$(basename "$WT")" "$ELAPSED")"$'\n'
done

HISTORY=""
[ -f "$CI_HOME/index.tsv" ] && HISTORY="$(tail -n "${1:-20}" "$CI_HOME/index.tsv")"

COMBINED="$(printf '%s\n%s\n%s' "$HISTORY" "$RUNNING_LINE" "$QUEUED_LINES" | sed '/^$/d')"

if [ -z "$COMBINED" ]; then
  echo "no runs yet"
else
  printf '%s\n' "$COMBINED" | column -t -s $'\t'
fi
