# Relay retry budget: hand-off brief

Session of 2026-10-10, as of commit `ad10847`. **No code was changed.** This brief
records two review findings against the relay retry budget and the fix recommended
for each. They are independent and can land in either order. Doing §1 first gives
§2 a regression test that actually exercises the budget share.

Code under discussion:

- `RetryBudget.tryReserveRetry()`: the budget decision, `synchronized`, counts a
  granted retry inside the lock.
- `Relay.Retries.tryReserveRetry()`: the per-message loop guard. It checks
  `maxRelayRetries`, then the budget, then sleeps the retry interval.
- `Relays.createRetryBudget`: wires the budget to the Micrometer counters.

---

## 1. `retriesWithinBudgetAfterTimeout` is coupled to `maxRelayRetries`

`RelayIT.Delivery.Retries.retriesWithinBudgetAfterTimeout` (`RelayIT.java:181`).

### Finding

The test expects `config.getMaxRelayRetries() - 1` retries (`RelayIT.java:209`).
The retry count is set by the budget, not by the max. The expectation equals 1 only
because the default max is 2:

- With a max of 3, the test expects 2 and fails.
- With a max of 1, the max cuts in before the budget does, so `retryExhaustions`
  becomes 1 and `budgetDropCount` becomes 0, and both of those assertions fail.

This also contradicts the test's display name, "…regardless of allowed max
retries". The comment at `RelayIT.java:183` still says "budget of 0.5", but the
test uses 0.2.

**Not a defect any more:** the original finding also said the single retry comes
from the free pass (`recentRetries == 0`, `RetryBudget.java:52`) and not from the
0.2 share. That was true while the share check used `<`. Since it became `<=`, the
share grants that retry as well:

| Check | Attempts | Retries | Free pass? | Share: `retries + 1 <= floor((attempts + 1) × 0.2)` | Result |
|---|---|---|---|---|---|
| 1st | 5 | 0 | yes | 1 ≤ floor(1.2) = 1 → passes | granted |
| 2nd | 6 | 1 | no | 2 ≤ floor(1.4) = 1 → fails | dropped |

The test does exercise the share, because the refusal comes from it. What it
cannot do is tell a share-granted first retry from a free-pass one.

### Recommended fix

Pin the max well above what the budget allows, and size the warm-up so that the
share grants **two** retries. The free pass can only ever account for one, so a
count of two proves the share granted it.

1. Call `config.setMaxRelayRetries(5)`. Either `Retries.setUp` takes it as a
   parameter, or this test sets it before `wsgwTestContext.setUp`.
2. Warm up with 8 sends instead of 4, and expect `relayAttempts == 8`. The checks
   then run:

   | Check | Attempts | Retries | Share | Result |
   |---|---|---|---|---|
   | 1st | 9 | 0 | free pass (share: 1 ≤ 2) | granted |
   | 2nd | 10 | 1 | 2 ≤ floor(2.2) = 2 | granted |
   | 3rd | 11 | 2 | 3 ≤ floor(2.4) = 2 → fails | dropped |

   Any warm-up from 7 to 10 gives the same outcome. 8 stays clear of the `floor`
   boundaries on both sides.
3. Assert literal values: `relayRetries == 2`, `budgetDropCount == 1`,
   `retryExhaustions == 0`. Derive nothing from the config.
4. Replace the comment, e.g. "Build a budget base on which a 0.2 share allows
   exactly two retries."

### Nearby, same file

- `retriesMaxNTimesAfterTimeout` (`RelayIT.java:130`) also depends on the default
  max without saying so. With budget 0.5 and a 4-send warm-up it holds for any max
  up to 3. Pin the max there too.
- Every assertion message in both tests reads "relay latency is recorded once the
  app has accepted the message". It was copied from the latency test and is wrong
  for the attempt, retry, exhaustion and drop checks.

---

## 2. The budget reads its decision state from the export meters

### Finding

`Relays.createRetryBudget` (`Relays.java:118`) hands `RetryBudget` two views of the
Micrometer counters: `relayAttempts().count()` as the attempt supplier, and
`relayRetries().count()`/`increment()` as the `RetryCounter`. The budget snapshots
both at each window start (`RetryBudget.resetWindow`) and subtracts the snapshot
from them to get in-window counts (`RetryBudget.java:49-50`).

