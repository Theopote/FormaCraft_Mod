package com.formacraft.common.generation.component.impl;

import com.formacraft.common.compiler.semantic.SemanticComponent;
import com.formacraft.common.generation.component.ComponentGenerator;
import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.dto.Dimensions;
import com.formacraft.common.llm.dto.Vec3i;
import com.formacraft.common.patch.BlockPatch;
import com.formacraft.common.typology.TypologyComponentRouter;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Parametric STRUCTURE fallback for structure_type geometry (colonnade, gear, cross, spiral, truss).
 * When a native typology_id is present, returns empty so the typology pipeline stays authoritative.
 */
public final class StructureComponentGenerator implements ComponentGenerator {

    @Override
    public List<BlockPatch> generate(SemanticComponent semantic) {
        Component c = semantic == null ? null : semantic.source();
        if (c == null || c.dimensions() == null || c.relativePosition() == null) {
            return List.of();
        }
        // Known typology carriers are owned by TypologyGenerationRouter / interpreters.
        if (TypologyComponentRouter.hasTypologyHint(c)) {
            String typology = TypologyComponentRouter.extractTypologyId(c);
            // Fake / decorative typology tokens (radial_colonnade) still need parametric emit.
            if (typology != null && isNativeTypology(typology)) {
                return List.of();
            }
        }
        String kind = resolveKind(c);
        if (kind == null || kind.isBlank()) {
            return List.of();
        }
        Dimensions d = c.dimensions();
        Vec3i rp = com.formacraft.common.generation.component.util.ComponentFootprintUtil
                .resolveMinCornerOrigin(c);
        Map<String, Object> params = c.params() != null ? c.params() : Map.of();
        String block = resolveBlock(params);
        return switch (kind) {
            case "colonnade", "column_ring", "radial_colonnade" -> colonnade(rp, d, params, block);
            case "gear", "cog", "industrial_gear" -> gear(rp, d, params, block);
            case "cross", "crucifix" -> cross(rp, d, block);
            case "spiral", "spiral_stair", "spiral_staircase" -> spiral(rp, d, block);
            case "truss", "truss_tower", "lattice" -> truss(rp, d, params, block);
            default -> List.of();
        };
    }

    private static boolean isNativeTypology(String typology) {
        String t = typology.trim().toLowerCase(Locale.ROOT);
        return switch (t) {
            case "dense_eaves_pagoda", "tibetan_stupa", "tailiang_timber_hall",
                 "radial_terrace_hall", "stadium_bowl", "suspension_bridge",
                 "gothic_cathedral_hall", "courtyard_compound", "radial_fortress",
                 "setback_tower", "tiered_mountain_palace", "truss_tower" -> true;
            default -> false;
        };
    }

    private static String resolveKind(Component c) {
        Map<String, Object> params = c.params() != null ? c.params() : Map.of();
        for (String key : List.of("structure_type", "structureType", "kind", "typology_id", "typology")) {
            Object raw = params.get(key);
            if (raw == null) continue;
            String s = String.valueOf(raw).trim().toLowerCase(Locale.ROOT);
            if (!s.isEmpty()) return normalizeKind(s);
        }
        if (c.features() != null) {
            for (String f : c.features()) {
                if (f == null) continue;
                String lower = f.toLowerCase(Locale.ROOT);
                if (lower.startsWith("typology:")) {
                    return normalizeKind(lower.substring("typology:".length()).trim());
                }
                if (lower.contains("colonnade") || lower.contains("column_ring")) return "colonnade";
                if (lower.contains("gear") || lower.contains("cog")) return "gear";
                if (lower.contains("cross") || lower.contains("crucifix")) return "cross";
                if (lower.contains("spiral")) return "spiral";
                if (lower.contains("truss") || lower.contains("x_brace") || lower.contains("lattice")) return "truss";
            }
        }
        return null;
    }

    private static String normalizeKind(String raw) {
        String s = raw.trim().toLowerCase(Locale.ROOT);
        if (s.contains("colonnade") || s.contains("column_ring") || s.equals("columns")) return "colonnade";
        if (s.contains("gear") || s.contains("cog")) return "gear";
        if (s.contains("cross") || s.contains("crucifix")) return "cross";
        if (s.contains("spiral")) return "spiral";
        if (s.contains("truss") || s.contains("lattice") || s.contains("x_brace")) return "truss";
        return s;
    }

