package io.github.pdkovacs.wsgw.backpressure;

import java.time.Duration;

public class RetryAfter extends ConnectionException {

    private final long afterSecs;

    public RetryAfter(String connectionId, long afterSecs) {
        super(connectionId);
        this.afterSecs = afterSecs;
    }

    public long getAfterSecs() {
        return afterSecs;
    }
}
