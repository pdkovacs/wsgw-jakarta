# RELAY backpressure — handoff brief

Session of 2026-09-12, follow-up 2026-09-13. **No code was changed.** Everything
below is analysis; the working tree is as commit `9cbbf3c` left it, apart from
this brief. The 09-13 follow-up corrected §7 and §8, added §10–§12, and updated
the open decisions.

Starting point: the RELAY inbound-hop work in `9cbbf3c` is unfinished, and the
hesitation about how to finish it prompted a probe for a design gap in
`docs/backpressure.md` §2.5. The probe found one, and then a second, larger one
behind it.

---

## 1. `docs/backpressure.md` §5.4 / §5.6 are stale

*Resolved 09-13:* §5.4 and the §5.6 traceability rows now describe the `offer`
with its timeout.

§5.4 says `Dispatcher.accept` calls `queue.put()` with "no enqueue timeout". It
doesn't, as of `9cbbf3c`:

- `Dispatcher.accept` (`Dispatcher.java:41`) calls
  `queue.offer(dispatch, relayEnqueueTimeout, NANOSECONDS)`.
- `relayEnqueueTimeout` is a settable `Configuration` field, default 30s
  (`Configuration.java:37`), reaching `Dispatcher` via `QueueParams`.

So §2.5.1's enqueue-timeout row and the §5.6 row for it are `[partial]`, not
`[planned]`.

## 2. The timeout path is a silent frame drop

`offer`'s `boolean` return is discarded. On expiry the frame vanishes — no close,
no metric, no log. That is action **#3 (drop frames)**, which §2.5.1 listed on
09-12 as the last resort the message contract does not tolerate, arrived at as
the default outcome.

*Revised 09-13 under decision L:* the drop itself is now the intended outcome.
What is missing is observing it: a metric and a log when `offer` returns
`false`. The drop is *not* caused by using `offer` instead of `put`.

## 3. §2.5.1's model of the three actions is wrong

§2.5.1 presents the three actions as alternatives forked from the enqueue
timeout — "each is the **terminal step** of a congestion decision" — and the
Mermaid diagram forks all three out of `KnobEnqueueTimeout`.

That model is not implementable. **Jakarta WebSocket exposes no way to stop
reading a connection**: there is no `setAutoRead`, no suspend/resume on
`Session`. The only lever is not returning from the message handler, and Tomcat
resumes reads when it returns.

Since `Endpoint.java:46` hands each frame straight to `Relay.sendMessage` →
`Dispatcher.accept` on the container's receive thread, **blocking in `accept`
*is* action #1**. Stop-reading is the state the connection is in for the whole
duration of the wait, not an action fired when the wait expires. The model in
§2.5.1 presumes a buffer whose filling is decoupled from the reader; this
topology has exactly one producer per queue.

This is also why there was no second timer to be found: the model treats a state
(stalled) as an event, so it has nowhere to put one.

**§4's matrix already has the right shape.** The RELAY inbound row reads
`none over HTTP → stop reading socket → WS close` — an escalation chain, same
shape as the outbound row's `retry → WS close`. The document contradicts itself,
and §2.5.1 is the half to fix. Under decision L both chains keep their shape but
end differently: `stop reading → drop the frame` and `retry → drop the message`.

## 4. The two-budget reframe

*Revised 09-13 under decision L: the second budget ends in a drop, not a close.*

*Revised again 09-13: budget 1 is not adopted.* An enqueue wait timer
(`docs/backpressure.md` §2.5.1) records how long every wait lasts, which shows
stalls without a threshold to configure — the same approach PUSH takes with
`wsgw.send_lock.wait`. One budget remains: the relay enqueue timeout.

Both budgets survive; the first is relabelled from action-trigger to observation
boundary.

| Budget | Meaning |
|---|---|
| 1 | How long a stall goes **unremarked**. The client is already frozen; this is the point at which it is worth counting. Without it there is no event marking the start of a stall, which is the flow's real blind spot. |
| 2 | How long a stall may **last** before the frame in hand is dropped and reading resumes. Queued frames and the one being relayed are kept. RELAY congestion does not close the connection (decision L). |

