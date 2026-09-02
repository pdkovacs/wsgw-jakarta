# MessagePushyIT hang — investigation findings

Investigated 2026-09-01/02 against the code as of `705645c` (pre-`2cef471`).
Subject: test JVM **PID 21380**, a Failsafe fork that never terminated and had to be killed.

> **Line numbers** in this document refer to the code that was actually running
> (`2cef471^`). `2cef471` shifted some of them — notably `WsTestClients.java`
> 51 → 53 and 54 → 56.

---

## 1. Summary

`MessageIT` was never the problem. It passed cleanly in both runs. The hang was
`MessagePushyIT.sendReceiveMessagesFromAppMultipleClientsPushy`, and it was a genuine
permanent wedge, not slowness.

Three independent defects compounded:

| # | Defect | Effect |
|---|---|---|
| A | Push requests have no timeout | A lost response is unrecoverable by construction |
| B | The JUnit `@Timeout` interrupt is swallowed | The test can never fail out; it hangs forever |
| C | Something loses HTTP/2 requests under load | **Root cause — still unexplained** |

A and B are test-harness defects and are fully understood. C is not, and A+B are the
reason it went undiagnosed: they turn a bounded, reportable failure into a silent
28-minute hang with no artifacts.

---

## 2. What was *not* wrong: `MessageIT`

The report that started the investigation is complete and green in both runs. It is only
large (66 KB) because every `<testcase>` carries a full `<system-err>` dump.

| Run | Result | Total | Slowest test |
|---|---|---|---|
| Sep 1 07:52 | 5 tests, 0 failures, 0 errors | 6.562 s | `sendReceiveMessagesFromAppMultipleClients` 6.204 s |
| Sep 2 07:26 | 5 tests, 0 failures, 0 errors | 5.355 s | `sendReceiveMessagesFromAppMultipleClients` 4.972 s |

---

## 3. The actual hang

### 3.1 Main thread

PID 21380, elapsed 1687 s, state `TIMED_WAITING`:

```
java.util.concurrent.CountDownLatch.await(CountDownLatch.java:276)
java.util.concurrent.ThreadPerTaskExecutor.awaitTermination(ThreadPerTaskExecutor.java:159)
java.util.concurrent.ThreadPerTaskExecutor.awaitTermination(ThreadPerTaskExecutor.java:173)
java.util.concurrent.ThreadPerTaskExecutor.close(ThreadPerTaskExecutor.java:189)
io.github.pdkovacs.wsgw.integration.MessagePushyIT
    .sendReceiveMessagesFromAppMultipleClientsPushy(MessagePushyIT.java:108)
```

Line 108 is the `try (var sendExec = ...)` of phase 2. Main is in the implicit `close()`,
awaiting termination of the send executor.

### 3.2 `jstack` alone is actively misleading here

Of 1045 threads, **1002 were `HttpClient-N-SelectorManager`** — daemon *platform* threads,
one per `HttpClient` instance, all `RUNNABLE` in `EPoll.wait`. That is their normal idle
resting state. They are not what `close()` is waiting for.

**Unmounted virtual threads do not appear in `jstack` at all.** They park via
`VirtualThread.park`, never `EPoll.wait`. The threads that mattered were invisible.

### 3.3 The real picture — `jcmd Thread.dump_to_file -format=json`

Container `java.util.concurrent.ThreadPerTaskExecutor@7034317a` held **273 live virtual
threads**, every one with an identical stack:

```
java.lang.VirtualThread.park(VirtualThread.java:745)
java.util.concurrent.CompletableFuture.get(CompletableFuture.java:2093)
jdk.internal.net.http.HttpClientImpl.send(HttpClientImpl.java:902)
io.github.pdkovacs.wsgw.integration.WebsocketTestClient
    .postMessageFromApp(WsTestClients.java:54)
io.github.pdkovacs.wsgw.integration.MessagePushyIT
    .lambda$sendReceiveMessagesFromAppMultipleClientsPushy$2(MessagePushyIT.java:135)
```

