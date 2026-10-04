package io.github.pdkovacs.wsgw.appward;

import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;

/**
 * The phase of an exchange with the app that a timeout ended in, as the {@code phase} tag of
 * the meters that count or time such exchanges (docs/backpressure.md §2.4.2, §2.5.2).
 * <p>
 * The JDK reports the phase the exchange was in when a timer fired, not which timer fired: the
 * request timeout expiring while the connection is still being established is also an
 * {@link HttpConnectTimeoutException}.
 */
public enum TimeoutPhase {
    /** The connection to the app was not established within the budget. */
    CONNECT("connect"),
    /** The connection was up; the app's answer is what was awaited (or arrived). */
    RESPONSE("response");

    public static final String TAG = "phase";

    private final String tagValue;

    TimeoutPhase(String tagValue) {
        this.tagValue = tagValue;
    }

    public String tagValue() {
        return tagValue;
    }

    public static TimeoutPhase of(HttpTimeoutException timeout) {
        return timeout instanceof HttpConnectTimeoutException ? CONNECT : RESPONSE;
    }
}
