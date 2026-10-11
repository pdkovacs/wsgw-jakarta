package io.github.pdkovacs.wsgw.integration;

import io.github.pdkovacs.wsgw.socket.ConnectionIdProvider;

import java.util.concurrent.atomic.AtomicReference;

public class ConnectionIdGeneratorMock implements ConnectionIdProvider {

    // Atomic so that concurrent handshakes can never both consume the same rolled id:
    // a read-then-null on a plain field let two of them hand out one id twice.
    private final AtomicReference<String> nextId = new AtomicReference<>();

    public String roll() {
        var id = ConnectionIdProvider.DEFAULT.generateId();
        nextId.set(id);
        return id;
    }

    public String getNextId() {
        return nextId.get();
    }

    @Override
    public String generateId() {
        var tmp = nextId.getAndSet(null);
        return tmp == null ? ConnectionIdProvider.DEFAULT.generateId() : tmp;
    }
}
