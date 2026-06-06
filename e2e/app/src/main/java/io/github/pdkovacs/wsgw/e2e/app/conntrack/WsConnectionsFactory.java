package io.github.pdkovacs.wsgw.e2e.app.conntrack;

import io.github.pdkovacs.wsgw.e2e.app.config.AppConfig;
import io.github.pdkovacs.wsgw.e2e.app.config.ConnectionTrackingConfig;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;

@ApplicationScoped
public class WsConnectionsFactory {

    @Produces
    @Singleton
    public WsConnections wsConnections(AppConfig appConfig) {
        ConnectionTrackingConfig tracking = appConfig.connectionTracking();
        return switch (tracking.type()) {
            case IN_MEMORY -> new InMemoryConnectionTracker();
            case DYNAMODB, VALKEY -> throw new UnsupportedOperationException(
                    "Connection tracking type '" + tracking.type().wireName() + "' is not implemented in iteration 1");
        };
    }
}
