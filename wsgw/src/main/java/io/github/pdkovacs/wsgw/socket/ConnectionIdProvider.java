package io.github.pdkovacs.wsgw.socket;

import java.util.UUID;

public interface ConnectionIdProvider {
    ConnectionIdProvider DEFAULT = new ConnectionIdProvider() {};

    default String generateId() {
        return UUID.randomUUID().toString();
    }
}
