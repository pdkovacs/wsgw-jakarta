package io.github.pdkovacs.wsgw.refapp.http;

import io.github.pdkovacs.wsgw.refapp.common.wsgw.WsgwLocator;
import io.github.pdkovacs.wsgw.refapp.config.AppConfig;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

@Path("/config")
public class ConfigResource {

    private final AppConfig appConfig;

    public ConfigResource(AppConfig appConfig) {
        this.appConfig = appConfig;
    }

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public WsgwLocator getConfig() {
        return appConfig.wsgwLocator();
    }
}
