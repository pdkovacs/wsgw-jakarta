package io.github.pdkovacs.wsgw.socket;

import io.github.pdkovacs.wsgw.WsgwPaths;
import io.github.pdkovacs.wsgw.appward.Relay;
import io.github.pdkovacs.wsgw.clientward.SessionRegistrar;
import jakarta.servlet.ServletContextEvent;
import jakarta.servlet.ServletContextListener;
import jakarta.websocket.DeploymentException;
import jakarta.websocket.server.ServerContainer;
import jakarta.websocket.server.ServerEndpointConfig;

public class WsListener implements ServletContextListener {

    final private Relay appwardRelay;
    final private SessionRegistrar registerSession;

    public WsListener(Relay appwardRelay, SessionRegistrar registerSession) {
        this.appwardRelay = appwardRelay;
        this.registerSession = registerSession;
    }

    @Override
    public void contextInitialized(ServletContextEvent e) {
        var sc = (ServerContainer) e.getServletContext()
                .getAttribute("jakarta.websocket.server.ServerContainer"); // set by WsSci
        try {
            sc.addEndpoint(ServerEndpointConfig.Builder.create(Endpoint.class, WsgwPaths.CONNECT_FROM_CLIENT)
                    .configurator(new EndpointConfigurator(appwardRelay, registerSession))
                    .build());
        } catch (DeploymentException ex) {
            throw new RuntimeException(ex);
        }
    }
}
