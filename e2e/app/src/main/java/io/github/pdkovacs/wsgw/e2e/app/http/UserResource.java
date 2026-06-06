package io.github.pdkovacs.wsgw.e2e.app.http;

import io.github.pdkovacs.wsgw.e2e.app.security.UserInfo;
import io.github.pdkovacs.wsgw.e2e.app.security.UserService;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.List;

@Path("/")
public class UserResource {

    private final UserService userService;
    private final RequestUser requestUser;

    public UserResource(UserService userService, RequestUser requestUser) {
        this.userService = userService;
        this.requestUser = requestUser;
    }

    @GET
    @Path("/user")
    @Produces(MediaType.APPLICATION_JSON)
    public Response user() {
        String userId = requestUser.userId();
        if (userId == null) {
            return Response.status(Response.Status.UNAUTHORIZED).build();
        }
        return Response.ok(userService.getUserInfo(userId)).build();
    }

    @GET
    @Path("/users")
    @Produces(MediaType.APPLICATION_JSON)
    public List<UserInfo> users() {
        return userService.getUsers();
    }
}
