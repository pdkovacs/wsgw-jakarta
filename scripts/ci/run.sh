#!/usr/bin/env bash
# Builds and tests one commit in a disposable worktree; records the outcome.
# Usage: run.sh <commit-sha> [trigger-label]
set -euo pipefail

# A hook invocation runs with GIT_DIR/GIT_INDEX_FILE/etc set for the git
# command in progress; left in place, they corrupt the `git worktree add`
# below once it operates on a second worktree's own paths.
unset $(git rev-parse --local-env-vars)

SHA="${1:?commit sha required}"
TRIGGER="${2:-manual}"

REPO_ROOT="$(git rev-parse --show-toplevel)"
CI_HOME="${WSGW_CI_HOME:-/tmp/wsgw-ci}"
RUNS_DIR="$CI_HOME/runs"
WORKTREE_DIR="$CI_HOME/worktree"
LOCK_FILE="$CI_HOME/run.lock"
INDEX_FILE="$CI_HOME/index.tsv"

mkdir -p "$RUNS_DIR"

(
  flock 9

  BRANCH="$(git -C "$REPO_ROOT" for-each-ref --points-at "$SHA" --format='%(refname:short)' refs/heads | head -1)"
  BRANCH="${BRANCH:-(detached)}"
  SUBJECT="$(git -C "$REPO_ROOT" log -1 --format=%s "$SHA")"
  [ "${#SUBJECT}" -gt 72 ] && SUBJECT="${SUBJECT:0:69}..."
  TS="$(date +%Y%m%dT%H%M%S)"
  RUN_DIR="$RUNS_DIR/${TS}-${SHA:0:12}"
  mkdir -p "$RUN_DIR"
  ln -sfn "$RUN_DIR" "$CI_HOME/latest"
  echo RUNNING > "$RUN_DIR/status"

  {
    echo "sha=$SHA"
    echo "subject=$SUBJECT"
    echo "branch=$BRANCH"
    echo "trigger=$TRIGGER"
    echo "started=$(date -Is)"
  } > "$RUN_DIR/meta.txt"

  rm -rf "$WORKTREE_DIR"
  START=$(date +%s)
  if git -C "$REPO_ROOT" worktree add --detach "$WORKTREE_DIR" "$SHA" >> "$RUN_DIR/log.txt" 2>&1 \
     && (cd "$WORKTREE_DIR" && mvn -B verify) >> "$RUN_DIR/log.txt" 2>&1
  then
    echo PASS > "$RUN_DIR/status"
  else
    echo FAIL > "$RUN_DIR/status"
  fi
  END=$(date +%s)

  git -C "$REPO_ROOT" worktree remove --force "$WORKTREE_DIR" 2>/dev/null || rm -rf "$WORKTREE_DIR"
  git -C "$REPO_ROOT" worktree prune

  echo "finished=$(date -Is)" >> "$RUN_DIR/meta.txt"
  echo "duration=$((END - START))s" >> "$RUN_DIR/meta.txt"

  printf '%s\t%s\t%s\t%s\t%s\t%ss\t%s\n' \
    "$(date -Is)" "$SHA" "$SUBJECT" "$BRANCH" "$(cat "$RUN_DIR/status")" "$((END - START))" "$RUN_DIR" >> "$INDEX_FILE"
) 9>"$LOCK_FILE"