Implementable as two sequential `offer`s — no timer threads, no interrupt path,
and the frame is held in hand until the second budget expires:

```java
if (queue.offer(dispatch, stallMarkNanos, NANOSECONDS)) return;
onStalled.run();                       // metric / log; client frozen from here
if (queue.offer(dispatch, dropBudgetNanos - stallMarkNanos, NANOSECONDS)) return;
onDropped.run();                       // metric / log; the frame is discarded
```

With budget 1 not adopted, the single form is the design rather than a first
increment: one `offer`, the wait duration recorded when it returns, and a drop
metric and log on `false`. It is today's code (§2) plus the missing observation.

**What remains of the close's mechanics.** The `Session` seam and the send-lock
check are no longer needed (decision F). One item stands on its own:

- `POISON` must not be subject to the same budget. `Relay.sendDisconnect`
  enqueues through the same `accept`; if a congested queue can time it out, the
  dispatcher thread never exits. (See §9 for why this matters beyond the thread.)

**Sizing note.** The stall does not reach the client instantly — Tomcat's read
buffer, the socket receive buffer and the client's send buffer absorb some bytes
first. That lag is measured in bytes, not seconds, so it should not be modelled
as a timeout.

## 5. Drop frames (#3): not instead of stopping reads, but at the end of the stall

*Revised 09-13 under decision L.*

The 09-12 conclusion was that drop frames could be retired: with one producer per
queue and a blocking enqueue, stop-reading plus a stall budget covers the whole
range, and dropping would only become necessary with a producer that cannot be
blocked, which this topology does not have.

That still holds for what happens *during* a stall. What changed is how the stall
ends. Under best-effort RELAY it ends by dropping the frame in hand (§4, budget 2)
instead of closing the connection PUSH needs, so drop frames stays in the
contract in that role (decision B).

---

## 6. The app is a tier, not a node

§2.1's topology sketch has one `app` node, and §2.5's escalation, as written on
09-12, assumed that node-ness: it stalled and then destroyed a client connection
on evidence that may come from one overloaded instance out of N. Under decision
L RELAY no longer closes the connection, but the tier framing below still
decides where retries land (§7).

**What the reference apps actually keep.** Both
`pdkovacs/wsgw/test/e2e/app/internal/conntrack` (Go) and
`wsgw-node-ref/app/src/conntrack` (TypeScript) implement the same interface —
`AddConnection(userId, connId)` / `RemoveConnection(userId, connId)` /
`GetConnections(userId) → []connId` — over in-memory, Postgres, Valkey or
DynamoDB.

Two things follow:

- The shared state is a **userId → connectionIds index**, and its consumer is the
  **PUSH** direction: the app looks up a user's connection ids in order to `POST`
  to `/message/{connId}`.
- The **RELAY** direction reads none of it. The Node handler's parameter is
  literally `_wsConnections`. There is no per-connection session pinned to
  whichever instance served CONNECT.

So relays are **location-transparent**, and re-routing a relay retry is safe. An
earlier concern in this session — that re-routing would need an unstated
"any instance accepts any connectionId" guarantee — is withdrawn; it holds by
construction in both references.

**The premise, stated accurately.** Not "the backend can stay stateless"
(README:3–7), but: *the instances are stateless; the tier is not.* wsgw converts
a **hard pin** (a TCP connection bound to one instance for its life) into a
**soft lookup** (a row in a store any instance can read). The `inmemory`
conntrack option is where the gloss shows — it works only for a single-instance
tier.

## 7. What that means for §2.5.2's retry

§2.5.2 specifies the retry in **time** (`relay response deadline`,
`max relay retries`, `relay retry interval`) and says nothing about **space**.
Whether retry #2 reaches a different instance is not written down anywhere.

**It depends on the layer the frontend balances at** (corrected 09-13; the 09-12
version treated the L4 case as universal):

| Frontend | Balances per | Pooled keep-alive / h2 connection pinned to one instance? |
|---|---|---|
| **L7**: AWS ALB, GCP HTTP(S) LB, nginx / Envoy / HAProxy in HTTP mode, k8s Ingress controllers, Envoy-based service mesh | **request** (or h2 stream) | **No.** The LB terminates wsgw's connection and routes each request from its own backend pool |
| **L4**: AWS NLB, plain k8s `ClusterIP` Service via kube-proxy, IPVS | **connection** | **Yes.** Retries return to the instance already known to be slow and burn the budget re-sampling it |

