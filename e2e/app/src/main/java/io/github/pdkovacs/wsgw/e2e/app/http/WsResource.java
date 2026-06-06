package io.github.pdkovacs.wsgw.e2e.app.http;

import io.github.pdkovacs.wsgw.e2e.app.common.wsgw.WsgwPaths;
import io.github.pdkovacs.wsgw.e2e.app.conntrack.WsConnections;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.util.Map;

@Path("/ws")
public class WsResource {

    private static final Logger log = Logger.getLogger(WsResource.class);

    private final WsConnections wsConnections;
    private final RequestUser requestUser;

    public WsResource(WsConnections wsConnections, RequestUser requestUser) {
        this.wsConnections = wsConnections;
        this.requestUser = requestUser;
    }

    @GET
    @Path("/connect")
    public Response connect(@HeaderParam(WsgwPaths.CONNECTION_ID_HEADER_KEY) String connId) {
        String userId = requestUser.userId();
        if (userId == null) {
            log.error("No user id in session");
            return Response.status(Response.Status.FORBIDDEN).build();
        }
        if (connId == null || connId.isBlank()) {
            log.errorf("No connection-id header %s", WsgwPaths.CONNECTION_ID_HEADER_KEY);
            return Response.status(Response.Status.BAD_REQUEST).build();
        }
        log.debugf("incoming connection request user=%s connid=%s", userId, connId);
        wsConnections.addConnection(userId, connId);
        return Response.ok().build();
    }

    @POST
    @Path("/disconnected")
    public Response disconnected(@HeaderParam(WsgwPaths.CONNECTION_ID_HEADER_KEY) String connId) {
        String userId = requestUser.userId();
        if (userId == null) {
            log.info("incoming ws disconnection request without userId");
            return Response.status(Response.Status.BAD_REQUEST).build();
        }
        if (connId == null || connId.isBlank()) {
            log.infof("user=%s incoming ws disconnection request without connection-id", userId);
            return Response.status(Response.Status.BAD_REQUEST).build();
        }
        log.debugf("incoming disconnection request user=%s connid=%s", userId, connId);
        if (!wsConnections.removeConnection(userId, connId)) {
            log.infof("user=%s has no ws connections", userId);
        }
        return Response.ok().build();
    }

    @POST
    @Path("/message")
    public Response message(@HeaderParam(WsgwPaths.CONNECTION_ID_HEADER_KEY) String connId,
                            Map<String, Object> body) {
        if (connId == null || connId.isBlank()) {
            log.info("send message request without connection-id");
            return Response.status(Response.Status.BAD_REQUEST).build();
        }
        log.debugf("message received connid=%s body=%s", connId, body);
        return Response.ok().build();
    }
}
