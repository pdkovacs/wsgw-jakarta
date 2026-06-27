package io.github.pdkovacs.wsgw;

public class SendBackpressureException extends RuntimeException {
    final private String connectionId;
    public SendBackpressureException(String connectionId) {
        this.connectionId = connectionId;
    }
}
