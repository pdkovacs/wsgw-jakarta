package io.github.pdkovacs.wsgw;

public class WsgwPaths {
    public static final String CONNECT_FROM_CLIENT = "/connect";
    public static final String MESSAGE_FROM_APP = "/message";
    // Lower-cased to match Vert.x / Quarkus header lookup conventions.
    public static final String CONNECTION_ID_HEADER_KEY = "x-wsgw-connection-id";
}