Blocked on an **untimed** `CompletableFuture.get()`, waiting for an HTTP/2 response.

### 3.4 Wedged, not slow

Two JSON dumps 45 s apart: **the same 273 thread IDs, zero finished, zero started.**

---

## 4. Why it could never recover

### A. No request timeout

`WsTestClients.java:51`:

```java
//                .timeout(Duration.ofSeconds(1))
```

`git log -L` shows this line was **born commented out**, in
`e591fb4 "Tests for scaled up traffic / pushing it up reeeeally high"`. It was never once
active. It was not forgotten — it was written and immediately disabled during scale-up
work, because 1 s is far too tight once the offered load goes up.

For contrast, `Http2NegotiationSpikeIT.java:84` does set `.timeout(Duration.ofSeconds(5))`.

### B. `@Timeout(300)` fired, and the interrupt was swallowed

It did fire. The `junit-jupiter-timeout-watcher` was in an **untimed**
`DelayedWorkQueue.take()` — i.e. its queue was empty, so the scheduled interrupt task had
already run. Yet main was still waiting at t=1687 s and the 273 workers were never
interrupted.

The mechanism, confirmed against the JDK 25 source
(`java.base/java/util/concurrent/ThreadPerTaskExecutor.java`):

```java
private void awaitTermination() {
    boolean terminated = isTerminated();
    if (!terminated) {
        tryShutdownAndTerminate(false);          // does NOT interrupt workers
        boolean interrupted = false;
        while (!terminated) {
            try {
                terminated = awaitTermination(1L, TimeUnit.DAYS);
            } catch (InterruptedException e) {
                if (!interrupted) {
                    tryShutdownAndTerminate(true);   // interrupts workers — only here
                    interrupted = true;
                }
            }
        }
        ...
```

`tryShutdownAndTerminate(true)` — the variant that interrupts workers — is reachable
**only** from the `InterruptedException` catch. But `inFlight.acquire()` at
`MessagePushyIT.java:131` runs on the *main thread inside the try-with-resources*, and
`Semaphore.acquire()` consumed the interrupt and cleared the flag. Unwinding then entered
`close()` with no flag set, so it took the `false` path and looped on
`awaitTermination(1, DAYS)` forever.

That is why the workers were never interrupted and why the process had to be killed.

### C. Nothing was actually in flight

Three independent checks, all agreeing:

- **Sockets:** 2000 `ESTAB`, **every one with `Send-Q=0` and `Recv-Q=0`**. Nothing pending
  on the wire in either direction. (4087 open fds; 2 `LISTEN`.)
- **Server side fully idle:** 1000 `dispatcher + <connId>` virtual threads parked in
  `LinkedBlockingQueue.take()` at `Dispatcher.java:53` on **empty** queues, and **zero**
  Tomcat `exec` threads.
- **No progress:** identical thread set across 45 s.

So the client believed 273 requests were outstanding while the server had no work and no
bytes were pending. Those requests were either never written (queued inside `HttpClient`
above the socket layer) or their responses were dropped without completing the future.

**This is the unexplained part, and it is the only one that might not be a test defect.**

---

## 5. Secondary findings

### 5.1 `HttpClient` leak — `WsTestClients.close()` is never called

`WsTestClients.close()` exists and correctly closes each client's session, container and
`HttpClient`. It is called from **nowhere** in the repository. `WsgwTestContext.tearDown()`
closes `wsgw`, `fakeApp` and its own `httpClient`, and stops there.

This is exactly what the dump showed: 1002 `HttpClient-N-SelectorManager` threads still
alive, with their sockets, long after the tests that created them.

### 5.2 One connection burned 182× the CPU of any other

CPU across the 1002 selector threads:

```
count=1002   total=222595 ms
min=3.62   p50=211.68   p90=271.03   p99=299.44   max=60377.81
```

`HttpClient-154` alone accounted for **60.4 s — 27 % of all selector CPU**, against a
median of 212 ms and a next-highest of 331 ms. Accumulating that much while parked in
`EPoll.wait` at dump time means it had been **busy-spinning** — a channel left perpetually
"ready", e.g. a cancelled key not reaped or a half-closed socket.

