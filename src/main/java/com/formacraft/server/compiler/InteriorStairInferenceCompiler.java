package com.formacraft.server.compiler;

import com.formacraft.FormacraftMod;
import com.formacraft.common.generation.component.util.ComponentFootprintUtil;
import com.formacraft.common.generation.component.util.ComponentParamParsers;
import com.formacraft.common.generation.component.util.MassComponentKinds;
import com.formacraft.common.generation.component.util.ResolvedFloorLayout;
import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.dto.Dimensions;
import com.formacraft.common.llm.dto.Vec3i;
import com.formacraft.server.generation.component.impl.StraightStairComponentGenerator;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * When a multi-storey circulation mass has no interior stair components, emit a
 * switchback STAIR_SYSTEM chain so upper floors remain reachable.
 */
final class InteriorStairInferenceCompiler {
    private InteriorStairInferenceCompiler() {}

    static void apply(List<Component> parts) {
        if (parts == null || parts.isEmpty()) {
            return;
        }
        List<Component> extras = new ArrayList<>();
        for (Component host : List.copyOf(parts)) {
            if (!needsStairs(host, parts)) {
                continue;
            }
            List<Component> flights = buildFlights(host);
            if (!flights.isEmpty()) {
                extras.addAll(flights);
                FormacraftMod.LOGGER.info(
                        "InteriorStairInferenceCompiler: inferred {} stair flight(s) for host_id={}",
                        flights.size(),
                        host.params().get("component_id"));
            }
        }
        parts.addAll(extras);
    }

    private static boolean needsStairs(Component host, List<Component> parts) {
        if (!MassComponentKinds.isStoreyedCirculationMass(host) || host.params() == null) {
            return false;
        }
        Object id = host.params().get("component_id");
        if (id == null) {
            return false;
        }
        // Typology / landmark shells own their own vertical circulation.
        if (host.features() != null && host.features().stream().filter(Objects::nonNull).anyMatch(f ->
                f.startsWith("typology:") || f.startsWith("landmark:"))) {
            return false;
        }
        for (Component c : parts) {
            if (c == null || c.params() == null || Boolean.TRUE.equals(c.params().get("exterior"))) {
                continue;
            }
            if (!StraightStairComponentGenerator.accepts(c)) {
                continue;
            }
            if (id.equals(c.params().get("host_id"))) {
                return false;
            }
        }
        return true;
    }

