package io.github.pdkovacs.wsgw.refapp.config;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Optional;

public record ConnectionTrackingConfig(Type type, Optional<String> url) {

    public enum Type {
        IN_MEMORY("in-memory"),
        DYNAMODB("dynamodb"),
        VALKEY("valkey");

        private final String wireName;

        Type(String wireName) {
            this.wireName = wireName;
        }

        public String wireName() {
            return wireName;
        }

        public static Type fromWireName(String name) {
            for (Type t : values()) {
                if (t.wireName.equalsIgnoreCase(name)) {
                    return t;
                }
            }
            throw new IllegalArgumentException(
                    "E2EAPP_CONNECTION_TRACKING should be one of in-memory|dynamodb|valkey, got: " + name);
        }
    }

    public static ConnectionTrackingConfig parse(String typeValue, String urlValue) {
        Type type = (typeValue == null || typeValue.isBlank())
                ? Type.IN_MEMORY
                : Type.fromWireName(typeValue.trim());

        Optional<String> url = (urlValue == null || urlValue.isBlank())
                ? Optional.empty()
                : Optional.of(validUrl(urlValue.trim()));

        if (type == Type.IN_MEMORY && url.isPresent()) {
            throw new IllegalArgumentException(
                    "E2EAPP_CONNECTION_TRACKING_URL should not be set when E2EAPP_CONNECTION_TRACKING=in-memory");
        }
        if (type == Type.VALKEY && url.isEmpty()) {
            throw new IllegalArgumentException(
                    "E2EAPP_CONNECTION_TRACKING_URL should be set when E2EAPP_CONNECTION_TRACKING=valkey");
        }

        return new ConnectionTrackingConfig(type, url);
    }

    private static String validUrl(String value) {
        try {
            new URI(value);
            return value;
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("E2EAPP_CONNECTION_TRACKING_URL should be a valid URL", e);
        }
    }
}
