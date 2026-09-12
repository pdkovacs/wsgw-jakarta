# wsgw-jakarta

## Local CI

Every commit kicks off a background build-and-test run of that commit. It is wired through
`core.hooksPath`, which `scripts/ci/install.sh` points at `scripts/ci/hooks` — so `.git/hooks/`
is empty. Do not read that as "no CI installed"; check `git config core.hooksPath`.

- **What runs**: whatever `scripts/ci/run.sh` invokes — currently `mvn -B verify`, i.e. the whole
  suite, unit tests and ITs alike. Read the script rather than assuming. As more stress-style tests
  land this may split into tiers, and at that point a green run stops implying the slow ones passed.
- **When**: on `post-commit`, `post-merge` and `post-rewrite`, gated on `WATCH_PATHS` in
  `scripts/ci/config` (currently `wsgw e2e wsgw-contract`). A commit touching only docs triggers
  nothing, so silence there does not mean "still running". `post-commit` deliberately stays quiet
  during an interactive rebase; `post-rewrite` covers the rebase as a whole.
- **What it tests**: the committed SHA, checked out into a disposable worktree. Uncommitted
  working-tree changes are not covered — running tests on those is still your job, before the commit.
- **Results**: `scripts/ci/status.sh` for recent runs, `scripts/ci/status.sh log` for the latest
  full log. Runs are serialized by a lock, so a commit made while one is in flight queues behind it.
  Output lives under `/tmp/wsgw-ci` (override with `WSGW_CI_HOME`) and is ephemeral across reboots.

After the user commits, check `status.sh` rather than offering to re-run the suite yourself.
