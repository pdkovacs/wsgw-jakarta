package io.github.pdkovacs.wsgw.backpressure;

public class SendLockWaitTimedOut extends ConnectionException {
    public SendLockWaitTimedOut(String connectionId) {
        super(connectionId);
    }
}
