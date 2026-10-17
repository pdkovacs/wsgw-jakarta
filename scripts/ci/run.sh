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
#          Runs only the e2e modules when nothing that feeds the gateway's
#          build changed since the nearest ancestor that passed (see
#          FULL_SUITE_PATHS in config); otherwise the whole suite.
#
#        run.sh <commit> [trigger-label]
#          Explicit request: runs the whole suite on that commit (a SHA, or a
#          ref such as HEAD) unconditionally, even if it has been tested before.
#          For re-running a suspected-flaky failure, or for giving the day's
#          narrowed runs a passing base ahead of time. Runs in the foreground.
set -euo pipefail

# A hook invocation runs with GIT_DIR/GIT_INDEX_FILE/etc set for the git
# command in progress; left in place, they corrupt the `git worktree add`
# below once it operates on a second worktree's own paths.
unset $(git rev-parse --local-env-vars)

# Prefer sdkman's tools where sdkman is installed; elsewhere (e.g. distro-packaged
# JDK and Maven) keep whatever the environment provides, since a JAVA_HOME
# pointing at a missing directory makes mvn refuse to start.
SDKMAN_CANDIDATES="$HOME/.sdkman/candidates"
if [ -d "$SDKMAN_CANDIDATES/java/current" ]; then
  export JAVA_HOME="$SDKMAN_CANDIDATES/java/current"
  export PATH="$JAVA_HOME/bin:$SDKMAN_CANDIDATES/maven/current/bin:$PATH"
fi

SHA=""
EXPLICIT=false
case "${1:?commit sha, or --worktree <path>, required}" in
  --worktree)
    REQUESTER="${2:?worktree path required}"
    TRIGGER="${3:-manual}"
    ;;
  *)
    # Resolved here, so the index records a SHA even when given a ref like HEAD;
    # narrowed runs look their base up by SHA.
    SHA="$(git rev-parse --verify "$1^{commit}")"
    EXPLICIT=true
    REQUESTER="$(git rev-parse --show-toplevel)"
    TRIGGER="${2:-manual}"
    ;;
esac
. "$REQUESTER/scripts/ci/config"
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
  # A passing narrowed run is a valid base for the next one: its gateway code is
  # unchanged since a full green run. A failed run is never a base, so whatever
  # it changed is still counted by the next run's diff.
  SCOPE=full
  BASE=""
  # A worktree whose config predates these settings always gets the whole suite.
  # The ancestor search is bounded; past 200 commits a full run is due anyway.
  if [ "$EXPLICIT" = false ] && [ -f "$INDEX_FILE" ] \
     && [ -n "${FULL_SUITE_PATHS:-}" ] && [ -n "${E2E_MODULES:-}" ]; then
    BASE="$(git -C "$REQUESTER" rev-list --first-parent -n 200 "$SHA^" 2>/dev/null \
      | grep -m1 -Fx -f <(awk -F'\t' '$6 == "PASS" { print $2 }' "$INDEX_FILE") || true)"
    if [ -n "$BASE" ] \
       && [ -z "$(git -C "$REQUESTER" diff --name-only "$BASE" "$SHA" -- $FULL_SUITE_PATHS)" ]; then
      SCOPE=e2e
    fi
  fi
  MVN_ARGS=()
  if [ "$SCOPE" = e2e ]; then
    MVN_ARGS=(-pl "${E2E_MODULES// /,}" -am)
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
    echo "scope=$SCOPE"
    [ -z "$BASE" ] || echo "base=$BASE"
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
     && (cd "$WORKTREE_DIR" && mvn -B verify "${MVN_ARGS[@]}") >> "$RUN_DIR/log.txt" 2>&1
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

  printf '%s\t%s\t%s\t%s\t%s\t%s\t%ss\t%s\t%s\n' \
    "$(date -Is)" "$SHA" "$SUBJECT" "$BRANCH" "$WORKTREE_NAME" "$(cat "$RUN_DIR/status")" "$((END - START))" "$RUN_DIR" "$SCOPE" >> "$INDEX_FILE"
) 9>"$LOCK_FILE"
