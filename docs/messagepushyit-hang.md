# MessagePushyIT — status

## Issue

`MessagePushyIT.sendReceiveMessagesFromAppMultipleClientsPushy` (1000 clients × 1000
messages each way) would sometimes hang indefinitely under constrained resources
(originally: 4-core/32GB-class host) instead of failing, producing no report and no
diagnostic artifacts.

## Current status: the hang is fixed; a real transport-failure rate is now the open question

Three independent defects combined to turn a bounded failure into a silent, unkillable
hang. All three are fixed and verified on a live repro run:

1. **No push timeout** (`WebsocketTestClient.postMessageFromApp`) — a lost HTTP/2 response
   was unrecoverable by construction. Fixed: `pushRequestTimeout` is now a required
   (`Objects.requireNonNull`-checked) field threaded from `WsgwTestContext.setUp(...)`
   through `WsTestClients` to `WebsocketTestClient`; `MessagePushyIT` sets it to 60s.
2. **The `@Timeout` interrupt was swallowed** — `Semaphore.acquire()` on the main thread
   consumed the interrupt before an enclosing try-with-resources executor's `close()` saw
   it, so workers were never interrupted and `close()` looped forever. Fixed: the
   submission loop now throttles via `inFlight.acquire()` per push and drains with a single
   bulk `inFlight.acquire(MAX_IN_FLIGHT_PUSHES)`
   ([MessagePushyIT.java:141,154](../wsgw/src/test/java/io/github/pdkovacs/wsgw/integration/MessagePushyIT.java#L141)),
   which propagates the interrupt cleanly (confirmed: `InterruptedException` appears as
   *Suppressed* under the `TimeoutException` in the report, not lost).
3. **Teardown itself could hang independently of the test body** — `WsgwTestContext.tearDown()`
   stopped the server before closing test clients, so each client's graceful
   `HttpClient.close()` waited on a peer that was already gone; `WsTestClients` closes
   clients sequentially, so this could serialize into a very long wait. Fixed two ways:
   - `wsTestClients.close()` now runs before `wsgw.stop()`/`fakeApp.stop()` in
     `WsgwTestContext.tearDown()`.
   - `WebsocketTestClient.close()` uses `httpClient.shutdownNow()` instead of the graceful
     `httpClient.close()`, so teardown no longer depends on an orderly drain at all — the
     first fix alone was not sufficient (reproduced hanging in exactly this spot even with
     clients closed first) until this one landed too.

Latest run: the test fails cleanly at the 300s `@Timeout`, teardown adds under a second
(365.3s total vs. 364.8s test time), and the `PushyStatsOnFailure` watcher fires correctly.
Its snapshot log line is easy to miss — the report XML runs to several MB of captured debug
output — so grep for it rather than scrolling:

```
grep -n "Pushy load" wsgw/target/failsafe-reports/TEST-*MessagePushyIT.xml
```

That snapshot is the actual open problem now: a **13.2% transport failure rate**
(210,042 / 1,595,127 attempts), breakdown `SocketException=150969, ConnectException=43813,
IOException=14468, EOFException=792`. This is a different signature than originally
theorized (a single dropped HTTP/2 response with sockets otherwise idle) — it looks more
like connection-level exhaustion under load than an isolated response loss. Not yet
diagnosed further; this is where the next investigation session should pick up.

## Repro

Via Docker (`docker/pushy-it.Dockerfile`) — matches the original constrained host (CPU quota,
core-count pinning, memory cap) with no per-run authentication and no dependency on the host's
systemd/polkit setup:

```
docker build -f docker/pushy-it.Dockerfile -t wsgw-pushy-it .

docker run --rm \
    --cpus=1.2 --cpuset-cpus=0-3 --memory=32g \
    --user "$(id -u):$(id -g)" \
    -v "$(pwd)":/workspace \
    -v "$HOME/.m2/repository":/m2repo \
    -w /workspace \
    wsgw-pushy-it \
    mvn -Dmaven.repo.local=/m2repo -pl wsgw '-Dit.test=*Pushy*' verify
```

The build only needs to happen once; the source is bind-mounted, not baked in, so code edits
don't require a rebuild. `--user "$(id -u):$(id -g)"` keeps `target/` output owned by you
rather than root.

This is a targeted repro tool, not a general substitute for how you'd normally run tests. Two
gaps to know about: it never references `~/.m2/settings.xml`, only `~/.m2/repository` — any
mirror/proxy/auth config a host's Maven setup depends on is silently absent inside the
container, so this only works unmodified where Maven Central is directly reachable. And there's
no IDE integration — it's a raw `docker run`, so it can't feed a Test Results panel or show live
progress the way running from IntelliJ/VS Code would. Fine for "did the fix hold," not a
replacement for routine local test-running.

Previously this used `systemd-run --scope -p CPUQuota=120% -p AllowedCPUs=0-3 -p MemoryMax=32G`
directly on the host. That still works, but asks for a password on every single invocation
(polkit authorization for the system-level scope, uncached between runs) — the reason for
moving to Docker. `systemd-run --user --scope` avoids the prompt but silently drops
`AllowedCPUs` (the `cpuset` controller isn't delegated to user sessions on a stock setup), which
matters here: it's what pins the JVM's scheduling contention to a handful of run-queues to
reproduce the original host's behavior, not just its aggregate CPU-time budget. Docker's daemon
runs as root, so it applies `--cpuset-cpus` directly with no delegation gap and no prompt.

Whichever way it's invoked, it must be `-Dit.test`, not `-Dtest` — Failsafe's `test` parameter
has no `-Dtest` alias, and since `MessagePushyIT` also matches Surefire's default include
pattern, `-Dtest` runs it twice (once uninstrumented via Surefire, once via Failsafe), roughly
doubling wall time.

## Proving the retry fix

The connection-death race the retry in `WebsocketTestClient.postMessageFromApp` guards against
is intermittent — a clean run proves nothing, since it may just mean the race didn't fire. A
fixed batch of runs is the wrong shape too: either it's too small to catch the race, or it keeps
running well past the point a single occurrence would already answer the question. What actually
answers it is one caught occurrence where the retry fires and the run still passes — so loop
until that happens (or a cap is hit), not a fixed count of times:

```
SCRATCH=$(mktemp -d)
MAX_TRIES=15
found=0
for i in $(seq 1 $MAX_TRIES); do
  echo "=== RUN $i ==="
  docker run --rm \
      --cpus=1.2 --cpuset-cpus=0-3 --memory=32g \
      --user "$(id -u):$(id -g)" \
      -v "$(pwd)":/workspace \
      -v "$HOME/.m2/repository":/m2repo \
      -w /workspace \
      wsgw-pushy-it \
      mvn -Dmaven.repo.local=/m2repo -pl wsgw '-Dit.test=*Pushy*' verify > "$SCRATCH/proof_run_$i.log" 2>&1
  exit_code=$?
  cp wsgw/target/failsafe-reports/TEST-io.github.pdkovacs.wsgw.integration.MessagePushyIT.xml "$SCRATCH/proof_report_$i.xml"
  echo "exit: $exit_code"
  grep -n "Pushy load done\|Connect failure" "$SCRATCH/proof_report_$i.xml"
  if grep -q "Push failed, retrying" "$SCRATCH/proof_report_$i.xml"; then
    echo ">>> RETRY EVENT CAUGHT on run $i <<<"
    found=1
    break
  fi
done
echo "=== done: found=$found after $i runs ==="
```

The report is copied out per iteration before the next run overwrites it — without that, the
one run that actually proves anything is exactly the one whose detail gets lost. Last confirmed
run: caught on attempt 5 of 15, 10 concurrent `EOFException`s on one connection, all retried,
`transport failures=0 (0.0000%)` in the final tally.
