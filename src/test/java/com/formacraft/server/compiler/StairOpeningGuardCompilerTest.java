package com.formacraft.server.compiler;

import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.dto.Dimensions;
import com.formacraft.common.llm.dto.Vec3i;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class StairOpeningGuardCompilerTest {

    @Test
    void authoredPrimitiveStairGuardBoxIsNotRewrittenToFence() {
        var parts = new ArrayList<Component>();
        parts.add(stair("desert_adobe", "main", 5, 0, 3, Map.of("x", 1, "y", 0, "z", 0), Map.of("x", 1, "y", 4, "z", 4)));
        var guardParams = new HashMap<String, Object>();
        guardParams.put("component_id", "earth_opening_stair_guard_0");
        guardParams.put("host_id", "desert_adobe");
        guardParams.put("kind", "box");
        guardParams.put("material", "minecraft:packed_mud");
        parts.add(new Component("PRIMITIVE", "main", new Vec3i(4, 5, 4), new Dimensions(1, 6, 2),
                List.of(), guardParams));

        StairOpeningGuardCompiler.apply(parts);

        assertEquals(1, parts.stream().filter(c -> "PRIMITIVE".equalsIgnoreCase(c.componentType())).count());
        assertTrue(parts.stream().anyMatch(c ->
                c.params() != null && "earth_opening_stair_guard_0".equals(c.params().get("component_id"))
                        && "minecraft:packed_mud".equals(c.params().get("material"))));
        assertTrue(parts.stream().noneMatch(c ->
                c.params() != null && "compiled_stair_guard".equals(c.params().get("placement"))));
    }

    @Test
    void entranceWithPlanRailingTokenInFeaturesIsNotConsumedAsRailSeed() {
        var parts = new ArrayList<Component>();
        parts.add(stair("mass_adobe_main", "adobe_house", 8, 0, 4,
                Map.of("x", 1, "y", 0, "z", 5), Map.of("x", 1, "y", 4, "z", 0)));
        var entranceParams = new HashMap<String, Object>();
        entranceParams.put("component_id", "entrance_central_adobe");
        entranceParams.put("host_id", "mass_adobe_main");
        parts.add(new Component("ENTRANCE", "adobe_house", new Vec3i(5, 0, 12), new Dimensions(3, 1, 3),
                List.of("entrance", "second_floor_stair_opening_railing", "door:central"), entranceParams));
        var railParams = new HashMap<String, Object>();
        railParams.put("component_id", "railing_stair_opening");
        railParams.put("host_id", "mass_adobe_main");
        railParams.put("placement", "stair_opening");
        railParams.put("railing", true);
        railParams.put("material", "minecraft:dark_oak_fence");
        parts.add(new Component("DECOR_DETAIL", "adobe_house", new Vec3i(8, 5, 3), new Dimensions(3, 1, 1),
                List.of("railing:stair_opening"), railParams));

        StairOpeningGuardCompiler.apply(parts);

        assertTrue(parts.stream().anyMatch(c -> "ENTRANCE".equalsIgnoreCase(c.componentType())
                && "entrance_central_adobe".equals(c.params().get("component_id"))));
        assertTrue(parts.stream().anyMatch(c ->
                c.params() != null
                        && "compiled_stair_guard".equals(c.params().get("placement"))
                        && "minecraft:dark_oak_fence".equals(c.params().get("material"))));
        assertTrue(parts.stream().noneMatch(c ->
                c.params() != null
                        && String.valueOf(c.params().get("component_id")).startsWith("entrance_central_adobe_")));
    }

    @Test
    void guardTowerPortalEntranceIsNotConsumedAsRailSeed() {
        var parts = new ArrayList<Component>();
        parts.add(stair("guard_tower_0", "main", 0, 1, 0, Map.of("x", 2, "y", 0, "z", 1), Map.of("x", 2, "y", 8, "z", 8)));
        parts.add(entrance("guard_tower_portal_0", "guard_tower_0", "main",
                new Vec3i(-1, 1, 4), new Dimensions(2, 5, 4)));

        StairOpeningGuardCompiler.apply(parts);

        assertEquals(1, parts.stream().filter(c -> "ENTRANCE".equalsIgnoreCase(c.componentType())).count());
        assertTrue(parts.stream().anyMatch(c ->
                c.params() != null && "guard_tower_portal_0".equals(c.params().get("component_id"))));
        assertTrue(parts.stream().noneMatch(c ->
                c.params() != null && String.valueOf(c.params().get("component_id")).contains("compiled_stair_guard")));
    }

    @Test
    void explicitRailingSeedStillExpandsBesideInteriorFlight() {
        var parts = new ArrayList<Component>();
        parts.add(stair("adobe_house", "main", 2, 1, 2, Map.of("x", 2, "y", 0, "z", 1), Map.of("x", 2, "y", 4, "z", 5)));
        var railParams = new HashMap<String, Object>();
        railParams.put("component_id", "adobe_house_stair_rail_side");
        railParams.put("host_id", "adobe_house");
        railParams.put("role", "railing");
        railParams.put("material", "wood");
        parts.add(new Component("DECOR_DETAIL", "main", new Vec3i(0, 5, 2), new Dimensions(1, 6, 1),
                List.of("railing", "stair_opening_guard"), railParams));

        StairOpeningGuardCompiler.apply(parts);

        assertTrue(parts.stream().noneMatch(c ->
                c.params() != null && "adobe_house_stair_rail_side".equals(c.params().get("component_id"))));
        assertTrue(parts.stream().anyMatch(c ->
                c.params() != null
                        && "compiled_stair_guard".equals(c.params().get("placement"))
                        && "DECOR_DETAIL".equalsIgnoreCase(c.componentType())));
    }

    private static Component stair(String hostId, String slot, int x, int y, int z,
                                   Map<String, Object> from, Map<String, Object> to) {
        var params = new HashMap<String, Object>();
        params.put("component_id", hostId + "_stair");
        params.put("host_id", hostId);
        params.put("from", from);
        params.put("to", to);
        params.put("width", 3);
        return new Component("STAIR_SYSTEM", slot, new Vec3i(x, y, z), new Dimensions(3, 8, 9), List.of(), params);
    }

    private static Component entrance(String id, String hostId, String slot, Vec3i pos, Dimensions dims) {
        var params = new HashMap<String, Object>();
        params.put("component_id", id);
        params.put("host_id", hostId);
        params.put("facing", "WEST");
        params.put("door_width", 3);
        params.put("door_height", 3);
        return new Component("ENTRANCE", slot, pos, dims, List.of("entrance"), params);
    }
}
