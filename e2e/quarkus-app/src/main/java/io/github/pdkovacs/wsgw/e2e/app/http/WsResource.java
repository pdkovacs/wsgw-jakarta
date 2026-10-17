package io.github.pdkovacs.wsgw.e2e.app.http;

import io.github.pdkovacs.wsgw.AppPaths;
import io.github.pdkovacs.wsgw.WsgwPaths;
import io.github.pdkovacs.wsgw.e2e.app.conntrack.WsConnections;
import io.github.pdkovacs.wsgw.e2e.app.logging.CtxLogger;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.core.Response;

import java.util.Map;

@Path("/ws")
public class WsResource {

    private static final CtxLogger log = CtxLogger.of(WsResource.class);

    private static final String CONN_ID_PATH_PARAM_NAME = "connId";

    private final WsConnections wsConnections;
    private final RequestUser requestUser;

    public WsResource(WsConnections wsConnections, RequestUser requestUser) {
        this.wsConnections = wsConnections;
        this.requestUser = requestUser;
    }

    @GET
    @Path(AppPaths.CONNECT_FROM_WSGW + "/{" + CONN_ID_PATH_PARAM_NAME + "}")
    public Response connect(@PathParam("connId") String connId) {
        String userId = requestUser.userId();
        if (userId == null) {
            log.error("No user id in session");
            return Response.status(Response.Status.FORBIDDEN).build();
        }
        if (connId == null || connId.isBlank()) {
            log.error("No connection-id header {}", WsgwPaths.CONNECTION_ID_HEADER_KEY);
            return Response.status(Response.Status.BAD_REQUEST).build();
        }
        var log = WsResource.log.with("userId", userId).with("connId", connId);
        log.debug("incoming connection request");
        wsConnections.addConnection(userId, connId);
        return Response.ok().build();
    }

    @POST
    @Path(AppPaths.DISCONNECTED_FROM_WSGW + "/{" + CONN_ID_PATH_PARAM_NAME + "}")
    public Response disconnected(@PathParam(WsgwPaths.CONNECTION_ID_HEADER_KEY) String connId) {
        String userId = requestUser.userId();
        if (userId == null) {
            log.info("incoming ws disconnection request without userId");
            return Response.status(Response.Status.BAD_REQUEST).build();
        }
        var log = WsResource.log.with("userId", userId);
        if (connId == null || connId.isBlank()) {
            log.info("incoming ws disconnection request without connection-id");
            return Response.status(Response.Status.BAD_REQUEST).build();
        }
        log = log.with("connId", connId);
        log.debug("incoming disconnection request");
        if (!wsConnections.removeConnection(userId, connId)) {
            log.info("user has no ws connections");
        }
        return Response.ok().build();
    }

    @POST
    @Path(AppPaths.MESSAGE_FROM_WSGW + "/{" + CONN_ID_PATH_PARAM_NAME + "}")
    public Response message(@PathParam(WsgwPaths.CONNECTION_ID_HEADER_KEY) String connId,
            Map<String, Object> body) {
        if (connId == null || connId.isBlank()) {
            log.info("send message request without connection-id");
            return Response.status(Response.Status.BAD_REQUEST).build();
        }
        log.with("connId", connId).debug("message received body={}", body);
        return Response.ok().build();
    }
}
