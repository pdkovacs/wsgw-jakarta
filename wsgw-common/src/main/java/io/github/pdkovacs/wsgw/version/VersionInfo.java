package io.github.pdkovacs.wsgw.version;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Properties;

/**
 * What a module reports about its own build. Read from the module's filtered
 * {@code META-INF/build-info.properties}; a missing resource or key reads as {@value #UNKNOWN}.
 */
public record VersionInfo(String application, String version, String commit, String buildTime) {

    public static final String RESOURCE = "/META-INF/build-info.properties";
    static final String UNKNOWN = "unknown";

    /** Reads {@link #RESOURCE} from the module that {@code anchor} was loaded from. */
    public static VersionInfo load(Class<?> anchor) {
        try (InputStream in = anchor.getResourceAsStream(RESOURCE)) {
            return in == null ? parse(new Properties()) : read(in);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read " + RESOURCE, e);
        }
    }

    public static VersionInfo read(InputStream in) throws IOException {
        var props = new Properties();
        props.load(in);
        return parse(props);
    }

    private static VersionInfo parse(Properties props) {
        return new VersionInfo(
                props.getProperty("application", UNKNOWN),
                props.getProperty("version", UNKNOWN),
                props.getProperty("commit", UNKNOWN),
                props.getProperty("buildTime", UNKNOWN));
    }

    public String toJson() {
        return "{\"application\":" + quote(application)
                + ",\"version\":" + quote(version)
                + ",\"commit\":" + quote(commit)
                + ",\"buildTime\":" + quote(buildTime) + "}";
    }

    private static String quote(String s) {
        var sb = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.append('"').toString();
    }
}
