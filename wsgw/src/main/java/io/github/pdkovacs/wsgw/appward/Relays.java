package io.github.pdkovacs.wsgw.appward;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class Relays {
    private final String appBaseUrl;
    private final ConcurrentHashMap<String, ConnectionRelay> relays = new ConcurrentHashMap<>();

    public Relays(String appBaseUrl) {
        this.appBaseUrl = appBaseUrl;
    }

    public ConnectionRelay createRelay(Map<String, List<String>> requestHeaders, String connectionId) {
        var relay = new ConnectionRelay(appBaseUrl, requestHeaders, connectionId);
        relays.put(connectionId, relay);
        return relay;
    };

    public ConnectionRelay getRelay(String connectionId) {
        var relay = relays.remove(connectionId);
        if  (relay == null) {
            relay.close();
        }
        return relay;
    }
}
