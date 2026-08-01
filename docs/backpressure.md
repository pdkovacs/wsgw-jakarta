# Backpressure & Congestion Handling

This document is the single reference for the backpressure contract wsgw exposes:
how it detects congestion on each of its legs, what it does about it, and what it
tells its callers. It is written for two readers — the client author who must
handle the signals, and the operator who tunes the knobs and watches the metrics.

It is organized in two layers by depth of internal detail:

- **§1–§4 — the contract.** The external interface: signals, endpoints, knobs,
  metrics. Stable, and free of implementation references.
- **§5 — implementation & current status.** The implementor's working map: how
  much of the contract is live today, the internal mechanics behind it, and a
  per-element traceability table (§5.4) from contract to code. While the product
  is early-stage, this is the most active part of the document.

---

## 1. The signal contract (the vocabulary)

Three signals make up the entire backpressure vocabulary. Each means the same
thing wherever it is emitted, so a caller can treat the set as stable and key its
retry behaviour off the status code (and `Retry-After`).

| Signal | Meaning | Where the bottleneck is | `Retry-After` |
|---|---|---|---|
| **429 Too Many Requests** | This operation could not complete within its own wait budget. Reactive, per-request. | The gateway, right now, for this call. | Optional |
| **503 Service Unavailable** | The gateway is shedding load preemptively because a congestion metric crossed a configured threshold. Proactive. | The gateway, sustained. | Required |
| **504 Gateway Timeout** | The downstream app was too slow (connect ack — including a push waiting on that connection to establish, §2.1 — or message relay). | The backend app, not the caller. | Optional |

The axis that separates them: **429 and 503 say the client side is producing
faster than the gateway can drain** — 429 for a single call that ran out of
budget, 503 for "we've seen too much of that lately, back off." **504 says the
backend is slow** — a different party is at fault, so a caller's response should
differ.

Each signal carries a human-readable reason phrase for logs; programmatic callers key
off the status code and `Retry-After`.

New congestion sites (§2) map onto this same set rather than introducing codes of
their own — which is what lets a caller treat the vocabulary as fixed.

---

## 2. Congestion catalog

Each congestion site sits on exactly one data-flow leg. There are three:

| Leg | Direction | Entry point | Section |
|---|---|---|---|
| **PUSH** | app → client | `POST /message/{connectionId}` | §2.1 |
| **CONNECT** | client → app | `GET /connect` | §2.2 |
| **RELAY** | client → app | inbound WebSocket frame (no HTTP entry) | §2.3 |

One asymmetry shapes the whole catalog: **PUSH and CONNECT are each triggered by
an inbound HTTP request, so the gateway can answer that request with a signal
from §1. RELAY is triggered by an inbound WebSocket frame — there is no HTTP
request in flight to answer — so it cannot emit a §1 signal at all, and its
levers are different in kind.**

### 2.1 PUSH — slow message push, app → client

**Entry point.** `POST /message/{connectionId}` — the app hands the gateway a
payload to deliver as a text frame on the client's WebSocket.

**Trigger.** Delivery does not complete within its budget, typically because the
gateway↔client link is slow, which keeps that connection's send path busy so
further pushes to the same connection queue behind it.

**Knobs.**

| Knob | Controls |
|---|---|
| `pushWaitForRegistration` | How long a push first waits for its target connection to finish registering before failing. Race-tolerance for the connect/`onOpen` ordering — *not* a congestion signal; a timeout here means the connection never became ready (see §3). |
| `pushToClientWaitTimeout` | How long a push then waits for the connection's send path to drain before failing with 429. The push-congestion budget proper. |
| `pushToClientWaitTimeoutCountPreemptThresholdMinute` | `pushToClientWaitTimeout` expirations per minute above which the gateway sheds subsequent pushes preemptively with 503. |

**Metrics.**

