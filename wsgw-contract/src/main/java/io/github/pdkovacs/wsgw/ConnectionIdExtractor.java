package io.github.pdkovacs.wsgw;

public class ConnectionIdExtractor {
    public static final String extract(String servletPath, int elementIndexOfId) {
        var elements = servletPath.split("/");
        if (elements.length > elementIndexOfId) {
            return elements[elementIndexOfId];
        }
        return null;
    }
}
