package io.github.pdkovacs.wsgw.e2e.app.config;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.pdkovacs.wsgw.e2e.app.common.wsgw.WsgwLocator;
import jakarta.annotation.PostConstruct;
import jakarta.inject.Singleton;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.List;
import java.util.Optional;

@Singleton
public class AppConfig {

    public static final String ENV_NAME_PREFIX = "E2EAPP_";

    private final int serverPort;
    private final boolean http2;
    private final String passwordCredentialsJson;
    private final String wsgwHost;
    private final int wsgwPort;
    private final ConnectionTrackingConfig connectionTracking;

    private List<PasswordCredentials> passwordCredentialsList;

    public AppConfig(
            @ConfigProperty(name = "E2EAPP_SERVER_PORT", defaultValue = "8080") int serverPort,
            @ConfigProperty(name = "E2EAPP_HTTP2", defaultValue = "false") boolean http2,
            @ConfigProperty(name = "E2EAPP_PASSWORD_CREDENTIALS") String passwordCredentialsJson,
            @ConfigProperty(name = "E2EAPP_WSGW_HOST") String wsgwHost,
            @ConfigProperty(name = "E2EAPP_WSGW_PORT") int wsgwPort,
            @ConfigProperty(name = "E2EAPP_CONNECTION_TRACKING", defaultValue = "in-memory") String trackingType,
            @ConfigProperty(name = "E2EAPP_CONNECTION_TRACKING_URL") Optional<String> trackingUrl) {
        this.serverPort = serverPort;
        this.http2 = http2;
        this.passwordCredentialsJson = passwordCredentialsJson;
        this.wsgwHost = wsgwHost;
        this.wsgwPort = wsgwPort;
        this.connectionTracking = ConnectionTrackingConfig.parse(trackingType, trackingUrl.orElse(null));
    }

    @PostConstruct
    void parsePasswordCredentials() {
        try {
            this.passwordCredentialsList = new ObjectMapper().readValue(
                    passwordCredentialsJson,
                    new TypeReference<List<PasswordCredentials>>() {});
        } catch (Exception e) {
            throw new IllegalArgumentException(
                    "E2EAPP_PASSWORD_CREDENTIALS should be a JSON array of {username, password}", e);
        }
        if (passwordCredentialsList.isEmpty()) {
            throw new IllegalArgumentException("E2EAPP_PASSWORD_CREDENTIALS must contain at least one entry");
        }
    }

    public String envNamePrefix() {
        return ENV_NAME_PREFIX;
    }

    public int serverPort() {
        return serverPort;
    }

    public boolean http2() {
        return http2;
    }

    public List<PasswordCredentials> passwordCredentialsList() {
        return passwordCredentialsList;
    }

    public WsgwLocator wsgwLocator() {
        return new WsgwLocator(wsgwHost, wsgwPort);
    }

    public ConnectionTrackingConfig connectionTracking() {
        return connectionTracking;
    }
}