    private static String resolveBlock(Map<String, Object> params) {
        for (String key : List.of(
                "column_block", "material_primary", "material", "block", "wall_block",
                "arch_block", "cross_material", "accent_material")) {
            Object raw = params.get(key);
            if (raw == null) continue;
            String s = String.valueOf(raw).trim().toLowerCase(Locale.ROOT);
            if (s.isEmpty()) continue;
            return s.contains(":") ? s : "minecraft:" + s;
        }
        return "minecraft:stone_bricks";
    }

    private static List<BlockPatch> colonnade(Vec3i rp, Dimensions d, Map<String, Object> params, String block) {
        List<BlockPatch> out = new ArrayList<>();
        int count = Math.max(4, intParam(params, 8, "count", "column_count"));
        int maxRadius = maxInPlaneRadius(rp, d);
        int radius = Math.max(1, Math.min(maxRadius, intParam(params, Math.max(1, maxRadius),
                "radius", "ring_radius")));
        int height = Math.max(1, Math.min(d.height(), intParam(params, d.height(), "height", "column_height")));
        int cx = rp.x() + d.width() / 2;
        int cz = rp.z() + d.depth() / 2;
        for (int i = 0; i < count; i++) {
            double ang = (2 * Math.PI * i) / count;
            int x = cx + (int) Math.round(Math.cos(ang) * radius);
            int z = cz + (int) Math.round(Math.sin(ang) * radius);
            for (int y = 0; y < height; y++) {
                placeInside(out, rp, d, x, rp.y() + y, z, block);
            }
        }
        return out;
    }

    private static List<BlockPatch> gear(Vec3i rp, Dimensions d, Map<String, Object> params, String block) {
        List<BlockPatch> out = new ArrayList<>();
        int cx = rp.x() + d.width() / 2;
        int cz = rp.z() + d.depth() / 2;
        // Leave one cell for teeth so outer+1 stays inside Dimensions.
        int maxRadius = maxInPlaneRadius(rp, d);
        int outer = Math.max(1, Math.min(maxRadius - 1, Math.min(d.width(), d.depth()) / 2));
        int inner = Math.max(1, outer - 2);
        int teeth = Math.max(6, intParam(params, 12, "count", "teeth"));
        int midY = rp.y() + Math.max(0, Math.min(d.height() - 1, d.height() / 2));
        for (int x = -outer; x <= outer; x++) {
            for (int z = -outer; z <= outer; z++) {
                int r2 = x * x + z * z;
                if (r2 <= outer * outer && r2 >= inner * inner) {
                    placeInside(out, rp, d, cx + x, midY, cz + z, block);
                }
            }
        }
        int toothR = Math.min(maxRadius, outer + 1);
        for (int i = 0; i < teeth; i++) {
            double ang = (2 * Math.PI * i) / teeth;
            int x = (int) Math.round(Math.cos(ang) * toothR);
            int z = (int) Math.round(Math.sin(ang) * toothR);
            placeInside(out, rp, d, cx + x, midY, cz + z, block);
        }
        String hub = params.get("hub_block") != null
                ? String.valueOf(params.get("hub_block"))
                : "minecraft:polished_andesite";
        if (!hub.contains(":")) hub = "minecraft:" + hub;
        placeInside(out, rp, d, cx, midY, cz, hub);
        return out;
    }

    private static List<BlockPatch> cross(Vec3i rp, Dimensions d, String block) {
        List<BlockPatch> out = new ArrayList<>();
        int cx = rp.x() + d.width() / 2;
        int cz = rp.z() + d.depth() / 2;
        int arm = Math.min(maxInPlaneRadius(rp, d), Math.max(1, Math.min(d.width(), d.depth()) / 2));
        int crossY = rp.y() + Math.min(d.height() - 1, Math.max(d.height() * 2 / 3, 2));
        for (int y = 0; y < d.height(); y++) {
            placeInside(out, rp, d, cx, rp.y() + y, cz, block);
        }
        for (int dx = -arm; dx <= arm; dx++) {
            placeInside(out, rp, d, cx + dx, crossY, cz, block);
        }
        return out;
    }

