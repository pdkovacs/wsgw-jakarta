package io.github.pdkovacs.wsgw.unit;

import io.github.pdkovacs.wsgw.appward.RetryBudget;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

/**
 * Prints, check by check, how the relay retry budget and the max-retries limit treat one stalled
 * message, for sizing the warm-up and the expected meter values in {@code RelayIT}'s retry tests.
 * <p>
 * The scenario: {@code warmUp} relays that succeed first time, then one message whose every attempt
 * times out, all inside a single budget window. The Result column is decided by the real
 * {@link RetryBudget}; only the max check, private to {@code Relay.Retries}, is copied here.
 * The "Free pass?" and "Share" columns recompute {@code RetryBudget.tryReserveRetry} for reading
 * only: if they ever disagree with Result, they have drifted from it.
 * <p>
 * Run it from the IDE, or after {@code mvn test-compile}:
 * <pre>
 * java -cp wsgw/target/classes:wsgw/target/test-classes \
 *     io.github.pdkovacs.wsgw.unit.RetryBudgetTable &lt;budget&gt; &lt;warmUp&gt; &lt;maxRetries&gt;
 * </pre>
 */
public class RetryBudgetTable {

    public static void main(String[] args) {
        if (args.length != 3) {
            System.err.println("usage: RetryBudgetTable <budget> <warmUp> <maxRetries>");
            System.exit(2);
        }
        System.out.print(render(Double.parseDouble(args[0]), Integer.parseInt(args[1]), Integer.parseInt(args[2])));
    }

    static String render(double budget, int warmUp, int maxRetries) {
        var attempts = new int[]{0};
        var retries = new int[]{0};
        var drops = new int[]{0};
        // A fixed clock never passes the window end, so every check falls in the first window.
        var clock = Clock.fixed(Instant.EPOCH, ZoneOffset.UTC);
        var retryBudget = new RetryBudget(budget, Duration.ofSeconds(1),
                () -> retries[0]++, () -> drops[0]++, clock);
        // The table's own tally of what RetryBudget counts, so the columns can be read without the budget's internals.
        Runnable attempt = () -> {
            retryBudget.recordAttempt();
            attempts[0]++;
        };

        var out = new StringBuilder();
        out.append("budget=%s, warmUp=%d, maxRetries=%d%n%n".formatted(budget, warmUp, maxRetries));
        out.append("| Check | Attempts | Retries | Free pass? | Share: `r + 1 <= floor((A + 1) × b)` | Result |%n"
                .formatted());
        out.append("|---|---|---|---|---|---|%n".formatted());

        for (var i = 0; i < warmUp; i++) {
            attempt.run();
        }
        attempt.run(); // the stalled message's first attempt
        var exhaustions = 0;
        for (var check = 1; ; check++) {
            var a = attempts[0];
            var r = retries[0];
            var freePass = r == 0 ? "yes" : "no";
            var shareCap = (long) Math.floor((a + 1) * budget);
            var share = "%d ≤ floor(%s) = %d → %s".formatted(r + 1, (a + 1) * budget, shareCap,
                    r + 1 <= shareCap ? "passes" : "fails");

            if (r >= maxRetries) {
                exhaustions++;
                out.append(row(check, a, r, "—", "—", "exhausted (max)"));
                break;
            }
            if (!retryBudget.tryReserveRetry()) {
                out.append(row(check, a, r, freePass, share, "dropped (budget)"));
                break;
            }
            out.append(row(check, a, r, freePass, share, "granted"));
            attempt.run(); // the retry's attempt, which times out too
        }

        out.append("%nExpected meters: relayAttempts=%d (warm-up %d), relayRetries=%d, budgetDropCount=%d, retryExhaustions=%d%n"
                .formatted(attempts[0], warmUp, retries[0], drops[0], exhaustions));
        return out.toString();
    }

    private static String row(int check, long attempts, long retries, String freePass, String share, String result) {
        return "| %d | %d | %d | %s | %s | %s |%n".formatted(check, attempts, retries, freePass, share, result);
    }
}