Not proven to be the connection holding the 273 stuck requests; the dump does not
correlate virtual threads to connections.

### 5.3 The downgraded host silently halved the fork's heap

Neither POM sets `argLine` or `-Xmx`, so Failsafe forks run at the JVM default
`MaxRAMPercentage=25`:

```
MaxHeapSize = 8355053568   (~7.8 GiB)   on this 4-core / 31 GiB host
```

Moving to a host with half the memory therefore halved the fork's maximum heap with no
config change and no visible signal. `MAX_IN_FLIGHT_PUSHES = 20000` was tuned against the
old heap; its own comment says the cap exists to avoid "GC oblivion".

This matters beyond slowness: an `OutOfMemoryError` raised inside an `HttpClient` internal
handler leaves its `CompletableFuture` permanently uncompleted — which matches the
symptom exactly (server already done, bytes already off the socket, future never
completes, no timeout to break out). Unconfirmed: no `hs_err` log, no core, no heap dump.

### 5.4 A later run died rather than hung

The Sep 2 07:26–07:28 run produced **no `MessagePushyIT` report at all**, plus
`2026-09-02T07-26-25_686-jvmRun1.dumpstream`:

```
TestSet has not finished before stream error has appeared
  >> initializing exit by non-null configuration: EXIT
java.io.EOFException
```

That fork **vanished** — a different signature from the Sep 1 fork, which hung and stayed
alive. No `hs_err_pid*.log`, no core, no `.hprof`.

---

## 6. Recommended solutions

### R1 — Give the push a finite, tunable timeout *(fixes A)*

The value must differ between the functional tests and the stress test, so a single
hardcoded constant will not do. But a bare required parameter does not fix the defect
either: the defect is that *no timeout* was **expressible** and was the **default**. What
prevents recurrence is a safe non-null default that can only be tuned, never opted out of.

The value does not vary per client — it varies per test class. So it belongs on the
fixture, not in `connect()`'s signature (which already has a two-overload with/without
`readyLatch`; adding a `Duration` there gives overload combinatorics for something
constant within a run).

- Add `Duration pushTimeout` as a `WebsocketTestClient` record component — that is where
  it is read, and `of()` has exactly one caller.
- Populate it from a `WsTestClients` field set in the constructor, with
  `WsTestClients()` delegating to `WsTestClients(Duration)`.
- Do the same one level up in `WsgwTestContext`, so only `MessagePushyIT` overrides.
- `Objects.requireNonNull(pushTimeout)` in the compact constructor, so "no timeout" stops
  being representable.

**Picking the number — liveness, not latency.** Queueing time inside `HttpClient` counts
toward the per-request timeout: a request waiting for an h2 stream slot is already on the
clock. With `MAX_IN_FLIGHT_PUSHES = 20000` a 1 s bound fails en masse, and
`MAX_TRANSPORT_FAILURE_RATE = 0.0` turns every one of those into a red build — which is
almost certainly why the line was commented out in the first place.

In the stress test the timeout's job is to guarantee **liveness**, not to enforce a latency
SLA. Pick something well clear of worst-case queueing (~60 s). It still converts a
28-minute silent hang into a bounded failure with a readable per-client distribution.
Reserve tight values (1–5 s) for the functional tests, where a slow push *is* the defect.

### R2 — Let the timeout interrupt actually reach the workers *(fixes B)*

The operative change is removing the try-with-resources whose `close()` converts an
interrupt into an infinite wait — not "virtual threads instead of executor".

**Option 1 — fork explicitly, rejoin at a barrier.** Use the semaphore itself as the join,
so no million `Thread` references are held:

```java
for (ClientTestCtx ctx : processed) {
    var client = ctx.testClient();
    for (int i = 0; i < nrMessagesToSend; i++) {
        inFlight.acquire();
        attempts.increment();
        Thread.ofVirtual().start(() -> {
            try { ctx.ackedToClient().add(client.postMessageFromApp()); }
            catch (Throwable t) { transportFailures.add(t); }
            finally { inFlight.release(); }
        });
    }
}
inFlight.acquire(MAX_IN_FLIGHT_PUSHES);   // drains: every push has released
```

