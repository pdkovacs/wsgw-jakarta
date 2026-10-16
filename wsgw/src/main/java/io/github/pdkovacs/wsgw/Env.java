package io.github.pdkovacs.wsgw;

import java.nio.file.Path;
import java.time.Duration;
import java.time.format.DateTimeParseException;

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
    static double doubleVar(String name, double dflt) {
        var v = System.getenv(name);
        if (v == null || v.isBlank()) return dflt;
        try { return Double.parseDouble(v.trim()); }
        catch (NumberFormatException e) {
            throw new IllegalStateException(name + " must be a number, got: " + v, e);
        }
    }
    // ISO-8601 duration, e.g. PT10S, PT0.5S
    static Duration durationVar(String name, Duration dflt) {
        var v = System.getenv(name);
        if (v == null || v.isBlank()) return dflt;
        try { return Duration.parse(v.trim()); }
        catch (DateTimeParseException e) {
            throw new IllegalStateException(name + " must be an ISO-8601 duration (e.g. PT10S), got: " + v, e);
        }
    }
    static Path pathVar(String name, Path dflt) {
        var v = System.getenv(name);
        if (v == null || v.isBlank()) return dflt;
        return Path.of(v.trim());
    }
    // boolean as you grow into it
    private Env() {}
}