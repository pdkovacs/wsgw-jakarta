package io.github.pdkovacs.wsgw.e2e.app.conntrack;

import java.util.List;

public interface WsConnections {
    void addConnection(String userId, String connId);

    boolean removeConnection(String userId, String connId);

    List<String> getConnections(String userId);
}
