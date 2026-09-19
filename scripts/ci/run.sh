#!/usr/bin/env bash
# Builds and tests one commit in a disposable worktree; records the outcome.
#
# Usage: run.sh --worktree <path> [trigger-label]
#          Queued request. The commit tested is whatever HEAD that worktree
#          holds when the request reaches the front of the queue, and the run
#          is skipped if that commit already has one. A burst of commits (an
#          amend loop, a fast sequence) thus collapses into a single run of
#          the state you actually ended up at, rather than one run per commit
#          for commits you have already left behind.
#
#        run.sh <commit-sha> [trigger-label]
#          Explicit request: runs that commit unconditionally, even if it has
#          been tested before. For re-running a suspected-flaky failure.
set -euo pipefail

# A hook invocation runs with GIT_DIR/GIT_INDEX_FILE/etc set for the git
# command in progress; left in place, they corrupt the `git worktree add`
# below once it operates on a second worktree's own paths.
unset $(git rev-parse --local-env-vars)

SHA=""
case "${1:?commit sha, or --worktree <path>, required}" in
  --worktree)
    REQUESTER="${2:?worktree path required}"
    TRIGGER="${3:-manual}"
    ;;
  *)
    SHA="$1"
    REQUESTER="$(git rev-parse --show-toplevel)"
    TRIGGER="${2:-manual}"
    ;;
esac
WORKTREE_NAME="$(basename "$REQUESTER")"

CI_HOME="${WSGW_CI_HOME:-/tmp/wsgw-ci}"
RUNS_DIR="$CI_HOME/runs"
PENDING_DIR="$CI_HOME/pending"
WORKTREE_DIR="$CI_HOME/worktree"
LOCK_FILE="$CI_HOME/run.lock"
INDEX_FILE="$CI_HOME/index.tsv"

mkdir -p "$RUNS_DIR" "$PENDING_DIR"

# Announce the request before queueing for the lock. With several worktrees
# committing, a request can sit behind another run for minutes, and an
# invisible wait is indistinguishable from "WATCH_PATHS said don't bother".
PENDING_FILE="$PENDING_DIR/$(printf '%s' "$REQUESTER" | tr / _).$$"
{
  echo "worktree=$WORKTREE_NAME"
  echo "worktree_path=$REQUESTER"
  echo "queued=$(date -Is)"
  echo "trigger=$TRIGGER"
} > "$PENDING_FILE"

(
  # The subshell, not this script, is what actually waits on the lock and
  # survives its parent; record it so status.sh can tell a request that is
  # still queued from the leftovers of one that was killed.
  echo "pid=$BASHPID" >> "$PENDING_FILE"
  flock 9
  rm -f "$PENDING_FILE"

  BRANCH=""
  if [ -z "$SHA" ]; then
    # Resolve the request now, at the front of the queue, against whatever the
    # worktree holds at this moment - not against what it held when the hook
    # fired, which may be several commits ago.
    GIT_DIR_PATH="$(git -C "$REQUESTER" rev-parse --absolute-git-dir 2>/dev/null)" || exit 0
    # Mid-rebase, HEAD is a transient replay commit. post-commit stays out of
    # an interactive rebase's way for the same reason, and post-rewrite files
    # a fresh request once the rebase lands.
    if [ -d "$GIT_DIR_PATH/rebase-merge" ] || [ -d "$GIT_DIR_PATH/rebase-apply" ]; then
      exit 0
    fi
    SHA="$(git -C "$REQUESTER" rev-parse HEAD 2>/dev/null)" || exit 0
    # Same commit, same tree, same verdict - whichever worktree tested it.
    if [ -f "$INDEX_FILE" ] \
       && awk -F'\t' -v sha="$SHA" '$2 == sha { hit = 1 } END { exit !hit }' "$INDEX_FILE"; then
      exit 0
    fi
    BRANCH="$(git -C "$REQUESTER" rev-parse --abbrev-ref HEAD)"
    [ "$BRANCH" != HEAD ] || BRANCH="(detached)"
  fi

  if [ -z "$BRANCH" ]; then
    BRANCH="$(git -C "$REQUESTER" for-each-ref --points-at "$SHA" --format='%(refname:short)' refs/heads | head -1)"
    BRANCH="${BRANCH:-(detached)}"
  fi
  SUBJECT="$(git -C "$REQUESTER" log -1 --format=%s "$SHA")"
  [ "${#SUBJECT}" -gt 72 ] && SUBJECT="${SUBJECT:0:69}..."
  TS="$(date +%Y%m%dT%H%M%S)"
  RUN_DIR="$RUNS_DIR/${TS}-${SHA:0:12}"
  mkdir -p "$RUN_DIR"
  ln -sfn "$RUN_DIR" "$CI_HOME/latest"
  echo RUNNING > "$RUN_DIR/status"
  command -v notify-send >/dev/null && notify-send "wsgw CI: started" "$SUBJECT ($BRANCH)" || true

  {
    echo "sha=$SHA"
    echo "subject=$SUBJECT"
    echo "branch=$BRANCH"
    echo "worktree=$WORKTREE_NAME"
    echo "worktree_path=$REQUESTER"
    echo "trigger=$TRIGGER"
    echo "started=$(date -Is)"
  } > "$RUN_DIR/meta.txt"

  rm -rf "$WORKTREE_DIR"
  # A run killed mid-flight (kill -9, reboot) leaves its checkout registered in
  # the shared repo, and `worktree add` then refuses the path until the stale
  # registration is gone. Only entries whose directory has vanished are pruned,
  # so live worktrees - yours included - are untouched.
  git -C "$REQUESTER" worktree prune
  START=$(date +%s)
  if git -C "$REQUESTER" worktree add --detach "$WORKTREE_DIR" "$SHA" >> "$RUN_DIR/log.txt" 2>&1 \
     && (cd "$WORKTREE_DIR" && mvn -B verify) >> "$RUN_DIR/log.txt" 2>&1
  then
    echo PASS > "$RUN_DIR/status"
  else
    echo FAIL > "$RUN_DIR/status"
  fi
  END=$(date +%s)
  RESULT="$(cat "$RUN_DIR/status")"
  command -v notify-send >/dev/null && notify-send "wsgw CI: $RESULT" "$SUBJECT ($BRANCH) in $((END - START))s" || true

  git -C "$REQUESTER" worktree remove --force "$WORKTREE_DIR" 2>/dev/null || rm -rf "$WORKTREE_DIR"
  git -C "$REQUESTER" worktree prune

  echo "finished=$(date -Is)" >> "$RUN_DIR/meta.txt"
  echo "duration=$((END - START))s" >> "$RUN_DIR/meta.txt"

  printf '%s\t%s\t%s\t%s\t%s\t%s\t%ss\t%s\n' \
    "$(date -Is)" "$SHA" "$SUBJECT" "$BRANCH" "$WORKTREE_NAME" "$(cat "$RUN_DIR/status")" "$((END - START))" "$RUN_DIR" >> "$INDEX_FILE"
) 9>"$LOCK_FILE"
