package io.github.pdkovacs.wsgw.socket;

import io.github.pdkovacs.wsgw.WsgwPaths;
import io.github.pdkovacs.wsgw.appward.Relays;
import jakarta.servlet.ServletContextEvent;
import jakarta.servlet.ServletContextListener;
import jakarta.websocket.DeploymentException;
import jakarta.websocket.server.ServerContainer;
import jakarta.websocket.server.ServerEndpointConfig;

public class WsListener implements ServletContextListener {

    final private Relays appwardRelay;
    final private SessionRegistrar sessionRegistrar;
    private final Disconnector disconnector;

    public WsListener(Relays appwardRelay, SessionRegistrar sessionRegistrar, Disconnector disconnector) {
        this.appwardRelay = appwardRelay;
        this.sessionRegistrar = sessionRegistrar;
        this.disconnector = disconnector;
    }

    @Override
    public void contextInitialized(ServletContextEvent e) {
        var sc = (ServerContainer) e.getServletContext()
                .getAttribute("jakarta.websocket.server.ServerContainer"); // set by WsSci
        try {
            sc.addEndpoint(ServerEndpointConfig.Builder.create(Endpoint.class, WsgwPaths.CONNECT_FROM_CLIENT)
                    .configurator(new EndpointConfigurator(appwardRelay, sessionRegistrar, disconnector))
                    .build());
        } catch (DeploymentException ex) {
            throw new RuntimeException(ex);
        }
    }
}
