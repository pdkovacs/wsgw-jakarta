package io.github.pdkovacs.wsgw;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.DoubleSupplier;

public class CircuitBreaker {

    /** Retry-After is jittered to a random fraction in [MIN_JITTER_FRACTION, 1.0] of the true remaining hold-down. */
    public static final double MIN_JITTER_FRACTION = 0.5;

    private final Duration windowSize;
    private final int threshold;
    private final Duration holdDownPeriod;
    private final Clock clock;
    private final DoubleSupplier jitterSource;

    private Instant countingSince;
    private int currentValue;
    private Instant breakingSince;

    public CircuitBreaker(Duration windowSize, int threshold, Duration holdDownPeriod) {
        this(windowSize, threshold, holdDownPeriod, Clock.systemUTC());
    }

    public CircuitBreaker(Duration windowSize, int threshold, Duration holdDownPeriod, Clock clock) {
        this(windowSize, threshold, holdDownPeriod, clock, ThreadLocalRandom.current()::nextDouble);
    }

    public CircuitBreaker(
            Duration windowSize,
            int threshold,
            Duration holdDownPeriod,
            Clock clock,
            DoubleSupplier jitterSource) {
        this.windowSize = windowSize;
        this.threshold = threshold;
        this.holdDownPeriod = holdDownPeriod;
        this.clock = clock;
        this.jitterSource = jitterSource;
    }

    public Duration increment() {
        return incrementBy(1);
    }

    /**
     * Increments the monitored measure
     *
     * @param inc the amount with which to increment the monitored measure
     * @return the hold-down time remaining if currently shedding, {@code null} otherwise
     */
    public synchronized Duration incrementBy(int inc) {
        if (countingSince == null) {
            countingSince = clock.instant();
        }
        if (countingSince.isBefore(clock.instant().minus(windowSize))) {
            countingSince = clock.instant();
            currentValue = 0;
        }

        var toGo = remaining();

        if (toGo != null) {
            return toGo;
        }

        currentValue += inc;

        if (currentValue > threshold) {
            breakingSince = clock.instant();
            return holdDownPeriod;
        } else {
            return null;
        }
    }

    /**
     * How much time is remaining until break is lifted.
     *
     * @return the hold-down time remaining if currently shedding, {@code null} otherwise
     */
    public synchronized Duration remaining() {
        if (breakingSince != null) {
            Duration elapsed = Duration.between(breakingSince, clock.instant());
            if (elapsed.compareTo(holdDownPeriod) >= 0) {
                breakingSince = null;
                currentValue = 0;
                return null;
            }
            return holdDownPeriod.minus(elapsed);
        }

        return null;
    }

    /**
     * The value to send as {@code Retry-After}: {@link #remaining()}, scaled down by a random
     * fraction in {@code [MIN_JITTER_FRACTION, 1.0]}.
     *
     * @return the jittered hold-down time remaining if currently shedding, {@code null} otherwise
     */
    public synchronized Duration jitteredRemaining() {
        var exact = remaining();
        if (exact == null) {
            return null;
        }
        double fraction = MIN_JITTER_FRACTION + jitterSource.getAsDouble() * (1 - MIN_JITTER_FRACTION);
        return Duration.ofMillis(Math.round(exact.toMillis() * fraction));
    }
}
