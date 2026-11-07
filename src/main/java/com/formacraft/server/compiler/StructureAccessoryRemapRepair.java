package com.formacraft.server.compiler;

import com.formacraft.FormacraftMod;
import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.dto.Dimensions;
import com.formacraft.common.llm.dto.LlmPlan;
import com.formacraft.common.llm.dto.Vec3i;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Remap plinth / foundation pads authored as STRUCTURE → FOUNDATION so
 * {@link com.formacraft.common.generation.component.impl.FoundationGenerator} can emit solid pads.
 * <p>
 * Approach steps / 台阶 are remapped to {@code STAIR_SYSTEM} with lawful from/to endpoints and a
 * real stairs block (not a solid foundation pad). Columns / porch posts stay STRUCTURE.
 */
final class StructureAccessoryRemapRepair {
    private StructureAccessoryRemapRepair() {}

    static LlmPlan apply(LlmPlan plan) {
        if (plan == null || plan.components() == null || plan.components().isEmpty()) {
            return plan;
        }
        List<Component> out = new ArrayList<>(plan.components().size());
        boolean changed = false;
        for (Component c : plan.components()) {
            Component repaired = repair(c, plan);
            if (repaired != c) {
                changed = true;
            }
            out.add(repaired);
        }
        return changed ? PlanComponentRewrite.withComponents(plan, out) : plan;
    }

    private static Component repair(Component component, LlmPlan plan) {
        if (component == null || component.componentType() == null) {
            return component;
        }
        if (!"STRUCTURE".equalsIgnoreCase(component.componentType().trim())) {
            return component;
        }
        // Keep primary typology STRUCTURE carriers (whole-building japanese_haiden, etc.).
        if (hasTypologyMarker(component)) {
            return component;
        }
        if (looksApproachSteps(component)) {
            return remapToStair(component, plan);
        }
        if (!looksFoundationAccessory(component)) {
            return component;
        }
        FormacraftMod.LOGGER.info(
                "StructureAccessoryRemapRepair: STRUCTURE → FOUNDATION id={}",
                component.params() == null ? component.componentType() : component.params().get("component_id"));
        return new Component(
                "FOUNDATION",
                component.slotId(),
                component.relativePosition(),
                component.dimensions(),
                component.features(),
                component.params());
    }

    private static Component remapToStair(Component component, LlmPlan plan) {
        Map<String, Object> params = new HashMap<>();
        if (component.params() != null) {
            params.putAll(component.params());
        }
        params.putIfAbsent("exterior", true);
        params.put("support", false);

        Dimensions d = component.dimensions();
        int rise = d == null ? 2 : Math.max(1, d.height() <= 1 ? 1 : d.height() - 1);
        int stairWidth = d == null ? 3 : Math.max(1, Math.min(5, Math.min(d.width(), d.depth())));
        // Horizontal run must be >= rise (AssemblyCirculationOps.flightError).
        int authoredAlong = d == null ? rise + 1 : Math.max(d.width(), d.depth());
        int run = Math.max(rise, Math.max(1, authoredAlong - 1));

        String facing = resolveApproachFacing(component, plan);
        boolean alongZ = facing.equals("NORTH") || facing.equals("SOUTH");
        int width = alongZ ? stairWidth : (run + 1);
        int depth = alongZ ? (run + 1) : stairWidth;
        int midX = width / 2;
        int midZ = depth / 2;
        Map<String, Object> from;
        Map<String, Object> to;
        // Low end away from building, high end toward the entrance face.
        switch (facing) {
            case "NORTH" -> { // approach from south, ascend north
                from = Map.of("x", midX, "y", 0, "z", depth - 1);
                to = Map.of("x", midX, "y", rise, "z", depth - 1 - run);
            }
            case "EAST" -> { // approach from west, ascend east
                from = Map.of("x", 0, "y", 0, "z", midZ);
                to = Map.of("x", run, "y", rise, "z", midZ);
            }
            case "WEST" -> { // approach from east, ascend west
                from = Map.of("x", width - 1, "y", 0, "z", midZ);
                to = Map.of("x", width - 1 - run, "y", rise, "z", midZ);
            }
            default -> { // SOUTH — approach from north, ascend south
                from = Map.of("x", midX, "y", 0, "z", 0);
                to = Map.of("x", midX, "y", rise, "z", run);
            }
        }
        params.put("from", from);
        params.put("to", to);
        params.put("width", stairWidth);
        params.put("landing_length", 1);
        params.put("clearHeight", 2);
        params.put("facing", facing);

        String floor = resolveFloorBlock(params);
        // Preserve player/LLM-authored stairs blocks; only map full cubes → stairs.
        String authoredStairs = firstStairsCandidate(params);
        String stairs = authoredStairs != null ? authoredStairs : resolveStairsBlock(floor);
        params.put("floor", floor);
        params.put("stairs", stairs);
        params.putIfAbsent("material", floor);

        List<String> features = new ArrayList<>();
        if (component.features() != null) {
            features.addAll(component.features());
        }
        if (features.stream().noneMatch(f -> f != null && f.startsWith("stair:"))) {
            features.add("stair:straight_single_run");
            features.add(ascentFeature(facing));
        }

        Dimensions nextDims = new Dimensions(width, depth, rise + 1);
        Vec3i origin = component.relativePosition() == null ? new Vec3i(0, 0, 0) : component.relativePosition();
        // Snap stair high end toward the host entrance threshold when possible.
        origin = snapApproachToEntrance(origin, width, depth, rise, facing, component, plan);
        FormacraftMod.LOGGER.info(
                "StructureAccessoryRemapRepair: STRUCTURE → STAIR_SYSTEM id={} facing={} rise={} run={} stairs={}",
                params.get("component_id"), facing, rise, run, stairs);
        return new Component(
                "STAIR_SYSTEM",
                component.slotId(),
                origin,
                nextDims,
                List.copyOf(features),
                params);
    }