    private static List<BlockPatch> spiral(Vec3i rp, Dimensions d, String block) {
        List<BlockPatch> out = new ArrayList<>();
        int cx = rp.x() + d.width() / 2;
        int cz = rp.z() + d.depth() / 2;
        int radius = Math.max(1, Math.min(maxInPlaneRadius(rp, d), Math.min(d.width(), d.depth()) / 2 - 1));
        int steps = Math.max(d.height() * 4, 8);
        for (int i = 0; i < steps; i++) {
            double ang = (2 * Math.PI * i) / Math.max(8, radius * 4);
            int y = rp.y() + Math.min(d.height() - 1, i / 4);
            int x = cx + (int) Math.round(Math.cos(ang) * radius);
            int z = cz + (int) Math.round(Math.sin(ang) * radius);
            placeInside(out, rp, d, x, y, z, block);
        }
        return out;
    }

    /** Max axis-aligned offset from the box center that still lands inside Dimensions. */
    private static int maxInPlaneRadius(Vec3i rp, Dimensions d) {
        int cx = rp.x() + d.width() / 2;
        int cz = rp.z() + d.depth() / 2;
        int xSpan = Math.min(cx - rp.x(), rp.x() + d.width() - 1 - cx);
        int zSpan = Math.min(cz - rp.z(), rp.z() + d.depth() - 1 - cz);
        return Math.max(0, Math.min(xSpan, zSpan));
    }

    private static void placeInside(List<BlockPatch> out, Vec3i rp, Dimensions d,
                                    int x, int y, int z, String block) {
        if (x < rp.x() || x >= rp.x() + d.width()) return;
        if (y < rp.y() || y >= rp.y() + d.height()) return;
        if (z < rp.z() || z >= rp.z() + d.depth()) return;
        out.add(new BlockPatch(BlockPatch.PLACE, x, y, z, block));
    }

    private static List<BlockPatch> truss(Vec3i rp, Dimensions d, Map<String, Object> params, String block) {
        List<BlockPatch> out = new ArrayList<>();
        String secondary = params.get("material_secondary") != null
                ? String.valueOf(params.get("material_secondary")).trim().toLowerCase(Locale.ROOT)
                : block;
        if (!secondary.contains(":")) secondary = "minecraft:" + secondary;
        int w = Math.max(3, d.width());
        int depth = Math.max(3, d.depth());
        int h = Math.max(4, d.height());
        int minX = rp.x();
        int minZ = rp.z();
        int maxX = rp.x() + w - 1;
        int maxZ = rp.z() + depth - 1;
        for (int y = 0; y < h; y++) {
            double t = h <= 1 ? 1.0 : y / (double) (h - 1);
            int inset = (int) Math.round(t * Math.min(w, depth) * 0.3);
            int x0 = minX + inset;
            int x1 = maxX - inset;
            int z0 = minZ + inset;
            int z1 = maxZ - inset;
            if (x1 <= x0 || z1 <= z0) break;
            out.add(new BlockPatch(BlockPatch.PLACE, x0, rp.y() + y, z0, block));
            out.add(new BlockPatch(BlockPatch.PLACE, x1, rp.y() + y, z0, block));
            out.add(new BlockPatch(BlockPatch.PLACE, x0, rp.y() + y, z1, block));
            out.add(new BlockPatch(BlockPatch.PLACE, x1, rp.y() + y, z1, block));
            if (y % 4 == 0) {
                for (int x = x0; x <= x1; x++) {
                    out.add(new BlockPatch(BlockPatch.PLACE, x, rp.y() + y, z0, secondary));
                    out.add(new BlockPatch(BlockPatch.PLACE, x, rp.y() + y, z1, secondary));
                }
                for (int z = z0; z <= z1; z++) {
                    out.add(new BlockPatch(BlockPatch.PLACE, x0, rp.y() + y, z, secondary));
                    out.add(new BlockPatch(BlockPatch.PLACE, x1, rp.y() + y, z, secondary));
                }
            }
        }
        return out;
    }

    private static int intParam(Map<String, Object> params, int def, String... keys) {
        for (String key : keys) {
            Object raw = params.get(key);
            if (raw instanceof Number n) return n.intValue();
            if (raw == null) continue;
            try {
                return Integer.parseInt(String.valueOf(raw).trim());
            } catch (NumberFormatException ignored) {
                // continue
            }
        }
        return def;
    }
}
