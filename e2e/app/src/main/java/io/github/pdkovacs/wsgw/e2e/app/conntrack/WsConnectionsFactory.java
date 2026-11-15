package io.github.pdkovacs.wsgw.e2e.app.conntrack;

import io.github.pdkovacs.wsgw.e2e.app.Config;

public final class WsConnectionsFactory {

    private WsConnectionsFactory() {
    }

    public static WsConnections wsConnections(Config config) {
        Config.ConnectionTracking tracking = config.getConnectionTracking();
        return switch (tracking) {
            case IN_MEMORY -> new InMemoryConnectionTracker();
            case DYNAMODB, VALKEY -> throw new UnsupportedOperationException(
                    "Connection tracking type '" + tracking.wireName() + "' is not implemented in iteration 1");
        };
    }
}
