package com.formacraft.common.dsl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.*;

/** Shared asset is the signature source for Java validation and Python prompting. */
public final class FbdPrimitives {
    public record Role(String name) {}
    private static final Map<String, Map<String, Map<String, Object>>> SIGNATURES = load();
    public static final Set<String> ROLES = Set.of("wall", "window", "floor", "roof", "slab", "accent");

    private FbdPrimitives() {}

    private static Map<String, Map<String, Map<String, Object>>> load() {
        try (var in = FbdPrimitives.class.getResourceAsStream("/assets/formacraft/fbd/primitives_v0.json")) {
            if (in == null) throw new IOException("Missing FBD primitive signatures");
            return new ObjectMapper().readValue(in, new TypeReference<>() {});
        } catch (IOException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    public static Map<String, Map<String, Object>> signature(String op, String node, String path) {
        var signature = SIGNATURES.get(op);
        if (signature == null) throw new FbdException("unknown_primitive", "typecheck", node, path, "Unsupported primitive: " + op);
        return signature;
    }

    public static void value(String op, String key, Object value, String node, String path) {
        var field = signature(op, node, path).get(key);
        if (field.containsKey("enum") && !((List<?>) field.get("enum")).contains(value)) {
            throw new FbdException("invalid_enum", "typecheck", node, path, key + " must be one of " + field.get("enum"));
        }
        if (value instanceof Number n && field.get("min") instanceof Number min
                && (n.doubleValue() < min.doubleValue() || n.doubleValue() > ((Number) field.get("max")).doubleValue())) {
            throw new FbdException("param_out_of_range", "evaluate", node, path, op + "." + key + " out of range");
        }
    }

    /** Conservative bound counts visited cells too, including rejected/air samples. */
    public static long cost(String op, Map<String, Object> p) {
        if (op.equals("SURFACE_PATTERN")) {
            boolean alongX = Set.of("NORTH", "SOUTH").contains(p.get("face"));
            long span = alongX ? distance(p, "x0", "x1") : distance(p, "z0", "z1");
            return product(span, distance(p, "y0", "y1"), number(p, "thickness", 1) + 1);
        }
        long w = 2 * (number(p, "w", 0) / 2) + 1;
        long d = 2 * (number(p, "d", 0) / 2) + 1;
        if (op.equals("SHELL_BOX")) return product(w, d, 3 * number(p, "h", 0) + 4);
        long overhang = number(p, "overhang", 0);
        return product(w + 2 * overhang, d + 2 * overhang, number(p, "rise", 32) + 2);
    }

    private static long distance(Map<String, Object> p, String a, String b) {
        return Math.abs(number(p, a, 0) - number(p, b, 0)) + 1;
    }

    private static long number(Map<String, Object> p, String key, int fallback) {
        return p.get(key) instanceof Number n ? n.longValue() : fallback;
    }

    private static long product(long a, long b, long c) {
        try { return Math.multiplyExact(Math.multiplyExact(a, b), c); }
        catch (ArithmeticException e) { return Long.MAX_VALUE; }
    }
}
