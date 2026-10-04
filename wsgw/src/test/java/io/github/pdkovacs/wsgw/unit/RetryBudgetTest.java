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
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@Timeout(5)
@ExtendWith(MockitoExtension.class)
public class RetryBudgetTest {

    final private Configuration defaultConfig = new Configuration();

    private RetryBudget.Counter dropCountIncrementor;
    @Mock
    private RetryBudget.Counter retries;
    private MutableClock clock;

    @BeforeEach
    void setup() {
        clock = new MutableClock(Instant.EPOCH);
        dropCountIncrementor = mock(RetryBudget.Counter.class);
    }

    @Test
    @DisplayName("tryReserveRetry() returns true when not dropping")
    void returnsFalseWhenNotDropping() {
        var config = new Configuration();
        config.setRelayRetryBudget(0.5);
        RetryBudget underTest = createInstanceToTest(config);
        assertThat(underTest.tryReserveRetry()).isTrue();

        recordAttempts(underTest, 5);
        clock.advance(Duration.ofSeconds(1));
        assertThat(underTest.tryReserveRetry()).isTrue();
        verify(dropCountIncrementor, times(0)).increment();
    }

    @Test
    @DisplayName("tryReserveRetry() returns false when the budget is exceeded")
    void returnsTrueWhenDropping() {
        var config = new Configuration();
        config.setRelayRetryBudget(0.5);
        RetryBudget underTest = createInstanceToTest(config);
        assertThat(underTest.tryReserveRetry()).isTrue();

        recordAttempts(underTest, 2);
        clock.advance(Duration.ofSeconds(1));
        assertThat(underTest.tryReserveRetry()).isFalse();
        verify(dropCountIncrementor, times(1)).increment();
    }

    @Test
    @DisplayName("window expiry resets counter")
    void windowExpiryResetsCounter() {
        var config = new Configuration();
        config.setRelayRetryBudget(0.5);
        RetryBudget underTest = createInstanceToTest(config);
        assertThat(underTest.tryReserveRetry()).isTrue();

        recordAttempts(underTest, 2);
        clock.advance(Duration.ofSeconds(1));
        assertThat(underTest.tryReserveRetry()).isFalse();
        verify(dropCountIncrementor, times(1)).increment();

        clock.advance(defaultConfig.getRelayRetryBudgetWindow());
        assertThat(underTest.tryReserveRetry()).isTrue();
        verify(dropCountIncrementor, times(1)).increment();
    }

    @Test
    @DisplayName("budget shrinks back at window restart")
    void budgetShrinksBackAtWindowRestart() {
        var config = new Configuration();
        config.setRelayRetryBudget(0.5);
        RetryBudget underTest = createInstanceToTest(config);
        assertThat(underTest.tryReserveRetry()).isTrue();

        recordAttempts(underTest, 2);
        clock.advance(Duration.ofSeconds(1));
        assertThat(underTest.tryReserveRetry()).isFalse();
        verify(dropCountIncrementor, times(1)).increment();

        clock.advance(defaultConfig.getRelayRetryBudgetWindow());
        assertThat(underTest.tryReserveRetry()).isTrue(); // count 0 -> 1
        verify(dropCountIncrementor, times(1)).increment();

        IntStream.range(0, 4).forEach(i -> underTest.recordAttempt());
        assertThat(underTest.tryReserveRetry()).isTrue();          // count 1 -> 2; 1+1 <= floor(5*0.5)=2 -> count 2
        assertThat(underTest.tryReserveRetry()).isFalse();         // 2+1 > 2 -> drop
        verify(dropCountIncrementor, times(2)).increment();
    }

    @Test
    @DisplayName("More drops don't reset the window")
    void moreDropsDoNotResetTheWindow() {
        var config = new Configuration();
        config.setRelayRetryBudget(0.5);
        RetryBudget underTest = createInstanceToTest(config);
        recordAttempts(underTest, 2);
        underTest.tryReserveRetry();
        clock.advance(Duration.ofSeconds(1));

        assertThat(underTest.tryReserveRetry()).isFalse();
        verify(dropCountIncrementor, times(1)).increment();
        assertThat(underTest.tryReserveRetry()).isFalse();
        verify(dropCountIncrementor, times(2)).increment();
    }

    @Test
    @DisplayName("More attempts cause drops to stop")
    void moreAttemptsCausesDropsToStop() {
        var config = new Configuration();
        config.setRelayRetryBudget(0.5);
        RetryBudget underTest = createInstanceToTest(config);
        recordAttempts(underTest, 2);
        underTest.tryReserveRetry();
        clock.advance(Duration.ofSeconds(1));

        assertThat(underTest.tryReserveRetry()).isFalse();
        verify(dropCountIncrementor, times(1)).increment();

        recordAttempts(underTest, 3);
        assertThat(underTest.tryReserveRetry()).isTrue();
        verify(dropCountIncrementor, times(1)).increment();
    }

    private RetryBudget createInstanceToTest(Configuration config) {
        return new RetryBudget(
                config.getRelayRetryBudget(),
                config.getRelayRetryBudgetWindow(),
                retries,
                dropCountIncrementor,
                clock);
    }

    private void recordAttempts(RetryBudget underTest, int i) {
        IntStream.range(0, i).forEach(_ -> underTest.recordAttempt());;
    }
}
