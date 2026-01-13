package com.formacraft.common.component.placement;

import net.minecraft.util.math.Direction;

import java.util.Locale;
import java.util.Map;

/**
 * FacingDeriver（v1）：
 * - 把低层 facing 变成“由语义策略推导出来的结果”
 * - 当用户/LLM 没有显式指定 facing 时，生成器可调用此类进行推导
 */
public final class FacingDeriver {
    private FacingDeriver() {}

    /**
     * 根据 placementSpec.facingPolicy 推导朝向。
     *
     * @param spec 构件 placementSpec（可空；空则直接返回 fallbackFacing）
     * @param hostFacing host 推导朝向（通常为 socketWorldFacing；可空）
     * @param fallbackFacing 兜底朝向（通常为 mountFromFacing 或 Direction.SOUTH）
     * @param policyHints 可选 hints（例如 edge_endpoints），用于 ALONG_EDGE 推导
     */
    public static Direction derive(ComponentPlacementSpec spec,
                                   Direction hostFacing,
                                   Direction fallbackFacing,
                                   Map<String, Object> policyHints) {
        Direction fb = normalize(fallbackFacing);
        if (spec == null || spec.facingPolicy == null) return fb;

        return switch (spec.facingPolicy) {
            case NONE -> fb;
            case USER_DEFINED -> fb; // 没显式给时就用 fallback（不猜）
            case DERIVED_FROM_HOST, OUTWARD_NORMAL -> normalize(hostFacing != null ? hostFacing : fb);
            case ALONG_EDGE -> {
                Direction d = deriveAlongEdge(policyHints);
                yield d != null ? d : (hostFacing != null ? normalize(hostFacing) : fb);
            }
        };
    }

    /**
     * ALONG_EDGE 推导：
     * 允许传入下列任一形式（best-effort）：
     * - edge: { ax, az, bx, bz }
     * - edge: { a:{x,z}, b:{x,z} }
     * - edge_a/edge_b: {x,z}
     */
    @SuppressWarnings("unchecked")
    private static Direction deriveAlongEdge(Map<String, Object> hints) {
        if (hints == null) return null;

        Object edge0 = hints.get("edge");
        if (edge0 instanceof Map<?, ?> em) {
            Map<String, Object> edge = (Map<String, Object>) em;
            Integer ax = getInt(edge, "ax", "a_x", "x1");
            Integer az = getInt(edge, "az", "a_z", "z1");
            Integer bx = getInt(edge, "bx", "b_x", "x2");
            Integer bz = getInt(edge, "bz", "b_z", "z2");
            if (ax != null && az != null && bx != null && bz != null) {
                return cardinalFromDelta(bx - ax, bz - az);
            }
            // nested a/b
            Object a0 = edge.get("a");
            Object b0 = edge.get("b");
            if (a0 instanceof Map<?, ?> am && b0 instanceof Map<?, ?> bm) {
                Map<String, Object> a = (Map<String, Object>) am;
                Map<String, Object> b = (Map<String, Object>) bm;
                Integer ax2 = getInt(a, "x", "X");
                Integer az2 = getInt(a, "z", "Z");
                Integer bx2 = getInt(b, "x", "X");
                Integer bz2 = getInt(b, "z", "Z");
                if (ax2 != null && az2 != null && bx2 != null && bz2 != null) {
                    return cardinalFromDelta(bx2 - ax2, bz2 - az2);
                }
            }
        }

        Object a0 = hints.get("edge_a");
        Object b0 = hints.get("edge_b");
        if (a0 instanceof Map<?, ?> am && b0 instanceof Map<?, ?> bm) {
            Map<String, Object> a = (Map<String, Object>) am;
            Map<String, Object> b = (Map<String, Object>) bm;
            Integer ax = getInt(a, "x", "X");
            Integer az = getInt(a, "z", "Z");
            Integer bx = getInt(b, "x", "X");
            Integer bz = getInt(b, "z", "Z");
            if (ax != null && az != null && bx != null && bz != null) {
                return cardinalFromDelta(bx - ax, bz - az);
            }
        }
        return null;
    }

    private static Direction normalize(Direction d) {
        if (d == null || !d.getAxis().isHorizontal()) return Direction.SOUTH;
        return d;
    }

    private static Integer getInt(Map<String, Object> m, String... keys) {
        if (m == null || keys == null) return null;
        for (String k : keys) {
            if (k == null) continue;
            Object v = m.get(k);
            if (v == null) continue;
            if (v instanceof Number n) return n.intValue();
            try {
                return Integer.parseInt(String.valueOf(v).trim());
            } catch (Throwable ignored) {}
        }
        return null;
    }

    /**
     * 将 dx/dz 映射为最接近的水平朝向（N/E/S/W）。
     * dx>0 => EAST, dx<0 => WEST；dz>0 => SOUTH, dz<0 => NORTH（Minecraft 坐标系）。
     */
    public static Direction cardinalFromDelta(int dx, int dz) {
        if (dx == 0 && dz == 0) return null;
        int adx = Math.abs(dx);
        int adz = Math.abs(dz);
        if (adx >= adz) {
            return dx >= 0 ? Direction.EAST : Direction.WEST;
        }
        return dz >= 0 ? Direction.SOUTH : Direction.NORTH;
    }

    /**
     * 解析字符串为 FacingPolicy（给外部系统/JSON 兼容用）。
     */
    public static FacingPolicy parsePolicy(String s) {
        if (s == null || s.isBlank()) return FacingPolicy.NONE;
        try {
            return FacingPolicy.valueOf(s.trim().toUpperCase(Locale.ROOT));
        } catch (Throwable ignored) {
            String u = s.trim().toUpperCase(Locale.ROOT);
            if (u.contains("HOST")) return FacingPolicy.DERIVED_FROM_HOST;
            if (u.contains("OUT")) return FacingPolicy.OUTWARD_NORMAL;
            if (u.contains("EDGE")) return FacingPolicy.ALONG_EDGE;
            if (u.contains("USER")) return FacingPolicy.USER_DEFINED;
            return FacingPolicy.NONE;
        }
    }
}

