package io.github.pdkovacs.wsgw.backpressure;

public class ConnectionGone extends ConnectionException {
    public ConnectionGone(String connectionId) {
        super(connectionId);
    }
}
