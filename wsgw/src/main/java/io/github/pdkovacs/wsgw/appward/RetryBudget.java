package io.github.pdkovacs.wsgw.appward;

import io.github.pdkovacs.wsgw.MonotonicClock;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

public class RetryBudget {
    public interface Counter {
        void increment();
    }

    private final double budget;
    private final Duration budgetWindow;
    private final Counter retryCounter;
    private final Counter dropCounter;
    private final Clock clock;

    private Instant windowEnd;
    private int attemptsInWindow;
    private int retryCountInWindow;

    RetryBudget(double budget, Duration budgetWindow, Counter retryCounter, Counter dropCounter) {
        this(budget, budgetWindow, retryCounter, dropCounter, MonotonicClock.create());
    }

    public RetryBudget(double budget, Duration budgetWindow, Counter retryCounter,
                       Counter dropCounter, Clock clock) {
        this.budget = budget;
        this.budgetWindow = budgetWindow;
        this.retryCounter = retryCounter;
        this.dropCounter = dropCounter;
        this.clock = clock;
        resetWindow();
    }

    public synchronized boolean tryReserveRetry() {
        resetWindowOnExpiry();
        var nextRetry = 1; // both an attempt and a retry
        if (retryCountInWindow == 0 || retryCountInWindow + nextRetry <= Math.floor((attemptsInWindow + nextRetry) * budget)) {
            retryCountInWindow++;
            retryCounter.increment();
            return true;
        }

        dropCounter.increment();
        return false;
    }

    public synchronized void recordAttempt() {
        resetWindowOnExpiry();
        attemptsInWindow++;
    }

    private void resetWindowOnExpiry() {
        if (clock.instant().isAfter(windowEnd)) {
            resetWindow();
        }
    }

    private void resetWindow() {
        windowEnd = clock.instant().plus(budgetWindow);
        attemptsInWindow = 0;
        retryCountInWindow = 0;
    }
}
