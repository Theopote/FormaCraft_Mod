package com.formacraft.common.dsl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.*;

/** Shared asset is the signature source for Java validation and Python prompting. */
public final class FbdPrimitives {
    public record Role(String name) {}
    private static final Map<String, Map<String, Map<String, Object>>> SIGNATURES = load();
    /** Palette semantic slots only — never block IDs. post kept for SHELL_BOX colonnade. */
    public static final Set<String> ROLES = Set.of(
            "wall", "window", "floor", "roof", "slab", "accent", "post",
            "pillar", "trim", "railing", "light", "stairs");

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
        return switch (op) {
            case "SURFACE_PATTERN", "OPENINGS" -> {
                boolean alongX = Set.of("NORTH", "SOUTH").contains(String.valueOf(p.get("face")));
                long span = alongX ? distance(p, "x0", "x1") : distance(p, "z0", "z1");
                yield product(span, distance(p, "y0", "y1"), number(p, "thickness", 1) + number(p, "ring", 1) + 2);
            }
            case "SHELL_BOX" -> {
                long w = 2 * (number(p, "w", 0) / 2) + 1;
                long d = 2 * (number(p, "d", 0) / 2) + 1;
                long h = number(p, "h", 0);
                boolean twisted = (p.get("twistTurns") instanceof Number turns && Math.abs(turns.doubleValue()) > 0.001)
                        || (p.get("twistPhase") instanceof Number phase && Math.abs(phase.doubleValue()) > 0.001);
                if (!twisted) yield product(w, d, 3 * h + 4);
                long span = 2 * (long) Math.floor(Math.hypot(w, d) / 2) + 1;
                long floorStep = Math.max(3, number(p, "floorStep", 4));
                yield product(span, span, h + h / floorStep + 2)
                        + product(h + 1, w * d + 2 * (w + d - 2), 1);
            }
            case "ROOF_COVER" -> {
                long overhang = number(p, "overhang", 0);
                long w = number(p, "w", 0) + 2 * overhang;
                long d = number(p, "d", 0) + 2 * overhang;
                yield product(w, d, number(p, "rise", 32) + 2);
            }
            case "CLEAR_BOX" -> product(distance(p, "x0", "x1"), distance(p, "y0", "y1"), distance(p, "z0", "z1"));
            case "CYLINDER" -> {
                long r = number(p, "r", 2);
                long diam = 2 * r + 1;
                yield product(diam, diam, number(p, "h", 1));
            }
            case "ARCH_RIB", "BUTTRESS" -> {
                long run = endpointSpan(p);
                long samples = Math.max(12, number(p, "samples", run * 3));
                long pier = number(p, "pierDown", 0);
                yield product(samples + 1, number(p, "thickness", 1) + 1, 2) + pier * 2;
            }
            case "TRUSS_2D" -> {
                long run = endpointSpan(p);
                long height = number(p, "height", number(p, "h", 6));
                yield product(run + 1, height + 1, number(p, "thickness", 1) + 2);
            }
            case "FRAME_GRID_3D" -> {
                long nx = Math.max(1, distance(p, "x0", "x1") / Math.max(1, number(p, "stepX", number(p, "step", 4))) + 1);
                long ny = Math.max(1, distance(p, "y0", "y1") / Math.max(1, number(p, "stepY", number(p, "step", 4))) + 1);
                long nz = Math.max(1, distance(p, "z0", "z1") / Math.max(1, number(p, "stepZ", number(p, "step", 4))) + 1);
                yield product(nx * ny + ny * nz + nz * nx, Math.max(distance(p, "x0", "x1"), Math.max(distance(p, "y0", "y1"), distance(p, "z0", "z1"))),
                        number(p, "thickness", 1) + 1);
            }
            case "STAIR_SYSTEM" -> {
                long run = Math.max(Math.abs(number(p, "toX", 0) - number(p, "fromX", 0)),
                        Math.abs(number(p, "toZ", 0) - number(p, "fromZ", 0))) + 1;
                long width = number(p, "width", 2);
                long clear = number(p, "clearHeight", 3);
                yield product(run, width, 2 + clear * 2);
            }
            default -> product(number(p, "w", 0) + 2 * number(p, "overhang", 0),
                    number(p, "d", 0) + 2 * number(p, "overhang", 0), number(p, "rise", 32) + 2);
        };
    }

    private static long endpointSpan(Map<String, Object> p) {
        long dx = Math.abs(number(p, "toX", 0) - number(p, "fromX", 0));
        long dy = Math.abs(number(p, "toY", 0) - number(p, "fromY", 0));
        long dz = Math.abs(number(p, "toZ", 0) - number(p, "fromZ", 0));
        return Math.max(dx, Math.max(dy, dz)) + 1;
    }

    private static long distance(Map<String, Object> p, String a, String b) {
        return Math.abs(number(p, a, 0) - number(p, b, 0)) + 1;
    }

    private static long number(Map<String, Object> p, String key, long fallback) {
        return p.get(key) instanceof Number n ? n.longValue() : fallback;
    }

    private static long product(long a, long b, long c) {
        try { return Math.multiplyExact(Math.multiplyExact(a, b), c); }
        catch (ArithmeticException e) { return Long.MAX_VALUE; }
    }
}
