package io.github.pdkovacs.wsgw.clientside;

import jakarta.websocket.Session;

public interface SessionRegistrar {
    void register(String connectionId, Session session);
}
