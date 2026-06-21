package io.github.pdkovacs.wsgw.clientside;

import java.io.IOException;

public interface MessagePusher {
    void messageTo(String connectionId, String message) throws IOException;
}
