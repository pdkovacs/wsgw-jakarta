package io.github.pdkovacs.wsgw.unit;

import io.github.pdkovacs.wsgw.CircuitBreaker;
import io.github.pdkovacs.wsgw.appward.Dispatcher;
import io.github.pdkovacs.wsgw.appward.Relays;
import io.github.pdkovacs.wsgw.appward.Request;
import io.github.pdkovacs.wsgw.routehandlers.ConnectionRequest;
import io.github.pdkovacs.wsgw.socket.ConnectionIdProvider;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.Collections;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A connect-phase expiry ({@link HttpConnectTimeoutException}) is a timeout like any other
 * (docs/backpressure.md §2.4.2, §2.5.2): it is counted, timed and retried, and the {@code phase}
 * tag is what tells it apart from an app that was reached but answered late.
 */
@Timeout(10)
public class ConnectPhaseTimeoutTest {

    private static Request requestFailingWith(HttpTimeoutException failure) throws Exception {
        var httpClient = mock(HttpClient.class);
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenThrow(failure);
        return new Request("http://app.invalid", httpClient);
    }

    private static double timeouts(MeterRegistry registry, String phase) {
        return registry.get("wsgw.connect.timeouts")
                .tag("flow", "connect").tag("site", "gw_to_app").tag("phase", phase).counter().count();
    }

    private static long relayLatencySamples(MeterRegistry registry, String phase) {
        return registry.get("wsgw.relay.latency")
                .tag("flow", "relay").tag("site", "gw_to_app").tag("phase", phase).timer().count();
    }

    private static double relayCounter(MeterRegistry registry, String name) {
        return registry.get(name).tag("flow", "relay").tag("site", "gw_to_app").counter().count();
    }

    private static class ConnectAttempt {
        final MeterRegistry registry = new SimpleMeterRegistry();
        final CircuitBreaker breaker = new CircuitBreaker(Duration.ofSeconds(30), 0, Duration.ofSeconds(30));
        final HttpServletResponse response = mock(HttpServletResponse.class);

        ConnectAttempt(HttpTimeoutException failure) throws Exception {
            var underTest = new ConnectionRequest(requestFailingWith(failure), ConnectionIdProvider.DEFAULT,
                    10, Duration.ofSeconds(1), breaker, Duration.ofSeconds(1), registry);

            var request = mock(HttpServletRequest.class);
            when(request.getServletPath()).thenReturn("/connect");
            when(request.getHeaderNames()).thenReturn(Collections.emptyEnumeration());

            underTest.doFilter(request, response, mock(FilterChain.class));
        }
    }

    @Test
    @DisplayName("an app not reached in time fails the connect with 504, counted under phase=connect, sampled, and fed to the breaker")
    void connectPhaseExpiryIsATimeout() throws Exception {
        var attempt = new ConnectAttempt(new HttpConnectTimeoutException("HTTP connect timed out"));

        verify(attempt.response).sendError(eq(HttpServletResponse.SC_GATEWAY_TIMEOUT), any(String.class));
        assertThat(timeouts(attempt.registry, "connect")).isEqualTo(1);
        assertThat(timeouts(attempt.registry, "response")).isEqualTo(0);
        assertThat(attempt.registry.get("wsgw.connect.latency").timer().count()).isEqualTo(1);
        assertThat(attempt.breaker.remaining()).isNotNull();
    }

    @Test
    @DisplayName("an app reached but answering late is counted under phase=response")
    void responsePhaseExpiryIsCountedApart() throws Exception {
        var attempt = new ConnectAttempt(new HttpTimeoutException("request timed out"));

        verify(attempt.response).sendError(eq(HttpServletResponse.SC_GATEWAY_TIMEOUT), any(String.class));
        assertThat(timeouts(attempt.registry, "response")).isEqualTo(1);
        assertThat(timeouts(attempt.registry, "connect")).isEqualTo(0);
    }

    @Test
    @DisplayName("a relay whose connect-phase expiry repeats is retried, sampled under phase=connect, and then dropped as exhausted")
    void relayRetriesConnectPhaseExpiry() throws Exception {
        var registry = new SimpleMeterRegistry();
        int maxRetries = 2;
        var relays = new Relays(requestFailingWith(new HttpConnectTimeoutException("HTTP connect timed out")),
                new Dispatcher.QueueParams(10, Duration.ofSeconds(1)),
                Duration.ofSeconds(1),
                new Relays.RetryParams(maxRetries, Duration.ofMillis(10), 1.0, Duration.ofSeconds(30)),
                registry);

        var relay = relays.createRelay(Map.of(), "connection-1");
        relay.sendMessage("a message");
        relay.sendDisconnect();
        relay.join(Duration.ofSeconds(5));

        // the message and the disconnect notification are relayed in turn, each with its retries
        int attempts = 2 * (maxRetries + 1);
        assertThat(relayCounter(registry, "wsgw.relay.attempts")).isEqualTo(attempts);
        assertThat(relayCounter(registry, "wsgw.relay.retries")).isEqualTo(2 * maxRetries);
        assertThat(relayCounter(registry, "wsgw.relay.retry.exhaustions")).isEqualTo(2);
        assertThat(relayLatencySamples(registry, "connect")).isEqualTo(attempts);
        assertThat(relayLatencySamples(registry, "response")).isEqualTo(0);
    }
}
