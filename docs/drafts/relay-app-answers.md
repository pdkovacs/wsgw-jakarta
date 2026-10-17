# Relay: what the gateway does with the app's answer

Session of 2026-10-11, as of commit `cb0a00c`. **No code was changed.** This is a
design proposal, not a spec: nothing here is settled except where §2 says so.
It belongs in `docs/drafts/` because it moves §1–§4 of `docs/backpressure.md`
only once it is settled, and `backpressure.md`'s naming rule forbids naming
meters and knobs before they exist. Names below are therefore prose, not
identifiers, unless they already exist.

Code under discussion:

- `Relay.relayToApp`: discards the `HttpResponse`; any answer sets `sent = true`
  and records a latency sample.
- `Request.send`: shared with CONNECT, so it cannot judge a status itself.
- `ConnectionRequest.doFilter`: the CONNECT counterpart; the only place the app's
  status is interpreted today.

---

## 1. Premise: the app's answer to a relay is ignored

`Relay.relayToApp` calls `appwardRequest.send(...)` and drops the result. A 500, a
429 and a 200 are the same event: the message counts as delivered, a `response`
latency sample is recorded, and no retry happens. `wsgw.relay.attempts` rises; no
meter says "the app refused this".

This is also true of `POST /ws/disconnected/{id}`, which goes through the same
`relayToApp` (`Relay.sendDisconnect`).

### 1.1 Finding: the reference app answers 200, but not on the paths the gateway calls

The proposal that started this draft assumed the reference app (e2e-app) answers 200
in normal operation. An earlier version of this section inferred 204 instead,
reasoning that the gateway accepts only 204 on connect and "the e2e suite passes".
That inference was wrong: the suite that passes is the gateway's ITs against the
in-repo fake app (`FromWsgwFilters`, which sets `SC_NO_CONTENT` explicitly), not
e2e-app.

**Verified on 2026-10-17** against the Quarkus e2e-app, as of commit `6ced75e`
(see `e2e-app-port.md`):

- **The paths don't match.** `WsResource`'s class-level `@Path("/ws")` is joined to
  method paths that already start with `/ws/` (`AppPaths.*_FROM_WSGW`), so the real
  routes are `/ws/ws/connect/{id}` etc. The gateway's `/ws/connect/{id}` gets **404**.
- **Connect answers 200.** On its real route, connect's `Response.ok().build()` goes
  out as 200, not 204, so the gateway would refuse every connect even with the path
  fixed (`ConnectionRequest`: any answer other than 204 or 401 becomes 502).
- **Message and disconnect answer 400.** Their `@PathParam` names
  `x-wsgw-connection-id` while the path template variable is `connId`, so the id is
  always null.

So e2e-app has not run end to end against this gateway, and its answers can't
settle the question this section was raising. These bugs are being fixed as part of
the e2e-app port, and that fix is where the documented success code (200
or 204) for relayed messages and disconnects gets chosen. The consequence for §2
still holds: if that code is 204, "anything other than 200 is failed" would flag
normal answers, so 2xx must be the success class with no per-code warning, or the
warning must name 204 as expected.

### 1.2 Finding: §5.3 overstates CONNECT's pass-through

`docs/backpressure.md` §5.3 says "a non-204 app answer (e.g. 401) is passed
through". The code passes through only 401 (with a `WWW-Authenticate` header);
every other non-204 answer, 503 and 429 included, becomes 502, and the app's
`Retry-After` is dropped. That bears on §4: CONNECT does not mirror the app's
signals today, it collapses them.

---

## 2. Decided

Agreed in the 2026-10-11 session:

1. **Success is 2xx.** 200 is the documented code; another 2xx is delivered and
   logs a warning. (Subject to §1.1: the documented code may be 204.)
2. **A non-2xx answer is a failed delivery**: logged as an error, counted, and
   excluded from the relay latency timer.
3. **There must be a counter for failed deliveries.** None exists. The
   drop counters (`wsgw.relay.enqueue.drops`, `wsgw.relay.retry.exhaustions`,
   `wsgw.relay.retry.budget.drops`) all describe timeouts or a full buffer; an
   answered refusal and an `IOException` move none of them.
4. **The gateway should treat the app as our own clients are asked to treat us**:
   key retry behaviour off 429/503/504 and `Retry-After` (§1 of
   `backpressure.md`), not invent a second vocabulary.
5. **Expected status codes are documented per endpoint** in the README's
   Endpoint reference, extending the pattern the diagram and numbered list in
   *How it works* already use.

---

## 3. The mirror, as a table

One concrete reading of `backpressure.md` §1, applied to the app's answer to
`POST /ws/message/{id}`:

| App answers | §1 meaning | Gateway reaction | Counted as |
|---|---|---|---|
| 2xx | accepted | delivered (warn if not the documented code) | latency sample |
| 429 | that call ran out of the app's own wait budget | failed attempt; retry-eligible | failure, `reason=429` |
| 503 (+ `Retry-After`) | app shedding, sustained | failed attempt; retry-eligible after the `Retry-After`, capped | failure, `reason=503` |
| 504 | something behind the app was too slow | like our own timeout expiry: retry-eligible | failure, `reason=504` |
| 502, 410, other 4xx/5xx | not backpressure vocabulary | failed; **no retry** | failure, `reason=other_4xx/other_5xx` |
| no answer (refused, reset) | reachability | failed; **no retry** (as today) | failure, `reason=unreachable` |
| timeout expiry | slow app | retry-eligible (as today) | existing retry counters |

Retry-eligible attempts go through the existing `maxRelayRetries` and retry
budget. 429 and 503 are safer to retry than a timeout because the app refused the
message rather than possibly processing it late; 504 carries the same
duplicate caveat as a timeout (`backpressure.md` §2.5.2 "Retry is re-delivery").

---

## 4. Can mirrored congestion make the gateway unresponsive?

**Yes, in specific slices, and only if the app is allowed to control a wait the
gateway does not bound.** Today every wait on the relay path is gateway-owned:
`relayResponseTimeout` per attempt, `maxRelayRetries`, `relayRetryInterval`, the
budget, a bounded per-connection buffer. §2.5.2 gives the worst-case drain stall
as `(maxRelayRetries + 1) × relayResponseTimeout + maxRelayRetries ×
relayRetryInterval`. A stalled drain fills the buffer, reading from that client
stops, and `relayEnqueueTimeout` drops frames. That is bounded and per
connection.

Mirroring adds an input the app controls: `Retry-After`. The invariant to keep:

> **The app may lengthen a wait up to a gateway-owned cap; it may never unbound
> one.** Every duration the gateway waits because of an app's answer is
> `min(app's value, a gateway knob)`.

With a cap `C` the worst-case stall becomes `(n + 1) × relayResponseTimeout +
n × max(relayRetryInterval, min(RetryAfter, C))`, again expressed entirely in
gateway knobs.

### 4.1 The slices

Marked **verified** (read in code) or **inferred** (reasoned, to check).

| # | Slice | Mechanism | Status |
|---|---|---|---|
| a | One connection's relay path | Honored `Retry-After` stalls that connection's drain; buffer fills; reading stops. Bounded by §2.5.1, but the length is app-chosen unless capped. | verified: drain is one virtual thread per connection over a bounded `LinkedBlockingQueue` (`Dispatcher`) |
| b | **All connections' relay paths at once** | A 503 is "sustained", an app-wide condition. Every connection's drain stalls together, every buffer fills, reading stops everywhere. The relay path is then unresponsive gateway-wide, while PUSH keeps working. This is the "slice" in the question: correlated, not individual. | inferred from a; the correlation is the new part |
| c | The disconnect notification | `sendDisconnect` queues the disconnect behind messages in the same drain, and enqueues with `blockUntilAccepted` (an unbounded `put`) on its own virtual thread. A long stall delays the app learning of the disconnect, and each closing connection holds a buffer, a drain thread and a waiting enqueue thread until it clears. `Relays.relays.remove` runs only when the drain ends. | verified for the queueing and `put`; the leak consequence is inferred |
| d | CONNECT, through shared capacity | One shared `HttpClient` and one app. A shedding app answering relays 503 also answers connects; retries add load. Connect timeouts and registration timeouts feed `connectFailurePreemptThreshold` (§2.4), so relay load can shed new connections. | inferred; the coupling path exists in §3 of `backpressure.md` but has no RELAY input today |
| e | Synchronized retry waves | N connections each honoring the same `Retry-After` retry at the same instant. The gateway jitters its own `Retry-After` for exactly this reason (§2.4.1); honoring the app's un-jittered value reintroduces it. | inferred |
| f | Fast failure as a hot loop | A fast non-2xx is cheaper for the drain than a timeout, so the drain empties quickly and every message gets one attempt: the app receives the whole relay stream at full rate. First attempts are not budgeted (`RetryBudget` limits only retries). Mirroring does not cause this, but "failed, no retry" makes it visible. | verified: budget covers retries only |
| g | Control frames while reading is stopped | While a frame waits for buffer space the receive thread is blocked, so ping/pong and close frames from that client go unread. Whether Tomcat then expires the session is not established here. | **unknown**; check before relying on §2.5.1's "loses no data" |
| h | Graceful stop | `Dispatcher.join(timeout)` waits on drains that may be sleeping a capped `Retry-After`; stop is slower by up to the cap. | inferred, minor |

Slices a, c and g already exist today with timeouts; mirroring changes the *length*
and the *correlation*, not their existence. Slices b, d and e are new to
mirroring.

---

## 5. What we expect of the app, and what the client sees

