package io.github.pdkovacs.wsgw.refapp.http;

import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

@Path("/app-info")
@ApplicationScoped
public class AppInfoResource {

    private static final Logger log = Logger.getLogger(AppInfoResource.class);

    public record AppInfo(String application, String version, String commit, String buildTime) {
    }

    private AppInfo appInfo;

    @PostConstruct
    void load() {
        Properties props = new Properties();
        try (InputStream in = Thread.currentThread().getContextClassLoader()
                .getResourceAsStream("META-INF/build-info.properties")) {
            if (in != null) {
                props.load(in);
            } else {
                log.warn("META-INF/build-info.properties not found on classpath");
            }
        } catch (IOException e) {
            log.error("failed to read build-info.properties", e);
        }
        this.appInfo = new AppInfo(
                props.getProperty("application", "unknown"),
                props.getProperty("version", "unknown"),
                props.getProperty("commit", "unknown"),
                props.getProperty("buildTime", "unknown"));
    }

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    @Public
    public Response getAppInfo() {
        return Response.ok(appInfo).build();
    }
}
