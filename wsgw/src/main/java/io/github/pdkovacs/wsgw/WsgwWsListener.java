package io.github.pdkovacs.wsgw;

import jakarta.servlet.ServletContextEvent;
import jakarta.servlet.ServletContextListener;
import jakarta.websocket.DeploymentException;
import jakarta.websocket.server.ServerContainer;
import jakarta.websocket.server.ServerEndpointConfig;

public class WsgwWsListener implements ServletContextListener {

    final private MessageRelay messageRelay;

    WsgwWsListener(MessageRelay messageRelay) {
        this.messageRelay = messageRelay;
    }

    @Override
    public void contextInitialized(ServletContextEvent e) {
        var sc = (ServerContainer) e.getServletContext()
                .getAttribute("jakarta.websocket.server.ServerContainer");  // set by WsSci
        try {
            sc.addEndpoint(ServerEndpointConfig.Builder.create(WsgwEndpoint.class, WsgwPaths.CONNECT_FROM_CLIENT)
                    .configurator(new WsgwEndpointConfigurator(messageRelay))
                    .build());
        } catch (DeploymentException ex) { throw new RuntimeException(ex); }
    }
}
