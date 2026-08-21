package io.github.pdkovacs.wsgw.appward;

import io.github.pdkovacs.wsgw.logging.CtxLogger;

import java.time.Duration;
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
    }

    ;

    public Relay get(String connectionId) {   // retire from registry, hand it back
        return relays.get(connectionId);
    }

    public void scanForRemoveDefunctAsync() {
        Thread.ofVirtual().start(() -> {
            for (var relay : relays.entrySet().stream().toList()) {
                if (relay.getValue().isDefunct()) {
                    relays.remove(relay.getKey());
                }
            }
        });
    }

    public void stop() {
        for (Relay relay : relays.values()) {
            relay.join(Duration.ofSeconds(5));
        }
        appwardRequest.stop();
    }

    public Request appwardRequest() {
        return appwardRequest;
    }
}
