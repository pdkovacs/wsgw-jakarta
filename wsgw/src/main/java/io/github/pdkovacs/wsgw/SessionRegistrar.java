package io.github.pdkovacs.wsgw;

import jakarta.websocket.Session;

public interface SessionRegistrar {
    void register(String connectionId, Session session);
}