    /** Keep an already-valid stairs block id from params. */
    private static String firstStairsCandidate(Map<String, Object> params) {
        for (String key : List.of("stairs", "stairs_block", "stair_block", "material")) {
            Object raw = params.get(key);
            if (raw == null) continue;
            String id = String.valueOf(raw).trim();
            int props = id.indexOf('[');
            if (props >= 0) id = id.substring(0, props);
            if (!id.contains(":")) id = "minecraft:" + id;
            if (id.endsWith("_stairs")) return id;
        }
        return null;
    }

    /**
     * Place the stair volume so its high end meets the host mass entrance face at threshold Y.
     * Falls back to the authored origin when no host geometry is available.
     */
    private static Vec3i snapApproachToEntrance(
            Vec3i origin, int width, int depth, int rise, String facing,
            Component steps, LlmPlan plan) {
        if (plan == null || plan.components() == null || steps.params() == null) return origin;
        Object hostId = steps.params().get("host_id");
        if (hostId == null) hostId = steps.params().get("hostId");
        Component host = null;
        Component entrance = null;
        for (Component c : plan.components()) {
            if (c == null || c.params() == null) continue;
            if (hostId != null
                    && String.valueOf(hostId).equals(String.valueOf(c.params().get("component_id")))) {
                host = c;
            }
            String type = c.componentType() == null ? "" : c.componentType().toUpperCase(Locale.ROOT);
            if (type.contains("ENTRANCE") || type.contains("DOOR")) {
                Object eh = c.params().get("host_id");
                if (eh == null) eh = c.params().get("hostId");
                if (hostId != null && String.valueOf(hostId).equals(String.valueOf(eh))) {
                    entrance = c;
                }
            }
        }
        if (host == null || host.relativePosition() == null || host.dimensions() == null) {
            return origin;
        }
        Vec3i hr = host.relativePosition();
        Dimensions hd = host.dimensions();
        int thresholdY = hr.y();
        int highX;
        int highZ;
        if (entrance != null && entrance.relativePosition() != null) {
            highX = entrance.relativePosition().x();
            highZ = entrance.relativePosition().z();
            thresholdY = entrance.relativePosition().y();
        } else {
            // Midpoint of the entrance face of the host AABB.
            highX = hr.x() + hd.width() / 2;
            highZ = hr.z() + hd.depth() / 2;
            switch (facing) {
                case "NORTH" -> highZ = hr.z();
                case "SOUTH" -> highZ = hr.z() + hd.depth() - 1;
                case "EAST" -> highX = hr.x() + hd.width() - 1;
                case "WEST" -> highX = hr.x();
                default -> highZ = hr.z() + hd.depth() - 1;
            }
        }
        // Local high-end cell inside the stair AABB, then back-solve world origin.
        int localHighX = width / 2;
        int localHighZ = switch (facing) {
            case "NORTH" -> 0;
            case "SOUTH" -> depth - 1;
            default -> depth / 2;
        };
        int localHighXAdj = switch (facing) {
            case "EAST" -> width - 1;
            case "WEST" -> 0;
            default -> localHighX;
        };
        int ox = highX - localHighXAdj;
        int oz = highZ - localHighZ;
        int oy = thresholdY - rise;
        return new Vec3i(ox, oy, oz);
    }

    private static String ascentFeature(String facing) {
        return switch (facing) {
            case "NORTH" -> "ascends_from_south_low_end_to_north_high_end";
            case "EAST" -> "ascends_from_west_low_end_to_east_high_end";
            case "WEST" -> "ascends_from_east_low_end_to_west_high_end";
            default -> "ascends_from_north_low_end_to_south_high_end";
        };
    }

