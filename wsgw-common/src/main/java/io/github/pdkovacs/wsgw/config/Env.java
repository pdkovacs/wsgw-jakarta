package io.github.pdkovacs.wsgw.config;

import java.nio.file.Path;
import java.time.Duration;
import java.time.format.DateTimeParseException;
import java.util.function.Function;

/**
 * Typed reads of configuration variables. The lookup is injected so that a
 * {@code fromEnv(Env)} can be tested with a {@code Map::get} instead of the
 * process environment.
 *
 * <p>A variable that is unset or blank counts as absent: the default applies,
 * or a required read fails. A malformed value always fails.
 */
public final class Env {

    private final Function<String, String> lookup;

    private Env(Function<String, String> lookup) {
        this.lookup = lookup;
    }

    public static Env of(Function<String, String> lookup) {
        return new Env(lookup);
    }

    public static Env system() {
        return new Env(System::getenv);
    }

    public String required(String name) {
        var v = lookup.apply(name);
        if (v == null || v.isBlank())
            throw new IllegalStateException("Missing required env var: " + name);
        return v;
    }
    public int requiredInt(String name) {
        return parseInt(name, required(name));
    }
    // Returns the raw value, or dflt when absent
    public String stringVar(String name, String dflt) {
        var v = lookup.apply(name);
        if (v == null || v.isBlank()) return dflt;
        return v;
    }
    public int intVar(String name, int dflt) {
        var v = lookup.apply(name);
        if (v == null || v.isBlank()) return dflt;
        return parseInt(name, v);
    }
    public double doubleVar(String name, double dflt) {
        var v = lookup.apply(name);
        if (v == null || v.isBlank()) return dflt;
        try { return Double.parseDouble(v.trim()); }
        catch (NumberFormatException e) {
            throw new IllegalStateException(name + " must be a number, got: " + v, e);
        }
    }
    // Strict, unlike Boolean.parseBoolean: anything but true/false (any case) is an error
    public boolean booleanVar(String name, boolean dflt) {
        var v = lookup.apply(name);
        if (v == null || v.isBlank()) return dflt;
        return switch (v.trim().toLowerCase()) {
            case "true" -> true;
            case "false" -> false;
            default -> throw new IllegalStateException(name + " must be true or false, got: " + v);
        };
    }
    // ISO-8601 duration, e.g. PT10S, PT0.5S
    public Duration durationVar(String name, Duration dflt) {
        var v = lookup.apply(name);
        if (v == null || v.isBlank()) return dflt;
        try { return Duration.parse(v.trim()); }
        catch (DateTimeParseException e) {
            throw new IllegalStateException(name + " must be an ISO-8601 duration (e.g. PT10S), got: " + v, e);
        }
    }
    public Path pathVar(String name, Path dflt) {
        var v = lookup.apply(name);
        if (v == null || v.isBlank()) return dflt;
        return Path.of(v.trim());
    }

    private static int parseInt(String name, String v) {
        try { return Integer.parseInt(v.trim()); }
        catch (NumberFormatException e) {
            throw new IllegalStateException(name + " must be an integer, got: " + v, e);
        }
    }
}
