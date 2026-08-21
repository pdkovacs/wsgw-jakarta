package io.github.pdkovacs.wsgw.appward;

import io.github.pdkovacs.wsgw.logging.CtxLogger;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class Relays {
    private static final CtxLogger logger = CtxLogger.of(Relays.class);

    private final Request appwardRequest;
    private final ConcurrentHashMap<String, Relay> relays = new ConcurrentHashMap<>();
    private final int queueSize;

    public Relays(Request appwardRequest, int queueSize) {
        this.appwardRequest = appwardRequest;
        this.queueSize = queueSize;
    }

    public Relay createRelay(Map<String, List<String>> requestHeaders, String connectionId) {
        var relay = new Relay(appwardRequest, requestHeaders, connectionId, queueSize);
        relays.put(connectionId, relay);
        return relay;
    };

    public Relay detachRelay(String connectionId) {   // retire from registry, hand it back
        return relays.remove(connectionId);
    }

    public void close() {
        appwardRequest.close();
    }

    public Request appwardRequest() {
        return appwardRequest;
    }
}
