package io.github.pdkovacs.wsgw.e2e.app.routehandlers;

import io.github.pdkovacs.wsgw.AppPaths;
import io.github.pdkovacs.wsgw.e2e.app.conntrack.WsConnections;
import io.github.pdkovacs.wsgw.logging.CtxLogger;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;

public class ConnectCallbackServlet extends HttpServlet {

    private static final CtxLogger logger = CtxLogger.of(ConnectCallbackServlet.class);

    private final WsConnections connectionTracking;
    private final Runnable connectRequestCounter;

    public ConnectCallbackServlet(WsConnections connectionTracking, Runnable connectRequestCounter) {
        this.connectionTracking = connectionTracking;
        this.connectRequestCounter = connectRequestCounter;
    }

    public void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        connectRequestCounter.run();

        var path = req.getServletPath();
        var connectPathEnd = AppPaths.CONNECT_FROM_WSGW.length();
        var lastPathElementStart = path.lastIndexOf('/');
        if (connectPathEnd + 1 != lastPathElementStart) {
            logger.debug("Incorrect path end for connect: {}", path);
        }
        var connectionId = path.substring(lastPathElementStart + 1);

        String userId = req.getRemoteUser();
        if (userId == null) {
            resp.sendError(HttpServletResponse.SC_UNAUTHORIZED);
            return;
        }

        connectionTracking.addConnection(userId, connectionId);
        resp.setStatus(HttpServletResponse.SC_NO_CONTENT);
    }
}