| Metric | Meaning |
|---|---|
| average send-wait time | How long pushes wait for the connection's send path to free up; the leading indicator of push congestion. |
| `pushToClientWaitTimeoutCount` | Pushes that failed on the wait timeout; the input to the preempt threshold. |
| push-before-ready count | Connections where a push arrived before the connection had finished establishing (see §3). |

**Signals.**

| Condition | Signal |
|---|---|
| Send path fails to drain within `pushToClientWaitTimeout` | **429** |
| Connection not registered within `pushWaitForRegistration` | **504** + short `Retry-After` — see note below |
| `pushToClientWaitTimeoutCountPreemptThresholdMinute` exceeded | **503** + `Retry-After` |
| Session write error (not backpressure) | **502 Bad Gateway** |

The registration timeout answers with **504**, not 404 or 429, by deliberate
attribution: the wait is really "waiting for the CONNECT leg (§2.2) to finish
establishing this connection," so its timeout is the *same physical condition* as
a connect-ack timeout, observed from the push side — and it therefore carries the
same code (see §3). The condition is genuinely ambiguous at timeout (slow app
connect-ack vs. client that abandoned the upgrade vs. a stale/bogus id), so this
is an attributed default rather than a certainty; 504 is either correct (the
modal cause — slow app) or degrades safely (the caller retries briefly and gives
up). 404 is rejected because, during the `onOpen`-after-101 race, the id **is**
valid and about to register — telling the app "no such connection" would make it
abandon a good connection. The `Retry-After` is short because this usually clears
in milliseconds. The push-before-ready metric counts exactly these events, so the
attribution stays verifiable against the connect leg's own signals.

### 2.2 CONNECT — slow connection establishment, client → app

**Entry point.** `GET /connect` — the gateway relays the client's connection
request to the app and only completes the WebSocket upgrade if the app accepts.

**Trigger.** The app is slow to acknowledge the connection request.

**Knobs.**

| Knob | Controls |
|---|---|
| connect wait timeout | How long the gateway waits for the app's connect acknowledgement before failing with 504. |
| max in-flight connects | Admission bound on concurrent connection establishments. |
| connect preempt threshold / minute | Connect failures per minute above which the gateway rejects connections with 503. |

**Metrics.**

| Metric | Meaning |
|---|---|
| in-flight connect count | Connection establishments currently awaiting the app. |
| connect-to-app latency | How long the app takes to acknowledge. |
| connect timeout count | Connects that exceeded the wait timeout. |

**Signals.**

| Condition | Signal |
|---|---|
| App acknowledgement exceeds the connect wait timeout | **504** |
| Admission bound or preempt threshold exceeded | **503** + `Retry-After` |
| App unreachable (not backpressure) | **502 Bad Gateway** |
| App declined the connect (e.g. 401) | passed through unchanged |

### 2.3 RELAY — slow message relay, client → app

**Entry point.** An inbound WebSocket frame from the client, which the gateway
relays to the app as `POST /message-from-wsgw/{connectionId}`. Each connection
has its own bounded relay buffer.

**Trigger.** The client produces frames faster than the app drains them, so the
connection's relay buffer fills.

**The asymmetry.** There is no HTTP request in flight to reject — the trigger
arrived as a WebSocket frame — so none of §1's status codes apply to this leg.
The levers here are different in kind:

- **TCP backpressure (preferred)** — stop reading the client socket so the OS
  flow-control window closes and the client's writes stall. This is the closest
  analogue to natural backpressure and loses no data.
- **WebSocket close** — terminate a connection whose backlog cannot be drained,
  using an application close code.
- **Drop** — discard frames; only acceptable if the message contract tolerates
  loss, which wsgw's does not by default. Last resort.

**Knobs.**

| Knob | Controls |
|---|---|
| `appwardDispatcherQueueSize` | Per-connection relay buffer bound. |
| relay enqueue timeout | How long the relay may wait for buffer space before applying a lever above. |
| relay response deadline | How long the gateway waits for the app to accept a relayed message. |

**Metrics.**