This is the part that asks for more concept work before settling.

**What we would ask of the app** (contract layer, if it lands in §2.5.2):

- Refuse fast rather than answer slowly: a 503 with `Retry-After` frees the
  gateway's drain sooner than a timeout does. The gateway caps the wait, so an
  honest `Retry-After` costs the app nothing.
- Use 429/503/504 only with their §1 meanings. Anything else is "failed, not
  retried" from the gateway's side.
- Tolerate duplicates (already required) and jitter `Retry-After` itself where it
  can, since the gateway cannot know which connections the value should
  de-synchronize.

**What the client sees.** Nothing, over HTTP: RELAY has no request to answer
(`backpressure.md` §2.5.1, "The asymmetry"). A refused, failed or dropped relay
is, from the client, indistinguishable from a delivered one. Slice b would make
the relay path stall for every client, and each of them would see only a stalled
socket. Whether that is acceptable follows from the best-effort decision (L in
`relay-backpressure-handoff.md`), but mirroring makes the consequence larger: the
sources of silent loss go from "buffer full, timeouts" to "anything the app
answers".

---

## 6. Open questions

Each with my current lean; none decided.

1. **Retry on 429/503/504, or count and drop everything?** Lean: retry
   429/503/504 inside the existing budget; no retry for the rest. The alternative
   (no retry anywhere) is simpler and avoids slices b and e entirely.
2. **Cap on honored `Retry-After`.** Lean: required, gateway knob, small
   (seconds, not tens of seconds). Beyond the cap, drop the message rather than
   wait.
3. **Per-message reaction, or an app-wide hold-down?** A per-message reaction
   makes N connections rediscover the same 503. An app-wide hold-down mirrors
   `connectPreemptHoldDown` but is a new knob, a new state and a new §2.5.2
   subsection. Lean: per-message first; revisit if slice b shows up in a stress
   run.
4. **Disconnect gets its own policy.** Losing a disconnect leaks app state;
   losing a message costs one message. It also should not wait unboundedly
   behind messages. Lean: separate cap, and bound `blockUntilAccepted`. Needs the
   linear-lifecycle view (one owner, one release site) before deciding.
5. **Should relay failures feed the CONNECT breaker?** Lean: no. Different
   flows; shedding connects would also cut PUSH reachability for clients that
   relay nothing. If slice d is real, address it at the app's capacity, not by
   coupling the counters.
6. **First attempts.** Slice f says an app that fails fast is hit at full rate.
   Is a gateway-side rate limit toward the app in scope, or is the retry budget
   the intended ceiling? Lean: out of scope here; record it.
7. **CONNECT parity.** CONNECT currently collapses the app's 503/429 to 502 and
   drops `Retry-After` (§1.2). Mirroring on RELAY but not on CONNECT would make
   the contract inconsistent. Lean: decide both together, but implement RELAY
   first.
8. **Metric shape.** One failure counter with a bounded `reason` tag vs separate
   counters. Lean: one counter, `flow=relay`, `site=gw_to_app`, plus the reason.
   `retry.exhaustions`' description ("retries ran out after timeouts") widens.
9. **Latency exclusion vs the §2.5.2 convention.** The existing convention
   records timeouts ("the timeouts are the slow tail") and excludes at-once
   failures. A 504 relayed from the app is a timeout *behind* the app; excluding
   it is consistent with the 2xx-only rule but loses a tail sample. Lean:
   exclude, and say so.

---

## 7. Angles still to look at

The list above is my enumeration; the review is meant to find the ones I did not
think of. Suggested order, cheapest and most decisive first:

1. **Check §1.1 and slice g.** Both are facts one probe settles; everything else
   depends on them.
2. **Slice b**, as a stress scenario: app answers 503 + `Retry-After` for a
   window, N connections relaying. What does the gateway's HTTP front door
   (CONNECT, PUSH) do meanwhile? The stress ITs already have a reference app to
   misbehave.
3. **Slice c**, on paper first, against the lifecycle-linearity principle.
4. **Slice d**, by reading how connect and relay share the `HttpClient` and
   whether the app's own capacity is the coupling or the client is.

---

## 8. Follow-ups once settled

Not started. Listed so the folding step is not forgotten (the lesson of the
naming rule):

- `backpressure.md`: §2.5.2 (what the app answers and what the gateway does with
  it, dependencies diagram), §2.4.2 (if CONNECT parity lands), §3 (the new
  coupling, if any), §4 matrix row, §5.4 status, §5.6 traceability, and the §5.3
  sentence from §1.2.
- `README.md`: Endpoint reference per-endpoint expected codes; *How it works*
  diagram and numbered list, following their existing pattern.
- Code, last: `Relay.relayToApp` inspecting the answer; the failure counter; the
  `Retry-After` cap. Tests first, per the established practice for the two-waits
  split.
