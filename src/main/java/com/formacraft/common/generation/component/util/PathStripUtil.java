package com.formacraft.common.generation.component.util;

import com.formacraft.common.compiler.semantic.SemanticComponent;
import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.dto.Dimensions;
import com.formacraft.common.llm.dto.Vec3i;
import com.formacraft.common.patch.BlockPatch;
import com.formacraft.common.semantic.SemanticPart;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;

/**
 * World-free PATH/ROAD strip raster (GEOMETRY).
 * When {@code params.from} + {@code params.to} are present, emits a connected strip;
 * otherwise callers keep AABB pad behaviour.
 */
public final class PathStripUtil {
    private PathStripUtil() {}

    public record Endpoints(List<Vec3i> points, int width, int height) {}

    /** Resolve from/to (+ optional waypoints). Null when endpoints are absent or incomplete. */
    public static Endpoints resolveEndpoints(Component c) {
        if (c == null || c.params() == null) return null;
        Map<String, Object> params = c.params();
        Vec3i from = vec3OrNull(params.get("from"));
        Vec3i to = vec3OrNull(params.get("to"));
        if (from == null || to == null) return null;

        List<Vec3i> points = new ArrayList<>();
        points.add(from);
        Object wp = params.get("waypoints");
        if (wp instanceof List<?> list) {
            for (Object item : list) {
                Vec3i v = vec3OrNull(item);
                if (v != null) points.add(v);
            }
        }
        points.add(to);

        int width = ComponentParamParsers.intParam(params, 0, "width", "path_width", "pathWidth", "road_width");
        if (width <= 0 && c.dimensions() != null) {
            Dimensions d = c.dimensions();
            width = Math.max(1, Math.min(Math.max(1, d.width()), Math.max(1, d.depth())));
            if (width > 7) width = 3;
        }
        if (width <= 0) width = 3;
        width = Math.max(1, Math.min(7, width));

        int height = 1;
        if (c.dimensions() != null) {
            height = Math.max(1, Math.min(2, c.dimensions().height()));
        }
        Integer hParam = ComponentParamParsers.intOrNull(params, "height", "path_height");
        if (hParam != null && hParam > 0) {
            height = Math.max(1, Math.min(2, hParam));
        }
        return new Endpoints(List.copyOf(points), width, height);
    }

    public static boolean hasEndpoints(Component c) {
        return resolveEndpoints(c) != null;
    }

    /**
     * Emit a strip along from→waypoints→to in the same local frame as {@code relative_position}.
     */
    public static List<BlockPatch> emitStrip(
            SemanticComponent semantic,
            Endpoints endpoints,
            String fallbackBlock,
            String... materialKeys
    ) {
        List<BlockPatch> out = new ArrayList<>();
        if (semantic == null || endpoints == null || endpoints.points().size() < 2) return out;

        Set<Long> placed = new LinkedHashSet<>();
        List<int[]> center = rasterPolyline(endpoints.points());
        int r = Math.max(0, (endpoints.width() - 1) / 2);
        int height = endpoints.height();

        BiFunction<SemanticPart, String, String> material = (part, fb) -> {
            String block = ComponentMaterialResolver.resolve(semantic, part, null, materialKeys);
            if (block == null || block.isBlank()) {
                block = ComponentMaterialResolver.resolve(semantic, SemanticPart.ROAD_SURFACE, fb, materialKeys);
            }
            return block != null && !block.isBlank() ? block : fb;
        };

        for (int[] p : center) {
            int cx = p[0], cy = p[1], cz = p[2];
            for (int ox = -r; ox <= r; ox++) {
                for (int oz = -r; oz <= r; oz++) {
                    boolean edge = Math.abs(ox) == r || Math.abs(oz) == r;
                    SemanticPart part = (endpoints.width() >= 3 && edge)
                            ? SemanticPart.ROAD_EDGE : SemanticPart.ROAD_SURFACE;
                    String block = material.apply(part, fallbackBlock);
                    for (int y = 0; y < height; y++) {
                        int x = cx + ox;
                        int yy = cy + y;
                        int z = cz + oz;
                        long key = pack(x, yy, z);
                        if (!placed.add(key)) continue;
                        out.add(new BlockPatch(BlockPatch.PLACE, x, yy, z, block));
                    }
                }
            }
        }
        return out;
    }

    /** Rasterize polyline segments on XZ with Y lerp (inclusive endpoints). */
    public static List<int[]> rasterPolyline(List<Vec3i> points) {
        List<int[]> out = new ArrayList<>();
        if (points == null || points.isEmpty()) return out;
        Set<Long> seen = new LinkedHashSet<>();
        for (int i = 0; i < points.size() - 1; i++) {
            Vec3i a = points.get(i);
            Vec3i b = points.get(i + 1);
            for (int[] cell : rasterSegment(a.x(), a.y(), a.z(), b.x(), b.y(), b.z())) {
                long key = pack(cell[0], cell[1], cell[2]);
                if (seen.add(key)) out.add(cell);
            }
        }
        if (out.isEmpty()) {
            Vec3i p = points.getFirst();
            out.add(new int[]{p.x(), p.y(), p.z()});
        }
        return out;
    }

    static List<int[]> rasterSegment(int x0, int y0, int z0, int x1, int y1, int z1) {
        List<int[]> out = new ArrayList<>();
        int dx = Math.abs(x1 - x0);
        int dz = Math.abs(z1 - z0);
        int steps = Math.max(dx, dz);
        if (steps == 0) {
            out.add(new int[]{x0, y0, z0});
            return out;
        }
        for (int i = 0; i <= steps; i++) {
            int x = x0 + (int) Math.round((x1 - x0) * (double) i / steps);
            int y = y0 + (int) Math.round((y1 - y0) * (double) i / steps);
            int z = z0 + (int) Math.round((z1 - z0) * (double) i / steps);
            out.add(new int[]{x, y, z});
        }
        return out;
    }

    public static Vec3i vec3OrNull(Object raw) {
        if (raw == null) return null;
        if (raw instanceof Vec3i v) return v;
        if (raw instanceof Map<?, ?> m) {
            Integer x = asInt(m.get("x"));
            Integer y = asInt(m.get("y"));
            Integer z = asInt(m.get("z"));
            if (x == null || y == null || z == null) return null;
            return new Vec3i(x, y, z);
        }
        if (raw instanceof List<?> list && list.size() >= 3) {
            Integer x = asInt(list.get(0));
            Integer y = asInt(list.get(1));
            Integer z = asInt(list.get(2));
            if (x == null || y == null || z == null) return null;
            return new Vec3i(x, y, z);
        }
        return null;
    }

    private static Integer asInt(Object v) {
        if (v == null) return null;
        if (v instanceof Number n) return n.intValue();
        try {
            return Integer.parseInt(String.valueOf(v).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static long pack(int x, int y, int z) {
        return ((long) (x & 0x1FFFFF) << 42) | ((long) (y & 0xFFFFF) << 21) | (long) (z & 0x1FFFFF);
    }
}