| Metric | Meaning |
|---|---|
| relay buffer depth / high-water mark | Fill level per connection; the only early warning for this leg. |
| relay-to-app latency | How long the app takes to accept a relayed message. |
| enqueue-block / drop / close counts | How often each lever fired. |

**Signals.** None over HTTP — see the levers above. Because relay congestion
cannot turn a request red, it is observable only through the buffer-depth metric
until it escalates to a WebSocket close.

---

## 3. Cross-effects

The legs are not independent; the matrix in §4 exists to keep the couplings
visible.

- **Slow CONNECT surfaces as PUSH 504s.** A push aimed at a connection that has
  not finished establishing must wait for it to become ready. If connect
  acknowledgements are slow, these waits time out — and because the root cause is
  the connect leg, the push answers with the same code that leg uses, **504 on
  `POST /message`** (see the note in §2.1). This is distinct from PUSH congestion
  proper, which answers 429: the push-before-ready count is what tells them apart —
  a spike there points at CONNECT; a spike in average send-wait time points at
  PUSH proper.

- **Slow RELAY has no request-level symptom.** Unable to emit a signal, relay
  congestion stays invisible to HTTP callers and shows only in buffer depth until
  it escalates to a close. Operators must watch that metric directly.

---

## 4. Congestion matrix (operator quick-reference)

| Leg | Trigger | HTTP request to answer? | Knobs | Key metrics | Signal (when) |
|---|---|---|---|---|---|
| **PUSH** app→client | Delivery exceeds budget (slow client link) | Yes — `POST /message/{id}` | `pushWaitForRegistration`; `pushToClientWaitTimeout`; `…PreemptThresholdMinute` | avg send-wait; `pushToClientWaitTimeoutCount`; push-before-ready count | 429 (send-wait timed out); 504 (not registered — connect-slow, §3); 503+`Retry-After` (threshold) |
| **CONNECT** client→app | App slow to ack `/connect` | Yes — `GET /connect` | connect wait timeout; max in-flight; preempt threshold | in-flight connects; connect latency; connect timeout count | 504 (app ack timed out); 503+`Retry-After` (admission/threshold) |
| **RELAY** client→app | Client outpaces app drain; buffer fills | **No** — WebSocket frame | `appwardDispatcherQueueSize`; enqueue timeout; response deadline | buffer depth/high-water; relay latency; block/drop/close counts | none over HTTP → TCP backpressure → WS close |

---

## 5. Implementation & current status

This layer records how much of the contract above is live today and the internal
mechanics behind it. Status tags: `[implemented]`, `[partial]`, `[planned]`.

### 5.1 PUSH

Handled by `WsConnections.push`, invoked from the `MessageRequest` filter.

- **429 on timeout** — `[partial]`. `MessageRequest` returns 429 (`"Retry later"`)
  when `push` throws `SendBackpressureException`. No `Retry-After` header yet.
- **The two waits behind §2.1's two knobs** — `[partial]`. `push` runs the two
  waits sequentially through the `Timeouts` interface: `getPushWaitForRegistration`
  (→ §2.1 `pushWaitForRegistration`) is the *patient* wait absorbing the
  push-before-register race (Tomcat runs `onOpen` after the 101 is flushed), then
  `getWaitForSendMessageDesaturation` (→ §2.1 `pushToClientWaitTimeout`) is the
  *fast-fail* wait on the per-session send lock (a `ReentrantLock`). wsgw is wired
  through the single-arg `WsConnections` constructor, which feeds one hardcoded
  value (10s) to **both**, so the two knobs are not yet separately configurable.
- **push-before-ready count** — `[partial]`. Surfaced as
  `incPushWaitsOnRegistrationCount` on the `Metrics` hook and incremented once per
  raced connection, but `WsConnections` is constructed with a `null` metrics sink,
  so it is currently recorded nowhere.
- **503 preempt threshold**, `pushToClientWaitTimeoutCount`, **average send-wait
  time** metric, and `Retry-After` — `[planned]`.

### 5.2 CONNECT