    private static List<Component> buildFlights(Component host) {
        Vec3i origin = ComponentFootprintUtil.resolveMinCornerOrigin(host);
        Dimensions dims = host.dimensions();
        if (origin == null || dims == null) {
            return List.of();
        }
        int wall = Math.max(1, ComponentParamParsers.intParam(host.params(), 1, "wall_thickness", "wallThickness"));
        int clearSpan = Math.min(dims.width(), dims.depth()) - 2 * wall;
        // Narrow shafts clog with width-3 flights — default to a single-block stair.
        int width = clearSpan <= 5 || Math.min(dims.width(), dims.depth()) <= 9 ? 1 : 3;
        ResolvedFloorLayout layout = ResolvedFloorLayout.from(host, dims.height());
        int fh = layout.bandHeight();
        List<Integer> floorYs = layout.floorYs();
        if (floorYs.size() < 2 || fh < 2) {
            return List.of();
        }
        boolean alongZ = dims.depth() >= dims.width();
        int span = alongZ ? dims.width() : dims.depth();
        int run = alongZ ? dims.depth() : dims.width();
        int interiorRun = run - 2 * wall;
        int laneA = wall + width / 2;
        int laneB = span - wall - 1 - (width - 1) / 2;
        if (laneB - laneA < width || interiorRun < fh) {
            // Narrow tower: single-lane flights without switchback.
            width = Math.max(1, Math.min(width, Math.max(1, span - 2 * wall)));
            laneA = laneB = wall + width / 2;
            if (span - 2 * wall < 1 || interiorRun < fh) {
                return List.of();
            }
        }
        int low = wall;
        int high = run - wall - 1;
        if (high - low < fh) {
            return List.of();
        }
        String hostId = String.valueOf(host.params().get("component_id"));
        String material = resolveFloorMaterial(host);
        String stairs = resolveStairsMaterial(material);
        List<Component> out = new ArrayList<>();
        boolean switchback = laneA != laneB && high - low >= fh;
        // Keep the uppermost tread below the roof plane so inferred stairs never overwrite ROOF.
        int maxTreadY = Math.max(1, dims.height() - 2);
        for (int i = 0; i < floorYs.size() - 1; i++) {
            int fromY = floorYs.get(i);
            int toY = floorYs.get(i + 1);
            if (toY <= fromY || toY > maxTreadY) {
                continue;
            }
            int rise = toY - fromY;
            if (rise > high - low) {
                // Straight stair requires rise <= horizontal run.
                continue;
            }
            boolean even = i % 2 == 0;
            int start = (!switchback || even) ? low : high;
            int end = (!switchback || even) ? low + rise : high - rise;
            if (!switchback) {
                start = low;
                end = low + rise;
            }
            int lane = (!switchback || even) ? laneA : laneB;
            Map<String, Object> p = new HashMap<>();
            p.put("host_id", hostId);
            p.put("component_id", hostId + "#inferred_stair_" + i);
            p.put("from", coords(alongZ, lane, fromY, start));
            p.put("to", coords(alongZ, lane, toY, end));
            p.put("width", width);
            p.put("landing_length", 1);
            p.put("anchor_mode", "min_corner");
            p.put("support", false);
            p.put("floor", material);
            p.put("stairs", stairs);
            p.put("compiler_inferred_stair", true);
            out.add(new Component("STAIR_SYSTEM", host.slotId(), origin, dims, List.of(), p));
            if (switchback && i + 1 < floorYs.size() - 1) {
                // Landing plate between reverse flights at the turn Y.
                Map<String, Object> turn = new HashMap<>(p);
                turn.put("component_id", hostId + "#inferred_stair_" + i + "_turn");
                turn.put("width", 1);
                int gapLow = Math.min(laneA, laneB) + (width - 1) / 2 + 1;
                int gapHigh = Math.max(laneA, laneB) - width / 2 - 1;
                if (gapHigh >= gapLow) {
                    turn.put("from", coords(alongZ, even ? gapLow : gapHigh, toY, end));
                    turn.put("to", coords(alongZ, even ? gapHigh : gapLow, toY, end));
                    out.add(new Component("STAIR_SYSTEM", host.slotId(), origin, dims, List.of(), turn));
                }
            }
        }
        return out;
    }

    private static Map<String, Integer> coords(boolean alongZ, int lane, int y, int run) {
        return Map.of("x", alongZ ? lane : run, "y", y, "z", alongZ ? run : lane);
    }

    private static String resolveFloorMaterial(Component host) {
        String material = ComponentPlanTypes.getParamString(host.params(),
                "floor_block", "floor", "material", "wall_block", "primary_block");
        if (material == null || material.isBlank()) {
            return "minecraft:stone_bricks";
        }
        if (!material.contains(":")) {
            material = "minecraft:" + material;
        }
        return material;
    }

    private static String resolveStairsMaterial(String floorBlock) {
        if (floorBlock == null || floorBlock.isBlank()) {
            return "minecraft:stone_brick_stairs";
        }
        String id = floorBlock;
        int props = id.indexOf('[');
        if (props >= 0) {
            id = id.substring(0, props);
        }
        if (id.endsWith("_stairs")) {
            return id;
        }
        if (id.endsWith("_planks")) {
            return id.substring(0, id.length() - "_planks".length()) + "_stairs";
        }
        if (id.endsWith("_bricks")) {
            // stone_bricks → stone_brick_stairs (singular brick)
            String base = id.substring(0, id.length() - "_bricks".length());
            return base + "_brick_stairs";
        }
        if (id.equals("minecraft:stone") || id.endsWith(":stone")) {
            return "minecraft:stone_brick_stairs";
        }
        return "minecraft:stone_brick_stairs";
    }
}
