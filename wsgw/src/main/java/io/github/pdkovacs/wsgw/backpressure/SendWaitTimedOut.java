package io.github.pdkovacs.wsgw.backpressure;

public class SendWaitTimedOut extends ConnectionException {
    public SendWaitTimedOut(String connectionId) {
        super(connectionId);
    }
}