This has two problems:

1. **It reverses a recorded decision.** For `CircuitBreaker`, `docs/backpressure.md`
   §5.6 records the opposite choice: "windowed count as decision state, separate
   from the export meters". `CircuitBreaker` keeps its own `int currentValue`
   under its own lock (`CircuitBreaker.java:19`, `:53`). The export counter is
   incremented separately at the call site.
2. **It breaks under a step-based registry.** The arithmetic assumes `count()` is
   a running total. In a step-based registry (and in `SimpleMeterRegistry` in
   `CountingMode.STEP`), `count()` returns the previous step's delta. The window
   subtraction then produces nonsense: negative or near-zero in-window counts,
   which the free pass and the share both misread. Today `Main` uses a cumulative
   `SimpleMeterRegistry`, so nothing is broken yet. But §5.5 says an exporter is
   still to be wired, and whether that one is step-based is not decided.

### Option considered: one monotonic counter, exported through a function counter

Keep running totals in an `AtomicLong` and register a `FunctionCounter` over it, so
the export reads the same value the budget decides on.

- It fixes problem 2, because Micrometer derives step deltas from a monotonic
  function itself.
- It does not fix problem 1: decision and export still share one value, only with
  the dependency the other way round.
- It must be `long`. A running `int` total overflows in a few days at ~10k
  relays/s.
- It changes the meter type, so `WsgwTestContext.meters` would have to look these
  up with `.functionCounter()` instead of `.counter()`.

### Recommended fix: the `CircuitBreaker` shape

`RetryBudget` keeps its own windowed counts under its own lock. The Micrometer
counters become write-only exports.

1. In `RetryBudget`, replace `attemptsAtWindowStart` / `retryCountAtWindowStart`
   and the two suppliers with plain fields `int attemptsInWindow` and
   `int retriesInWindow`, both zeroed by `resetWindow()`. The counts are windowed,
   so `int` is enough.
2. Add a `synchronized void recordAttempt()` that increments `attemptsInWindow`,
   resetting the window first if it has expired, as `tryReserveRetry` does.
   `Relay.relayToApp` calls it next to `meters.relayAttempts.increment()`
   (`Relay.java:102`).
3. In `tryReserveRetry`, compute the share from the two fields. On a grant,
   increment `retriesInWindow` and run an injected `Runnable` that bumps
   `wsgw.relay.retries`. This mirrors the existing `dropCountIncrementor`.
   `RetryBudget.RetryCounter` and the `Supplier<Double> attempts` parameter go
   away.
4. In `Relays.createRetryBudget`, pass `() -> relayMeters.relayRetries().increment()`
   as the retry incrementor. No call to `count()` remains in production code.

Why this over the function-counter option:

- It matches the decision §5.6 already records.
- Attempts and retries are now read under the same lock, so the share is computed
  from a consistent pair.
- The window logic becomes "reset to zero" instead of snapshot-and-subtract.
- The meter types stay as they are, so the integration-test lookups don't change.

The cost is that each count is now incremented in two places, the budget and the
export counter, which could drift apart. Both increments sit at the same call
sites, so the exposure is small.

### Test impact

- `RetryBudgetTest` (unit) mocks `Supplier<Double> attempts` and
  `RetryBudget.RetryCounter`. Its setup becomes `recordAttempt()` calls plus a
  mocked retry `Runnable`, and window-reset cases advance `MutableClock` as today.
- `RelayIT` is unaffected apart from §1.

### Doc follow-ups

- `docs/backpressure.md` §5.6, row "§2.5.2 relay retry budget / relay retry budget
  window" (line 1046): still `[planned]` with code site "—". It should name
  `RetryBudget` and carry the same "windowed count as decision state, separate from
  the export meters" note as the `CircuitBreaker` row.
- The same table's row for the three retry metrics (line 1045), and §5.4 (around
  line 994), still say the counters are "never incremented". Both are stale since
  `ad10847`.