Handled by the `ConnectionRequest` filter (`registerWithApp`).

- Current behaviour — `[implemented]` but **without backpressure**. The connect is
  relayed with a blocking call on the shared `Request.appClient`, which has only a
  20s TCP *connect*-timeout and **no response deadline**; there is no admission
  bound and no fast-fail. Failure to reach the app maps to **502**; a non-204 app
  answer (e.g. 401) is passed through.
- Response deadline, admission bound, 504/503 signals, and all CONNECT metrics —
  `[planned]`.

### 5.3 RELAY

Handled per connection by `Relay` / `Dispatcher` (a single virtual thread
draining a bounded `LinkedBlockingQueue`), created via `Relays`.

- **Bounded buffer** — `[implemented]`. `appwardDispatcherQueueSize`
  (`APPWARD_DISPATCHER_QUEUE_SIZE`, default 1024) bounds the queue.
- **Fast-fail / signalling** — `[planned]`. When the queue is full,
  `Dispatcher.accept` calls `queue.put()`, which **blocks the WebSocket-receiving
  thread** — buffering, but no enqueue timeout, no TCP backpressure, no close, no
  metric. This is the rawest instance of the problem: the block is cheap, so
  nothing throttles the client.
- Relay response deadline (no per-request timeout on `appClient` today) and all
  RELAY metrics — `[planned]`.

### 5.4 Traceability: contract element → code → status

Each row anchors one §1–§4 contract element to the code that implements it (or
would), so an implementor can jump straight from "what's missing" to the site to
touch.

| Contract element | Code site | Status | Gap |
|---|---|---|---|
| §2.1 `pushToClientWaitTimeout` (send-desaturation budget) | `Timeouts.getWaitForSendMessageDesaturation`; value from `Configuration.getPushToClientWaitTimeout()` | `[partial]` | hardcoded 10s; shares the one value with `pushWaitForRegistration`, not independently configurable |
| §2.1 `pushWaitForRegistration` (race tolerance) | `Timeouts.getPushWaitForRegistration` | `[partial]` | fed the same 10s via the single-arg `WsConnections` ctor; not separately configurable |
| §2.1 signal 429 (send-wait timeout) | `MessageRequest.doFilter` (`SendBackpressureException` → 429) | `[partial]` | no `Retry-After`; both waits currently throw the same exception, so registration timeouts also land here as 429 |
| §2.1 signal 504 (registration-wait timeout) | `WsConnections.push` timeout branch → `MessageRequest.doFilter` | `[planned]` | today maps to 429; must be distinguished from the send-wait and mapped to 504 + short `Retry-After` |
| §2.1 signal 503 + `…PreemptThresholdMinute` | — | `[planned]` | |
| §2.1 metric `pushToClientWaitTimeoutCount` | — | `[planned]` | feeds the 503 threshold |
| §2.1 metric average send-wait time | — | `[planned]` | |
| §2.1 metric push-before-ready | `WsConnections.push` → `Metrics.incPushWaitsOnRegistrationCount` | `[partial]` | incremented once per raced connection, but `WsConnections` is built with a `null` metrics sink (`Wsgw.start`) → recorded nowhere |
| §2.2 knobs/metrics/signals (504, 503, admission bound) | `ConnectionRequest.registerWithApp` | `[planned]` | unbounded blocking on `Request.appClient` (20s TCP connect-timeout, no response deadline); reach failure → 502 |
| §2.3 `appwardDispatcherQueueSize` | `Configuration` (`APPWARD_DISPATCHER_QUEUE_SIZE`, 1024) → `Dispatcher` queue | `[implemented]` | |
| §2.3 relay enqueue timeout + levers + metrics | `Dispatcher.accept` (`queue.put()` blocks when full) | `[planned]` | no fast-fail, no TCP backpressure / WS close, no metric |
| §2.3 relay response deadline | `Request.appClient` | `[planned]` | no per-request timeout |
| §1 uniform `Retry-After` on 429/503 | — | `[planned]` | |
