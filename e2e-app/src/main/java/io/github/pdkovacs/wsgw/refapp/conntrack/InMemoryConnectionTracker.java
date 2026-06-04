package io.github.pdkovacs.wsgw.refapp.conntrack;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class InMemoryConnectionTracker implements WsConnections {

    private final Map<String, Set<String>> connsByUser = new ConcurrentHashMap<>();

    @Override
    public void addConnection(String userId, String connId) {
        connsByUser
                .computeIfAbsent(userId, k -> ConcurrentHashMap.newKeySet())
                .add(connId);
    }

    @Override
    public boolean removeConnection(String userId, String connId) {
        Set<String> conns = connsByUser.get(userId);
        if (conns == null) {
            return false;
        }
        conns.remove(connId);
        return true;
    }

    @Override
    public List<String> getConnections(String userId) {
        Set<String> conns = connsByUser.get(userId);
        if (conns == null) {
            return List.of();
        }
        return List.copyOf(conns);
    }
}
