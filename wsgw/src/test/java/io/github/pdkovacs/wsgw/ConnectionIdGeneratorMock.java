package io.github.pdkovacs.wsgw;

public class ConnectionIdGeneratorMock implements ConnectionIdProvider{

    private String nextId;

    public String roll() {
        this.nextId = ConnectionIdProvider.DEFAULT.generateId();
        return nextId;
    }

    public String getNextId() {
        return nextId;
    }

    @Override
    public String generateId() {
        var tmp = nextId;
        nextId = null;
        return tmp == null ? ConnectionIdProvider.DEFAULT.generateId() : tmp;
    }
}