That bulk acquire is interruptible and propagates, so `@Timeout` does what it says.
(A `CountDownLatch(nrClients * nrMessagesToSend)` reads more obviously if you prefer
intent over reuse.)

The client→app loop is *not* governed by the semaphore — keep its 1000 `Thread` handles
and `join()` them. Leaving that loop in a try-with-resources executor reintroduces the same
trap one loop earlier.

Per the JDK: *"Virtual threads are daemon threads and so do not prevent the [JVM from
exiting]"*, and *"The daemon status of a virtual thread is always true and cannot be
changed."* So once main stops blocking, the fork exits cleanly even with sends still
wedged. Today it is main itself that pins the JVM.

The trade-off is losing the `shutdownNow()` handle: wedged sends keep their `HttpClient`s
and sockets until JVM exit.

**Option 2 — keep the executor, interrupt explicitly.** Smaller change, keeps the handle:

```java
try (var sendExec = Executors.newVirtualThreadPerTaskExecutor()) {
    try { /* both loops */ }
    catch (InterruptedException e) { sendExec.shutdownNow(); throw e; }
}
```

Either is correct. Note this fixes *harness liveness*, not the wedge: the pushes still
never complete, you just fail at 300 s instead of hanging. R1 gives the diagnosis; R2 makes
a report possible at all. Land both.

### R3 — Close the test clients *(fixes 5.1)*

Add `wsTestClients.close()` to `WsgwTestContext.tearDown()`. One line; stops leaking an
`HttpClient`, a selector thread and a socket per client per test.

### R4 — Make memory failures visible *(addresses 5.3)*

Add to the Failsafe `argLine`:

```
-XX:+HeapDumpOnOutOfMemoryError -Xlog:gc
```

and consider pinning `-Xmx` explicitly so fork heap stops silently tracking host RAM.
Halve `MAX_IN_FLIGHT_PUSHES` to match the halved memory; if the wedge disappears, the
memory path is implicated.

### R5 — Then chase the loss mechanism *(the open question, C)*

Only after R1+R2 are in, because until then every run costs 28 minutes and yields nothing.

With a finite timeout the failures become a distribution: how many clients, how many
pushes each, clustered on a few connections or spread evenly. Log the `connId` on timeout —
the thread dump cannot correlate virtual threads to connections, and that correlation is
the missing link between the stuck requests and the spinning selector in 5.2.

---

## 7. Diagnostic recipe

For next time, in order:

1. **`jstack <pid>`** — read the *main* thread only. Everything else is likely noise:
   `HttpClient-N-SelectorManager` threads idle in `EPoll.wait` are normal, and unmounted
   virtual threads are **not shown at all**.
2. **`jcmd <pid> Thread.dump_to_file -format=json <file>`** — this is where the truth is.
   Find the `ThreadPerTaskExecutor@...` container; those are the tasks `close()` awaits.
   ⚠️ The file is written relative to the **target JVM's** cwd, not yours. Pass an absolute
   path.
3. **Two dumps 45–60 s apart** — compare the live `tid` set. Identical set = wedged;
   changing set = merely slow. This single check separates the two diagnoses.
4. **`ss -tanp | grep <pid>`** — non-empty `Send-Q`/`Recv-Q` means real backpressure;
   all-zero means nothing is on the wire and the stall is above the socket layer.
5. **Server-side threads** — idle dispatchers on empty queues plus zero Tomcat `exec`
   threads means the requests never arrived.
6. **CPU skew across selector threads** — normalise against the *median*, not the
   next-highest. A thread parked in `EPoll.wait` with orders-of-magnitude more CPU than the
   median has been busy-spinning.

Note that scratchpad dumps under `/tmp` do not survive a reboot. Copy anything worth
keeping somewhere durable before the box cycles.
