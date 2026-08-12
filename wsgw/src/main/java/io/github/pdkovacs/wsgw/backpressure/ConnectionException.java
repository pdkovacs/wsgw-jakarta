package io.github.pdkovacs.wsgw.backpressure;

abstract public class ConnectionException extends RuntimeException {
    protected final String connectionId;

    public ConnectionException(String connectionId) {
        this.connectionId = connectionId;
    }

    public String getConnectionId() {
        return connectionId;
    }
}
