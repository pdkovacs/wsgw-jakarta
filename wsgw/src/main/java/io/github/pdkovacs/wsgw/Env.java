package io.github.pdkovacs.wsgw;

final class Env {
    static String required(String name) {
        var v = System.getenv(name);
        if (v == null || v.isBlank())
            throw new IllegalStateException("Missing required env var: " + name);
        return v;
    }
    static int intVar(String name, int dflt) {
        var v = System.getenv(name);
        if (v == null || v.isBlank()) return dflt;
        try { return Integer.parseInt(v.trim()); }
        catch (NumberFormatException e) {
            throw new IllegalStateException(name + " must be an integer, got: " + v, e);
        }
    }
    // Duration/boolean as you grow into them
    private Env() {}
}