**Neither kind retries a timed-out relay itself.**

- nginx / Envoy / HAProxy retry policies by default exclude timeouts on
  non-idempotent methods, and a request already streamed upstream generally
  cannot be retried. ALB does not retry on timeout either.
- Outlier detection and readiness probes do eject bad instances, but on
  tens-of-seconds-to-minutes timescales — far past any relay deadline.

**Recommended topology (agreed).** An L7 frontend that really load-balances per
request. The reference case is the AWS setup: HTTP/2 from client to ALB,
HTTP/1.1 from ALB to instances. This is a deployment question, but the retry
semantics depend on it, so the docs state it as an **assumption**, together with
what happens when it fails (§12). The realistic L4 case is east-west traffic
inside k8s: wsgw calling the app's `Service` DNS name without a mesh.

Consequences:

- **Decision C dissolves.** Behind L7, reusing a pooled connection is harmless;
  every retry is already a fresh routing decision. No forced fresh connection.
- **A retry is a re-sample, not a steer.** Where it lands is the LB's routing
  policy. Round robin (ALB's default) gives a fresh pick but still sends 1/N of
  retries to a slow instance; `least_outstanding_requests` biases away from it,
  because the slow instance accumulates outstanding requests. "Lightest load" is
  an LB setting, not a property of the architecture.

**Interaction with HTTP/2 (corrected).** The 09-12 claim, that HTTP/2 multiplexes
every relay onto one connection and so pins the gateway→app hop to one instance,
holds **only behind L4**. Behind L7, HTTP/2 between wsgw and the LB changes how
many sockets wsgw holds to the LB, not how relays spread across instances. The h2
decision and retry routing are therefore **no longer coupled**. Note also: h2c
is purely a test vehicle; HTTP/2 in production is TLS h2. See §11.

**Giving up is honest behind L7.** "Retries exhausted → drop the message" behind
L4 means *one instance was slow N times*. Behind L7 it means *N independent
picks from the tier were slow*. With one bad instance out of k under round robin,
all N landing on it has probability about (1/k)^N, so the claim that *the tier
could not take this message* is reasonably justified.

## 8. Per-instance vs per-tier splits by flow

The distinction does **not** apply uniformly across §2:

- **CONNECT** has a genuine shared dependency: `addConnection` hits the store on
  every establishment, so a slow store makes CONNECT slow tier-wide. §2.4's
  `connectFailurePreemptThreshold` aggregating failures across instances is
  therefore defensible — the cause really is shared.
- **RELAY** has no shared dependency. Relay slowness is per-instance work, so it
  is far more likely to be genuinely instance-local: the case where re-routing
  helps and closing the client connection is the wrong remedy.

So §2.4 is mostly fine and §2.5 is the one that is wrong, for a reason specific
to it.

**The constraint that bounds all of this:** wsgw talks to a VIP, so it **cannot
directly observe whether "one instance is bad" or "the tier is bad"** — and that
distinction separates "retry elsewhere" from "amplify load onto an
already-saturated tier".

The 09-12 remedy, an instance identifier echoed in relay and connect responses
(decision D), is **withdrawn**:

- Behind an L7 frontend wsgw cannot address an instance, so it could not act on
  the identifier anyway.
- The retry budget (§10.1) makes the distinction statistically, without any
  contract addition.
- Per-instance diagnostics for operators already exist as the LB's per-target
  metrics (e.g. ALB's per-target response time).

## 9. Lost disconnect notifications pollute the app's index

`Endpoint.onClose` → `Relay.sendDisconnect` → **the same `Dispatcher.accept`,
the same full queue**. README:40 already calls the disconnect notification
best-effort.

Net: when a connection closes while its relay queue is full, the app may never
be told. The shared index accumulates connIds for connections that no longer
exist, and the app keeps pushing to them.

This is the `POISON`-through-a-full-queue hazard of §4, reached from the app's
side, and a stronger reason to care about it than a leaked dispatcher thread.

### 9.1 What wsgw answers, and what the app does with it

*Checked 09-13.*

- **wsgw answers 502, not 410.** `Endpoint.onClose` does not call
  `WsConnections.close` (only the app-initiated `DisconnectRequest` does), so the
  `WsConnection` stays in `conns` with its closed `Session`. A push reaches
  `sendText` on that session; the exception is not `ConnectionGone`, so
  `MessageRequest` answers 502 "failed to reach application". 410 comes only from
  the registration gate (§2.3.1).
- **The reference app evicts on 404 only.** `ApiResource.sendToUserDevices`
  removes the connId when the push returns 404. wsgw never returns 404 on this
  route, and every other status is ignored, so nothing is evicted on a failed
  push.
- **Removing the entry on close is not enough by itself.** A push to a connId
  with no entry is treated as push-before-register: it waits the full
  `registrationWaitTimeout`, answers 410, counts as a registration timeout, feeds
  the connect circuit breaker, and leaves a flagged entry in `conns` that only
  `register` or `close` removes. wsgw has to tell "closed" from "not yet
  registered" before a stale push gets a cheap 410.

Net: today the leak is monotonic.

---

## 10. Containing retry amplification

*Added 09-13.*

**The problem.** When a relay deadline expires, the original request is usually
still being worked on by the instance that received it. Abandoning it on wsgw's
side does not stop that work: app servers generally do not interrupt a running
handler when the client goes away. Retry is re-delivery (§2.5.2), so every retry
**adds** load to the tier — worst exactly when the tier is saturated.

Cancelling by closing the connection is not a remedy: it does not reliably stop
the work, and closing connections is not something wsgw wants to do in general
(but see §11 for where HTTP/1.1 does it anyway).

### 10.1 Retry budget — agreed

Cap retries as a fraction of recent relay traffic (e.g. retries ≤ 10–20% of
relays over a rolling window), on top of the per-message `max relay retries`.
Same mechanism as Envoy's and Finagle's retry budgets.

- **It separates "one slow instance" from "slow tier" without instance ids.**
  One slow instance out of k times out about 1/k of relays, and their retries
  fit in the budget. A saturated tier times out most relays, and the budget runs
  dry at once, so those messages are dropped instead of piling duplicate work
  onto the tier.
- **Per gateway instance.** Each wsgw instance sees a fair sample of the tier;
  no coordination between gateways is needed.

Net: retries are allowed where re-routing can help and cut off where they would
only add load.

### 10.2 Deadline propagation — agreed

wsgw sends the time remaining with each relay, e.g. `X-Relay-Timeout: 1500` (ms;
header name not decided). The app notes arrival time + that value as a local
deadline and, when it takes the message off its own queue, drops it unprocessed
if the deadline has passed.

- **No message ids needed.** The app never relates an original to its retry;
  each request carries its own expiry. The stale original is dropped without the
  app knowing a retry exists — wsgw has already abandoned it, and the retry is
  the live copy.
- **Relative, not absolute.** An absolute timestamp requires wsgw's and the
  app's clocks to agree. A relative value does not (same choice as gRPC's
  `grpc-timeout`); it only misses network + LB transit time, which errs toward
  the app keeping a message slightly too long.
- **Targets the common case.** Under overload most of the wasted time is queueing
  before the handler runs, not processing. Work already running is not
  cancelled.
- **Voluntary.** An app-contract addition that apps may ignore at no cost.
- **Limit.** A message dequeued just before its deadline is still processed, and
  so is its retry. It reduces duplicate work; it does not eliminate duplicates.

### 10.3 Message ID — correctness, not load

A stable id across retries lets the app detect duplicates. It matters for
correctness only: the original on instance A may complete after the retry on B
*and* after message n+1, so the app can see `n, n+1, n`. §2.5.2's "retry is
re-delivery" already puts this on the app; worth stating in the docs, no
mechanism decided.

| Mechanism | Needs | Addresses |
|---|---|---|
| Deadline header | nothing per message | wasted load: stale work skipped at dequeue |
| Message ID | stable id across retries | correctness: duplicates, out-of-order re-delivery |

## 11. HTTP/2 on the wsgw→LB hop

*Added 09-13.*

**Over HTTP/1.1, every relay timeout closes a connection.** Verified in the
OpenJDK source (current `master`; no local JDK 25 source archive was available):

- `Http1Exchange.cancelImpl` ends with `if (!upgraded) connection.close(error);`
  — a request cancelled by `HttpTimeoutException` closes its connection. The
  protocol leaves no alternative: HTTP/1.1 cannot abandon an in-flight request
  and keep the connection, since the late response would still arrive on it.
- `Stream.cancelImpl` (HTTP/2) calls `sendResetStreamFrame(...)`: only that
  stream is reset (`RST_STREAM`); the connection stays open for the others.

So with HTTP/1.1 toward the LB, each relay deadline expiry costs a connection and
a replacement (a TLS handshake in production) — most often under overload, when
timeouts are frequent.

**The case for HTTP/2 here now has two independent reasons** and, behind L7, no
load-distribution cost (§7):

1. socket count toward the app side (the original h2c motivation);
2. a timeout resets a stream instead of closing a connection.

**Decided 09-13 (decision J):** a setting for the HTTP version toward the app,
default `HTTP_2`, relying on the JDK client's own fallback to HTTP/1.1 when the
LB does not offer h2, with `HTTP_1_1` to force it. Because that fallback is
silent, the negotiated version (`HttpResponse.version()`) must be visible to
operators: logged when it changes, or as a tag on the relay latency metric.

Current code: `Request.createHttpClient()` hard-codes `HTTP_1_1`;
the `Version` overload is reachable from code only, not from configuration.

**Remaining con: TCP head-of-line blocking.** TCP delivers bytes strictly in
order; after a lost packet the receiving kernel holds back everything behind it
until the retransmission arrives. HTTP/2 multiplexes many relays over one TCP
connection, so one loss stalls every relay in flight on it — where HTTP/1.1
across many connections would stall only one.

- Duration: about one round trip with fast retransmit (sub-millisecond inside a
  data center); with a retransmission timeout, at least the minimum RTO — 200 ms
  by Linux default, tunable per route (`ip route … rto_min`).
- Losses between wsgw and an LB in the same VPC are rare, so the average cost is
  negligible. The effect that matters is **correlation**: a stall makes many
  relays late at once. With a relay deadline near the minimum RTO, one lost
  packet can expire a batch of relays together and drain the retry budget on a
  false alarm.
- It is therefore a constraint on how short the relay deadline may be (and on the
  retry budget window, §12), not an argument against HTTP/2.
- HTTP/3 (QUIC) removes it; noted for completeness only.

**Not an h2 issue: what the LB does with an abandoned request.** The LB forwarded
the relay to an instance over HTTP/1.1, which cannot cancel one request. On an
abandon — `RST_STREAM` under h2, a closed connection under h1 — the LB either
closes its connection to the instance (the handler usually keeps running anyway)
or lets the instance finish and discards the response. nginx does the former by
default (`proxy_ignore_client_abort` switches it); ALB's behaviour is unknown.
The question is identical under both HTTP versions, and it hardly matters:
neither option reliably stops running work, and §10.2 covers queued work.

## 12. Documentation to write

*Added 09-13.* Both halves depend on settings that do not exist yet: the HTTP
version toward the app is not configurable, and the §2.5.2 relay knobs are not in
`Configuration` (still `[planned]`).

**Public (operators, app authors):**

- The recommended topology (§7) as an **assumption the retry semantics depend
  on**, with the failure spelled out: behind L4, retries re-sample the same slow
  instance, so a dropped message again means "one instance was slow N times".
- The LB routing policy decides where a retry lands; `least_outstanding_requests`
  steers away from slow instances, round robin does not.
- Next to the relay response deadline: with HTTP/2 toward the app, do not set it
  near the TCP minimum retransmission timeout. State the mechanism; give 200 ms
  only as the Linux default, tunable per route.
- For app authors: the deadline header (§10.2) and re-delivery semantics /
  message id (§10.3).

**Internal (implementors, choosing defaults):**

- **Relay response deadline default:** well above the minimum RTO — seconds, not
  hundreds of milliseconds.
- **Retry budget window:** long enough to absorb one head-of-line burst, so that
  a single lost packet does not look like a saturated tier and cut off retries
  for the whole window.

---

## Open decisions

None as of 09-13.

## Settled decisions

| # | Decision | Outcome | Status |
|---|---|---|---|
| A | Rewrite §2.5.1 (prose, knob table, diagram with stop-read upstream of the knob) to §4's model: stop reading, then drop the frame in hand | Agreed as a good increment. No longer waits for the §2.1 "app is a tier" framing: that framing mattered for what a close means, and RELAY no longer closes (decision L) | Done 09-13 (`docs/backpressure.md` §2.5.1) |
| B | Demote #3 drop frames to "considered and rejected" | **Re-settled 09-13 under L: no.** Dropping is still not an alternative to stopping reads, but it replaces the close: when the enqueue timeout expires, the frame the receive thread holds is dropped and reading resumes. Frames already queued, and the one being relayed, are kept | Docs done 09-13 (`docs/backpressure.md` §2.5.1). The code already drops the frame (§2) but has no metric or log for it |
| C | Should the relay retry re-route (force a fresh connection rather than reuse the pool)? | **Resolved 09-13: no.** Behind the recommended L7 frontend every retry is already a fresh routing decision (§7) | Nothing to do |
| D | Should the app contract require an instance identifier in responses? | **Withdrawn 09-13.** wsgw cannot act on it behind L7; the retry budget makes the distinction (§8, §10.1) | Nothing to do |
| E | Do the reference apps evict from the index on 410? | **Checked 09-13: no.** They evict on 404 only (§9.1). One item added: the reference apps evict on 410 as well as 404. That alone does not clear connections the client closed: wsgw answers those with 502, and answering 410 instead requires wsgw to tell "closed" from "not yet registered" (§9.1) | Reference apps: done (evict on 404 and 410). No item covers the wsgw side yet |
| F | `Session` seam for the close, threaded `Endpoint` → `Relays` → `Relay` → `Dispatcher`, plus the send-lock check | **Withdrawn 09-13 under L.** RELAY congestion no longer closes the WebSocket, so there is no close to thread through | Nothing to do |
| G | Retry budget on relay retries | **Agreed 09-13** (§10.1) | Specified 09-13 in `docs/backpressure.md` §2.5.2; not implemented |
| H | Deadline header on relays, honoured voluntarily by the app | **Agreed 09-13**; app-contract addition (§10.2) | Specified 09-13 in `docs/backpressure.md` §2.5.2 (header name not decided); not implemented |
| I | Message ID on relays for duplicate detection | **Settled 09-13 under L: no.** Duplicates are allowed; wsgw adds no message ID | Nothing to implement. `docs/backpressure.md` §2.5.2 says duplicates can occur and no message id is attached |
| J | HTTP/2 on the wsgw→LB hop | **Settled 09-13: yes, as a setting.** The HTTP version toward the app becomes configurable, default `HTTP_2`. The JDK client falls back to HTTP/1.1 on its own when the LB does not offer h2; `HTTP_1_1` forces it (e.g. behind L4). The fallback is silent, so the negotiated version must be visible to operators: logged when it changes, or as a tag on the relay latency metric (not chosen) (§11) | Not implemented: `Request.createHttpClient()` hard-codes `HTTP_1_1`. Operator docs per K |
| K | Operator and implementor documentation per §12 | **Agreed 09-13** | Not written; depends on the settings existing |
| L | Delivery guarantee for RELAY | **Settled 09-13: best-effort.** PUSH is where the value is; RELAY is there for completeness and gives a client little that a plain REST endpoint could not. Messages may be lost under congestion and duplicated on retry; order within a connection is kept where cheap. RELAY congestion never closes the WebSocket, because PUSH needs it: when the enqueue timeout expires, the frame the receive thread holds is dropped and reading resumes; when retries run out, the message is dropped | Reflected 09-13 in `docs/backpressure.md` §2.5, §3, §4 and §5.4. Code lacks the relay metrics and the retry path |

## Suggested next step

Implement §2.5.1's metrics in `Dispatcher.accept`: record the enqueue wait time
around the `offer`, and count and log a drop when it returns `false`. The docs
already specify both, and it turns today's silent drop into the designed one.
