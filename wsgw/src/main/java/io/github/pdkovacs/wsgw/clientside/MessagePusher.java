package io.github.pdkovacs.wsgw.clientside;

import java.io.IOException;

public interface MessagePusher {
    void push(String connectionId, String message) throws IOException, InterruptedException;
}
