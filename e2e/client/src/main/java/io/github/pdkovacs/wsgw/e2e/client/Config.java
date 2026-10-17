package io.github.pdkovacs.wsgw.e2e.client;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.pdkovacs.wsgw.config.Env;

import java.nio.file.Path;
import java.util.List;

public class Config {

    // -----------------------------------------------------------------------------
    // --- value types ---
    // -----------------------------------------------------------------------------

    public record PasswordCredentials(String username, String password) {
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
        return baseDir != null ? baseDir : Path.of(System.getProperty("java.io.tmpdir"), "wsgw-e2e-client-tomcat");
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
    // --- construction ---
    // -----------------------------------------------------------------------------

    public static Config fromEnv() {
        return fromEnv(Env.system());
    }

    public static Config fromEnv(Env env) {
        var config = new Config();
        config.setServerPort(env.intVar("E2ECLI_SERVER_PORT", config.getServerPort()));
        config.setHttp2(env.booleanVar("E2ECLI_HTTP2", config.isHttp2()));
        config.setBaseDir(env.pathVar("E2ECLI_BASE_DIR", null));

        config.setWsgwHost(env.required("E2ECLI_WSGW_HOST"));
        config.setWsgwPort(env.requiredInt("E2ECLI_WSGW_PORT"));

        config.setPasswordCredentials(parsePasswordCredentials(env.required("E2ECLI_PASSWORD_CREDENTIALS")));
        return config;
    }

    private static List<PasswordCredentials> parsePasswordCredentials(String json) {
        List<PasswordCredentials> credentials;
        try {
            credentials = new ObjectMapper().readValue(json, new TypeReference<List<PasswordCredentials>>() {});
        } catch (Exception e) {
            throw new IllegalStateException(
                    "E2ECLI_PASSWORD_CREDENTIALS should be a JSON array of {username, password}", e);
        }
        if (credentials.isEmpty()) {
            throw new IllegalStateException("E2ECLI_PASSWORD_CREDENTIALS must contain at least one entry");
        }
        return credentials;
    }
}
