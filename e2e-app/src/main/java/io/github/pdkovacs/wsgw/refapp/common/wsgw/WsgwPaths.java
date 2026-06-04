package io.github.pdkovacs.wsgw.refapp.common.wsgw;

public final class WsgwPaths {

    public static final String CONNECT_PATH = "/connect";
    public static final String DISCONNECTED_PATH = "/disconnected";
    public static final String MESSAGE_PATH = "/message";

    // Lower-cased to match Vert.x / Quarkus header lookup conventions.
    public static final String CONNECTION_ID_HEADER_KEY = "x-wsgw-connection-id";

    public static final String CONN_ID_PATH_PARAM_NAME = "connectionId";

    private WsgwPaths() {
    }
}
