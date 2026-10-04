package io.github.pdkovacs.wsgw.appward;

import io.github.pdkovacs.wsgw.MonotonicClock;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.function.Supplier;

public class RetryBudget {
    public interface RetryCounter extends Supplier<Double> {
        void increment();
    }

    private final double budget;
    private final Duration budgetWindow;
    private final Supplier<Double> attempts;
    private final RetryCounter retries;
    private final Runnable dropCountIncrementor;
    private final Clock clock;

    private Instant windowEnd;
    private double attemptsAtWindowStart;
    private double retryCountAtWindowStart;

    RetryBudget(double budget, Duration budgetWindow, Supplier<Double> attempts, RetryCounter retries,
                Runnable dropCountIncrementor) {
        this(budget, budgetWindow, attempts, retries, dropCountIncrementor, MonotonicClock.create());
    }

    public RetryBudget(double budget, Duration budgetWindow, Supplier<Double> attempts, RetryCounter retries,
                       Runnable dropCountIncrementor, Clock clock) {
        this.budget = budget;
        this.budgetWindow = budgetWindow;
        this.attempts = attempts;
        this.retries = retries;
        this.dropCountIncrementor = dropCountIncrementor;
        this.clock = clock;
        resetWindow();
    }

    public synchronized boolean tryReserveRetry() {
        if (clock.instant().isAfter(windowEnd)) {
            resetWindow();
            retries.increment();
            return true;
        }

        var recentAttempts = attempts.get() - attemptsAtWindowStart;
        var recentRetries = retries.get() - retryCountAtWindowStart;
        var nextRetry = 1; // both an attempt and a retry
        if (recentRetries == 0 || recentRetries + nextRetry <= Math.floor((recentAttempts + nextRetry) * budget)) {
            retries.increment();
            return true;
        }

        dropCountIncrementor.run();
        return false;
    }

    private void resetWindow() {
        windowEnd = clock.instant().plus(budgetWindow);
        attemptsAtWindowStart = attempts.get();
        retryCountAtWindowStart = retries.get();
    }
}
