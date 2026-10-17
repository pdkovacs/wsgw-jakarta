package io.github.pdkovacs.wsgw.e2e.app;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.pdkovacs.wsgw.config.Env;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

public class Config {

    // -----------------------------------------------------------------------------
    // --- value types ---
    // -----------------------------------------------------------------------------

    public record PasswordCredentials(String username, String password) {
    }

    public enum ConnectionTracking {
        IN_MEMORY("in-memory"),
        DYNAMODB("dynamodb"),
        VALKEY("valkey");

        private final String wireName;

        ConnectionTracking(String wireName) {
            this.wireName = wireName;
        }

        public String wireName() {
            return wireName;
        }

        public static ConnectionTracking fromWireName(String name) {
            for (ConnectionTracking t : values()) {
                if (t.wireName.equalsIgnoreCase(name.trim())) {
                    return t;
                }
            }
            throw new IllegalStateException(
                    "E2EAPP_CONNECTION_TRACKING should be one of in-memory|dynamodb|valkey, got: " + name);
        }
    }

    // -----------------------------------------------------------------------------
    // --- base ---
    // -----------------------------------------------------------------------------

    private int serverPort = 8080;

    private boolean http2 = false;

    private Path baseDir;

    // -----------------------------------------------------------------------------
    // --- the gateway ---
    // -----------------------------------------------------------------------------

    private String wsgwHost;

    private int wsgwPort;

    // -----------------------------------------------------------------------------
    // --- authentication ---
    // -----------------------------------------------------------------------------

    private List<PasswordCredentials> passwordCredentials;

    // -----------------------------------------------------------------------------
    // --- connection tracking ---
    // -----------------------------------------------------------------------------

    private ConnectionTracking connectionTracking = ConnectionTracking.IN_MEMORY;

    private String connectionTrackingUrl;

    // -----------------------------------------------------------------------------
    // --- base ---
    // -----------------------------------------------------------------------------

    public int getServerPort() {
        return serverPort;
    }

    public void setServerPort(int serverPort) {
        this.serverPort = serverPort;
    }

    public boolean isHttp2() {
        return http2;
    }

    public void setHttp2(boolean http2) {
        this.http2 = http2;
    }

    public Path getBaseDir() {
        return baseDir != null ? baseDir : Path.of(System.getProperty("java.io.tmpdir"), "wsgw-e2e-app-tomcat");
    }

    public void setBaseDir(Path baseDir) {
        this.baseDir = baseDir;
    }

    // -----------------------------------------------------------------------------
    // --- the gateway ---
    // -----------------------------------------------------------------------------

    public String getWsgwHost() {
        return wsgwHost;
    }

    public void setWsgwHost(String wsgwHost) {
        this.wsgwHost = wsgwHost;
    }

    public int getWsgwPort() {
        return wsgwPort;
    }

    public void setWsgwPort(int wsgwPort) {
        this.wsgwPort = wsgwPort;
    }

    // -----------------------------------------------------------------------------
    // --- authentication ---
    // -----------------------------------------------------------------------------

    public List<PasswordCredentials> getPasswordCredentials() {
        return passwordCredentials;
    }

    public void setPasswordCredentials(List<PasswordCredentials> passwordCredentials) {
        this.passwordCredentials = passwordCredentials;
    }

    // -----------------------------------------------------------------------------
    // --- connection tracking ---
    // -----------------------------------------------------------------------------

    public ConnectionTracking getConnectionTracking() {
        return connectionTracking;
    }

    public void setConnectionTracking(ConnectionTracking connectionTracking) {
        this.connectionTracking = connectionTracking;
    }

    public Optional<String> getConnectionTrackingUrl() {
        return Optional.ofNullable(connectionTrackingUrl);
    }

    public void setConnectionTrackingUrl(String connectionTrackingUrl) {
        this.connectionTrackingUrl = connectionTrackingUrl;
    }

    // -----------------------------------------------------------------------------
    // --- construction ---
    // -----------------------------------------------------------------------------

    public static Config fromEnv() {
        return fromEnv(Env.system());
    }

    public static Config fromEnv(Env env) {
        var config = new Config();
        config.setServerPort(env.intVar("E2EAPP_SERVER_PORT", config.getServerPort()));
        config.setHttp2(env.booleanVar("E2EAPP_HTTP2", config.isHttp2()));
        config.setBaseDir(env.pathVar("E2EAPP_BASE_DIR", null));

        config.setWsgwHost(env.required("E2EAPP_WSGW_HOST"));
        config.setWsgwPort(env.requiredInt("E2EAPP_WSGW_PORT"));

        config.setPasswordCredentials(parsePasswordCredentials(env.required("E2EAPP_PASSWORD_CREDENTIALS")));

        var trackingType = env.stringVar("E2EAPP_CONNECTION_TRACKING", null);
        if (trackingType != null) {
            config.setConnectionTracking(ConnectionTracking.fromWireName(trackingType));
        }
        var trackingUrl = env.stringVar("E2EAPP_CONNECTION_TRACKING_URL", null);
        if (trackingUrl != null) {
            config.setConnectionTrackingUrl(validUrl(trackingUrl.trim()));
        }
        checkConnectionTracking(config);
        return config;
    }

    private static List<PasswordCredentials> parsePasswordCredentials(String json) {
        List<PasswordCredentials> credentials;
        try {
            credentials = new ObjectMapper().readValue(json, new TypeReference<List<PasswordCredentials>>() {});
        } catch (Exception e) {
            throw new IllegalStateException(
                    "E2EAPP_PASSWORD_CREDENTIALS should be a JSON array of {username, password}", e);
        }
        if (credentials.isEmpty()) {
            throw new IllegalStateException("E2EAPP_PASSWORD_CREDENTIALS must contain at least one entry");
        }
        return credentials;
    }

    private static String validUrl(String value) {
        try {
            new URI(value);
            return value;
        } catch (URISyntaxException e) {
            throw new IllegalStateException("E2EAPP_CONNECTION_TRACKING_URL should be a valid URL", e);
        }
    }

    private static void checkConnectionTracking(Config config) {
        var url = config.getConnectionTrackingUrl();
        if (config.getConnectionTracking() == ConnectionTracking.IN_MEMORY && url.isPresent()) {
            throw new IllegalStateException(
                    "E2EAPP_CONNECTION_TRACKING_URL should not be set when E2EAPP_CONNECTION_TRACKING=in-memory");
        }
        if (config.getConnectionTracking() == ConnectionTracking.VALKEY && url.isEmpty()) {
            throw new IllegalStateException(
                    "E2EAPP_CONNECTION_TRACKING_URL should be set when E2EAPP_CONNECTION_TRACKING=valkey");
        }
    }
}
