package io.github.pdkovacs.wsgw;

import java.io.IOException;

public interface MessagePusher {
    void sendTo(String connectionId, String message) throws IOException;
}
