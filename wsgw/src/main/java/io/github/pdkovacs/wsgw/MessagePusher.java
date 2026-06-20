package io.github.pdkovacs.wsgw;

import java.io.IOException;

public interface ClientMessenger {
    void sendTo(String connectionId, String message) throws IOException;
}
