# RELAY backpressure — handoff brief

Session of 2026-09-12. **No code or documentation was changed.** Everything below
is analysis; the working tree is as commit `9cbbf3c` left it.

Starting point: the RELAY inbound-hop work in `9cbbf3c` is unfinished, and the
hesitation about how to finish it prompted a probe for a design gap in
`docs/backpressure.md` §2.5. The probe found one, and then a second, larger one
behind it.

---

## 1. `docs/backpressure.md` §5.4 / §5.6 are stale

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
no metric, no log. That is action **#3 (drop frames)**, which §2.5.1 lists as the
last resort the message contract does not tolerate, arrived at as the default
outcome.

Acting on that boolean is the whole of the missing behaviour. The drop is *not*
caused by using `offer` instead of `put`.

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

**§4's matrix already has the right model.** The RELAY inbound row reads
`none over HTTP → stop reading socket → WS close` — an escalation chain, same
shape as the outbound row's `retry → WS close`. The document contradicts itself,
and §2.5.1 is the half to fix.

## 4. The two-budget reframe

Both budgets survive; the first is relabelled from action-trigger to observation
boundary.

| Budget | Meaning |
|---|---|
| 1 | How long a stall goes **unremarked**. The client is already frozen; this is the point at which it is worth counting. Without it there is no event marking the start of a stall, which is the flow's real blind spot. |
| 2 | How long a stall may **last** before the connection is declared undrainable → close **1013** (`TRY_AGAIN_LATER`), matching §2.2's code choice. |

Implementable as two sequential `offer`s — no timer threads, no interrupt path,
and the frame is held in hand throughout, so it is never dropped:

```java
if (queue.offer(dispatch, stallMarkNanos, NANOSECONDS)) return;
onStalled.run();                       // metric / log; client frozen from here
if (queue.offer(dispatch, closeBudgetNanos - stallMarkNanos, NANOSECONDS)) return;
onUndrainable.run();                   // close 1013
```

Collapsing to a single `offer` plus `if (!…) close` is the smaller first
increment.

**Mechanics the close needs:**

- A seam to the `Session`. `Dispatcher` holds an `ErrorChannel` but no session
  handle; the callback must be threaded `Endpoint` → `Relays` → `Relay` →
  `Dispatcher`, the same shape as `WsConnection`'s `onRegistrationTimeout`.
- A check against the send lock. The close frame is a write, and a push may be
  writing to the same `Session` concurrently — the constraint `WsConnection`
  serializes sends for. Verify rather than assume Tomcat serializes it.
- `POISON` must not be subject to the same budget. `Relay.sendDisconnect`
  enqueues through the same `accept`; if a congested queue can time it out, the
  dispatcher thread never exits. (See §7 for why this matters beyond the thread.)

**Sizing note.** The stall does not reach the client instantly — Tomcat's read
buffer, the socket receive buffer and the client's send buffer absorb some bytes
first. That lag is measured in bytes, not seconds, so it should not be modelled
as a timeout.

## 5. Drop frames (#3) can be retired from the contract

Nothing above needs it. With one producer per queue and a blocking enqueue,
stop-reading plus a stall budget covers the whole range; dropping would only
become necessary with a producer that cannot be blocked, which this topology does
not have. §2.5.1 currently lists it as a live last resort — it could be demoted
to "considered and rejected, with the reason".

---

## 6. The app is a tier, not a node

§2.1's topology sketch has one `app` node, and §2.5's escalation assumes that
node-ness: it stalls and then destroys a client connection on evidence that may
come from one overloaded instance out of N.

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
Whether retry #2 reaches a different instance is decided entirely by the HTTP
client's connection pooling, which is written down nowhere. Over a pooled
keep-alive connection it returns to the instance already known to be slow — so
the retries burn the whole budget re-sampling one backend, then close the
connection. In precisely the scenario retries exist for, they achieve nothing.

**The cluster frontend will not re-route opaquely.**

- A k8s `Service` balances per *connection*, not per request; a pooled connection
  is pinned to one pod for its life, and there is no retry layer.
- nginx / Envoy / HAProxy retry policies by default exclude timeouts on
  non-idempotent methods, and a request already streamed upstream generally
  cannot be retried.
- Outlier detection and readiness probes do eject bad instances, but on
  tens-of-seconds-to-minutes timescales — far past any relay deadline.

**Interaction with the h2c plan.** HTTP/2 multiplexes every relay onto one
connection, so the frontend pins the whole gateway→app leg to a single instance.
That is the LB-pinning tradeoff already noted for the h2c move, and it is in
direct tension with re-routing retries. The two should be decided together.

**Only then is the close honest.** "Retries exhausted → close" currently means
*one instance was slow N times*. After re-routing it means *the tier could not
take this message* — a claim that actually justifies destroying a connection.

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

**The constraint that bounds all of this:** wsgw talks to a VIP. Unless the app
echoes an instance identifier, the gateway **cannot distinguish "one instance is
bad" from "the tier is bad"** — and that distinction separates "retry elsewhere"
from "amplify load onto an already-saturated tier". An instance id on relay and
connect responses is the cheapest enabling step, and it is an app-contract
addition.

## 9. The close escalation pollutes the app's index

`Endpoint.onClose` → `Relay.sendDisconnect` → **the same `Dispatcher.accept`,
the same full queue**. README:40 already calls the disconnect notification
best-effort.

Net: exactly when the gateway destroys connections, it is least able to tell the
app it did. The shared index accumulates connIds for connections that no longer
exist; the app pushes to them; wsgw answers **410 Gone** (§2.3.1).

This is the `POISON`-through-a-full-queue hazard of §4, reached from the app's
side, and a stronger reason to care about it than a leaked dispatcher thread.

---

## Open decisions

| # | Decision | State |
|---|---|---|
| A | Rewrite §2.5.1 (prose, knob table, diagram with stop-read upstream of the knob) to §4's escalation model | Agreed as a good increment; **not done**. Should land with or after the §2.1 "app is a tier" framing, since that changes what *close* means |
| B | Demote #3 drop frames to "considered and rejected" | Leaning yes, possibly permanently |
| C | Should the relay retry re-route (force a fresh connection rather than reuse the pool)? | Open; interacts with h2c, decide together |
| D | Should the app contract require an instance identifier in responses? | Open; without it §8's distinction is unobservable |
| E | Do the reference apps evict from the index on 410? | **Unchecked.** If they don't, §9's leak is monotonic |
| F | `Session` seam for the close, threaded `Endpoint` → `Relays` → `Relay` → `Dispatcher`, plus the send-lock check | Design settled, not implemented |

## Suggested next step

The §2.1 framing ("the app is a tier") before or together with the §2.5.1
rewrite — item A's wording depends on it. Item E is a five-minute check that
could change how §2.5 weighs the close.
