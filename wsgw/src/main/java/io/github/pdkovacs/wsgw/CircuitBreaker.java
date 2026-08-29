package io.github.pdkovacs.wsgw;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

public class CircuitBreaker {

    private final Duration windowSize;
    private final int threshold;
    private final Duration holdDownPeriod;
    private final Clock clock;

    private Instant countingSince;
    private int currentValue;
    private Instant breakingSince;

    public CircuitBreaker(Duration windowSize, int threshold, Duration holdDownPeriod) {
        this(windowSize, threshold, holdDownPeriod, Clock.systemUTC());
    }

    public CircuitBreaker(Duration windowSize, int threshold, Duration holdDownPeriod, Clock clock) {
        this.windowSize = windowSize;
        this.threshold = threshold;
        this.holdDownPeriod = holdDownPeriod;
        this.clock = clock;
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
}
