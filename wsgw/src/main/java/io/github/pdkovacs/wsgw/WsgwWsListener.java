package io.github.pdkovacs.wsgw;

import io.github.pdkovacs.wsgw.appside.ToApp;
import io.github.pdkovacs.wsgw.clientside.SessionRegistrar;
import jakarta.servlet.ServletContextEvent;
import jakarta.servlet.ServletContextListener;
import jakarta.websocket.DeploymentException;
import jakarta.websocket.server.ServerContainer;
import jakarta.websocket.server.ServerEndpointConfig;

public class WsgwWsListener implements ServletContextListener {

    final private ToApp messageRelay;
    final private SessionRegistrar registerSession;

    WsgwWsListener(ToApp messageRelay, SessionRegistrar registerSession) {
        this.messageRelay = messageRelay;
        this.registerSession = registerSession;
    }

    @Override
    public void contextInitialized(ServletContextEvent e) {
        var sc = (ServerContainer) e.getServletContext()
                .getAttribute("jakarta.websocket.server.ServerContainer"); // set by WsSci
        try {
            sc.addEndpoint(ServerEndpointConfig.Builder.create(WsgwEndpoint.class, WsgwPaths.CONNECT_FROM_CLIENT)
                    .configurator(new WsgwEndpointConfigurator(messageRelay, registerSession))
                    .build());
        } catch (DeploymentException ex) {
            throw new RuntimeException(ex);
        }
    }
}