    /** Entrance facing: stairs climb toward the building face. */
    private static String resolveApproachFacing(Component steps, LlmPlan plan) {
        if (steps.params() != null) {
            Object f = steps.params().get("facing");
            if (f == null) f = steps.params().get("approach_facing");
            String parsed = parseFacing(f);
            if (parsed != null) return parsed;
        }
        Object hostId = steps.params() == null ? null : steps.params().get("host_id");
        if (hostId == null && steps.params() != null) hostId = steps.params().get("hostId");
        if (plan != null && plan.components() != null && hostId != null) {
            for (Component c : plan.components()) {
                if (c == null || c.params() == null) continue;
                if (!hostId.equals(c.params().get("component_id"))) continue;
                String parsed = parseFacing(c.params().get("facing"));
                if (parsed != null) return parsed;
            }
        }
        if (plan != null && plan.globalConstraints() != null && plan.globalConstraints().facing() != null) {
            return plan.globalConstraints().facing().name();
        }
        return "SOUTH";
    }

    private static String parseFacing(Object raw) {
        if (raw == null) return null;
        String s = String.valueOf(raw).trim().toUpperCase(Locale.ROOT);
        return switch (s) {
            case "NORTH", "SOUTH", "EAST", "WEST" -> s;
            case "N" -> "NORTH";
            case "S" -> "SOUTH";
            case "E" -> "EAST";
            case "W" -> "WEST";
            default -> null;
        };
    }

    static String resolveFloorBlock(Map<String, Object> params) {
        Object raw = params.get("material");
        if (raw == null) raw = params.get("floor");
        if (raw == null) raw = params.get("block");
        String floor = raw == null ? "minecraft:stone_bricks" : String.valueOf(raw).trim();
        if (!floor.contains(":")) floor = "minecraft:" + floor;
        int props = floor.indexOf('[');
        if (props >= 0) floor = floor.substring(0, props);
        return floor;
    }

    /** Map full blocks to a StairsBlock id (stone_bricks → stone_brick_stairs). */
    static String resolveStairsBlock(String floorBlock) {
        if (floorBlock == null || floorBlock.isBlank()) {
            return "minecraft:stone_brick_stairs";
        }
        String id = floorBlock;
        int props = id.indexOf('[');
        if (props >= 0) id = id.substring(0, props);
        if (id.endsWith("_stairs")) return id;
        if (id.endsWith("_planks")) {
            return id.substring(0, id.length() - "_planks".length()) + "_stairs";
        }
        if (id.endsWith("_bricks")) {
            return id.substring(0, id.length() - "_bricks".length()) + "_brick_stairs";
        }
        if (id.endsWith("_tiles")) {
            return id.substring(0, id.length() - "_tiles".length()) + "_tile_stairs";
        }
        if (id.equals("minecraft:stone") || id.endsWith(":stone")) {
            return "minecraft:stone_stairs";
        }
        if (id.endsWith("_block")) {
            String stem = id.substring(0, id.length() - "_block".length());
            return stem + "_stairs";
        }
        return "minecraft:stone_brick_stairs";
    }

    private static boolean hasTypologyMarker(Component c) {
        if (c.params() != null) {
            for (String key : List.of("typology_id", "typology", "structural_typology")) {
                Object v = c.params().get(key);
                if (v != null && !String.valueOf(v).isBlank()) {
                    return true;
                }
            }
        }
        if (c.features() != null) {
            for (String f : c.features()) {
                if (f != null && f.toLowerCase(Locale.ROOT).startsWith("typology:")) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Entrance steps / 台阶 — never treat as a solid foundation pad. */
    private static boolean looksApproachSteps(Component c) {
        String id = identity(c);
        String lower = id.toLowerCase(Locale.ROOT);
        if (lower.contains("steps") || lower.contains("step") || id.contains("台阶")
                || lower.contains("stair") || id.contains("踏步")) {
            if (lower.contains("plinth") && !lower.contains("step") && !id.contains("台阶")) {
                return false;
            }
            return true;
        }
        if (c.features() != null) {
            for (String f : c.features()) {
                if (f == null) continue;
                String fl = f.toLowerCase(Locale.ROOT);
                if (fl.contains("steps") || f.contains("台阶") || fl.contains("stair")
                        || fl.startsWith("stair:")) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean looksFoundationAccessory(Component c) {
        if (looksApproachSteps(c)) {
            return false;
        }
        String id = identity(c);
        String lower = id.toLowerCase(Locale.ROOT);
        if (lower.contains("plinth") || lower.contains("foundation") || id.contains("台基")
                || (lower.contains("base") && (lower.contains("stone") || lower.contains("platform")))) {
            return true;
        }
        if (c.features() != null) {
            for (String f : c.features()) {
                if (f == null) continue;
                String fl = f.toLowerCase(Locale.ROOT);
                if (fl.contains("plinth") || f.contains("台基") || fl.contains("foundation")) {
                    return true;
                }
            }
        }
        return false;
    }

    private static String identity(Component c) {
        if (c.params() != null) {
            Object raw = c.params().get("component_id");
            if (raw == null) raw = c.params().get("componentId");
            if (raw != null) return String.valueOf(raw);
        }
        return "";
    }
}
