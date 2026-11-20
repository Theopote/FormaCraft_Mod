package com.formacraft.common.generation.component.util;

import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.dto.Dimensions;
import com.formacraft.common.llm.dto.Slot;
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
        ResolvedComponentGeometry geometry = ResolvedComponentGeometry.resolve(component);
        return geometry == null ? null : geometry.bounds();
    }

    public static Vec3i resolveMinCornerOrigin(Component component) {
        if (component == null) return null;
        ResolvedComponentGeometry geometry = ResolvedComponentGeometry.resolve(component);
        return geometry == null ? component.relativePosition() : geometry.origin();
    }

    /**
     * Plan-space half-open XZ AABB {@code [minX, maxX, minZ, maxZ]} for ownership / exclusivity.
     * Uses authored dimensions + {@code anchor_mode} + slot.anchor — does <em>not</em> apply
     * {@link ResolvedComponentGeometry#normalizeBody} min-3 MASS clamp (identity must match Python).
     * Missing relativePosition/dimensions → null (never invent a 1×1 box).
     */
    public static int[] planSpaceFootprintXz(Component component, Map<String, Slot> slotMap) {
        if (component == null || component.relativePosition() == null || component.dimensions() == null) {
            return null;
        }
        Dimensions d = component.dimensions();
        int w = Math.max(1, d.width());
        int depth = Math.max(1, d.depth());
        Vec3i p = component.relativePosition();
        int minX;
        int minZ;
        if (isCornerAnchor(component.params())) {
            minX = p.x();
            minZ = p.z();
        } else {
            minX = p.x() - w / 2;
            minZ = p.z() - depth / 2;
        }
        Vec3i slotAnchor = slotAnchorOrZero(slotMap, authoredSlotId(component));
        minX += slotAnchor.x();
        minZ += slotAnchor.z();
        return new int[] {minX, minX + w, minZ, minZ + depth};
    }

    /** Top-level {@code slot_id} or params alias {@code slot_id}/{@code slotId}. */
    public static String authoredSlotId(Component component) {
        if (component == null) {
            return null;
        }
        if (component.slotId() != null && !component.slotId().isBlank()) {
            return component.slotId().trim();
        }
        return getParamString(component.params(), "slot_id", "slotId");
    }

    public static boolean xzFootprintsOverlap(int[] a, int[] b) {
        if (a == null || b == null || a.length < 4 || b.length < 4) {
            return false;
        }
        return a[0] < b[1] && b[0] < a[1] && a[2] < b[3] && b[2] < a[3];
    }

    public static Vec3i slotAnchorOrZero(Map<String, Slot> slotMap, String slotId) {
        if (slotMap == null || slotId == null || slotId.isBlank()) {
            return new Vec3i(0, 0, 0);
        }
        Slot slot = slotMap.get(slotId.trim());
        if (slot == null || slot.anchor() == null) {
            return new Vec3i(0, 0, 0);
        }
        return slot.anchor();
    }

    public static boolean isCornerAnchor(Map<String, Object> params) {
        String anchorMode = getParamString(params, "anchor_mode", "anchorMode");
        return anchorMode != null && anchorMode.toLowerCase(Locale.ROOT).contains("corner");
    }

    /** Older satellite generators default to corner space; an explicit centre must still work. */
    public static Vec3i resolveLegacyCornerOrigin(Component component) {
        if (component == null) return null;
        String mode = getParamString(component.params(), "anchor_mode", "anchorMode");
        return "center".equalsIgnoreCase(mode) ? resolveMinCornerOrigin(component) : component.relativePosition();
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
