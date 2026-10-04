package io.github.pdkovacs.wsgw.unit;

import io.github.pdkovacs.wsgw.Configuration;
import io.github.pdkovacs.wsgw.appward.RetryBudget;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.Instant;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@Timeout(5)
@ExtendWith(MockitoExtension.class)
public class RetryBudgetTest {

    final private Configuration defaultConfig = new Configuration();

    private Runnable dropCountIncrementor;
    @Mock
    private Supplier<Double> attempts;
    @Mock
    private RetryBudget.RetryCounter retries;
    private MutableClock clock;

    @BeforeEach
    void setup() {
        clock = new MutableClock(Instant.EPOCH);
        dropCountIncrementor = mock(Runnable.class);
        when(attempts.get()).thenReturn(Double.valueOf(0));
        when(retries.get()).thenReturn(Double.valueOf(defaultConfig.getMaxRelayRetries() - 2));
    }

    @Test
    @DisplayName("tryReserveRetry() returns true when not dropping")
    void returnsFalseWhenNotDropping() {
        var config = new Configuration();
        config.setRelayRetryBudget(0.5);
        RetryBudget underTest = createInstanceToTest(config);
        assertThat(underTest.tryReserveRetry()).isTrue();

        when(attempts.get()).thenReturn(5.0);
        when(retries.get()).thenReturn(1.0);
        clock.advance(Duration.ofSeconds(1));
        assertThat(underTest.tryReserveRetry()).isTrue();
        verify(dropCountIncrementor, times(0)).run();
    }

    @Test
    @DisplayName("tryReserveRetry() returns false when the budget is exceeded")
    void returnsTrueWhenDropping() {
        var config = new Configuration();
        config.setRelayRetryBudget(0.5);
        RetryBudget underTest = createInstanceToTest(config);
        assertThat(underTest.tryReserveRetry()).isTrue();

        when(attempts.get()).thenReturn(2.0);
        when(retries.get()).thenReturn(1.0);
        clock.advance(Duration.ofSeconds(1));
        assertThat(underTest.tryReserveRetry()).isFalse();
        verify(dropCountIncrementor, times(1)).run();
    }

    @Test
    @DisplayName("window expiry resets counter")
    void windowExpiryResetsCounter() {
        var config = new Configuration();
        config.setRelayRetryBudget(0.5);
        RetryBudget underTest = createInstanceToTest(config);

        when(attempts.get()).thenReturn(2.0);
        when(retries.get()).thenReturn(1.0);
        clock.advance(Duration.ofSeconds(1));
        assertThat(underTest.tryReserveRetry()).isFalse();
        verify(dropCountIncrementor, times(1)).run();

        clock.advance(defaultConfig.getRelayRetryBudgetWindow());
        assertThat(underTest.tryReserveRetry()).isTrue();
        verify(dropCountIncrementor, times(1)).run();
    }

    @Test
    @DisplayName("budget shrinks back at window restart")
    void budgetShrinksBackAtWindowRestart() {
        var config = new Configuration();
        config.setRelayRetryBudget(0.5);
        RetryBudget underTest = createInstanceToTest(config);

        when(attempts.get()).thenReturn(2.0);
        when(retries.get()).thenReturn(1.0);
        clock.advance(Duration.ofSeconds(1));
        assertThat(underTest.tryReserveRetry()).isFalse();
        verify(dropCountIncrementor, times(1)).run();

        clock.advance(defaultConfig.getRelayRetryBudgetWindow());
        assertThat(underTest.tryReserveRetry()).isTrue();
        verify(dropCountIncrementor, times(1)).run();

        when(attempts.get()).thenReturn(4.0);
        when(retries.get()).thenReturn(2.0);
        clock.advance(Duration.ofSeconds(1));
        assertThat(underTest.tryReserveRetry()).isFalse();
        verify(dropCountIncrementor, times(2)).run();
    }

    @Test
    @DisplayName("More drops don't reset the window")
    void moreDropsDoNotResetTheWindow() {
        var config = new Configuration();
        config.setRelayRetryBudget(0.5);
        RetryBudget underTest = createInstanceToTest(config);
        when(attempts.get()).thenReturn(2.0);
        when(retries.get()).thenReturn(1.0);
        clock.advance(Duration.ofSeconds(1));

        assertThat(underTest.tryReserveRetry()).isFalse();
        verify(dropCountIncrementor, times(1)).run();
        assertThat(underTest.tryReserveRetry()).isFalse();
        verify(dropCountIncrementor, times(2)).run();
    }

    @Test
    @DisplayName("More attempts cause drops to stop")
    void moreAttemptsCausesDropsToStop() {
        var config = new Configuration();
        config.setRelayRetryBudget(0.5);
        RetryBudget underTest = createInstanceToTest(config);
        when(attempts.get()).thenReturn(2.0);
        when(retries.get()).thenReturn(1.0);
        clock.advance(Duration.ofSeconds(1));

        assertThat(underTest.tryReserveRetry()).isFalse();
        verify(dropCountIncrementor, times(1)).run();

        when(attempts.get()).thenReturn(3.0);
        assertThat(underTest.tryReserveRetry()).isTrue();
        verify(dropCountIncrementor, times(1)).run();
    }

    private RetryBudget createInstanceToTest(Configuration config) {
        return new RetryBudget(config.getRelayRetryBudget(), config.getRelayRetryBudgetWindow(),
                attempts, retries, dropCountIncrementor, clock);
    }
}
