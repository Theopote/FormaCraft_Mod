package com.formacraft.common.generation.component.util;

import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.dto.Dimensions;
import com.formacraft.common.llm.dto.Vec3i;

import java.util.Locale;
import java.util.Map;

/**
 * Axis-aligned footprint bounds for components ({@code min_corner} space).
 */
public final class ComponentFootprintUtil {

    private ComponentFootprintUtil() {}

    public record Bounds(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        public int width() {
            return Math.max(0, maxX - minX);
        }

        public int depth() {
            return Math.max(0, maxZ - minZ);
        }

        public int height() {
            return Math.max(0, maxY - minY);
        }

        public Bounds expandHorizontal(int margin) {
            if (margin <= 0) {
                return this;
            }
            return new Bounds(minX - margin, minY, minZ - margin, maxX + margin, maxY, maxZ + margin);
        }

        public Bounds union(Bounds other) {
            if (other == null) {
                return this;
            }
            return new Bounds(
                    Math.min(minX, other.minX),
                    Math.min(minY, other.minY),
                    Math.min(minZ, other.minZ),
                    Math.max(maxX, other.maxX),
                    Math.max(maxY, other.maxY),
                    Math.max(maxZ, other.maxZ)
            );
        }

        public boolean contains(Bounds inner) {
            if (inner == null) {
                return false;
            }
            return minX <= inner.minX && maxX >= inner.maxX
                    && minZ <= inner.minZ && maxZ >= inner.maxZ;
        }
    }

    public static Bounds bounds(Component component) {
        if (component == null || component.dimensions() == null) {
            return null;
        }
        Vec3i origin = resolveMinCornerOrigin(component);
        if (origin == null) {
            return null;
        }
        Dimensions d = component.dimensions();
        int w = Math.max(1, d.width());
        int h = Math.max(1, d.height());
        int dep = Math.max(1, d.depth());
        return new Bounds(
                origin.x(),
                origin.y(),
                origin.z(),
                origin.x() + w,
                origin.y() + h,
                origin.z() + dep
        );
    }

    public static Vec3i resolveMinCornerOrigin(Component component) {
        if (component == null) {
            return null;
        }
        Vec3i rp = component.relativePosition();
        Dimensions dims = component.dimensions();
        if (rp == null || dims == null) {
            return rp;
        }
        if (isCornerAnchor(component.params())) {
            return rp;
        }
        int offsetX = -(dims.width() / 2);
        int offsetZ = -(dims.depth() / 2);
        return new Vec3i(rp.x() + offsetX, rp.y(), rp.z() + offsetZ);
    }

    public static boolean isCornerAnchor(Map<String, Object> params) {
        String anchorMode = getParamString(params, "anchor_mode", "anchorMode");
        return anchorMode != null && anchorMode.toLowerCase(Locale.ROOT).contains("corner");
    }

    public static String slotKey(Component component) {
        if (component == null || component.slotId() == null || component.slotId().isBlank()) {
            return "__default__";
        }
        return component.slotId().trim();
    }

    public static boolean isMassType(String type) {
        if (type == null || type.isBlank()) {
            return false;
        }
        String normalized = type.trim().toUpperCase(Locale.ROOT);
        return normalized.startsWith("MASS") || "MAIN_MASS".equals(normalized);
    }

    public static boolean isFoundationType(String type) {
        if (type == null) {
            return false;
        }
        String normalized = type.trim().toUpperCase(Locale.ROOT);
        return normalized.contains("FOUNDATION") || normalized.contains("TERRACE") || normalized.equals("BASE");
    }

    private static String getParamString(Map<String, Object> params, String... keys) {
        if (params == null) {
            return null;
        }
        for (String key : keys) {
            Object v = params.get(key);
            if (v == null) {
                continue;
            }
            String s = String.valueOf(v).trim();
            if (!s.isEmpty()) {
                return s;
            }
        }
        return null;
    }
}
