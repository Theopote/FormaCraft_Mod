package com.formacraft.common.generation.component.impl;

import com.formacraft.common.compiler.semantic.SemanticComponent;
import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.dto.Dimensions;
import com.formacraft.common.llm.dto.GlobalConstraints;
import com.formacraft.common.llm.dto.Slot;
import com.formacraft.common.llm.dto.Vec3i;
import com.formacraft.common.patch.BlockPatch;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class EntranceGeometryContractTest {
    @org.junit.jupiter.api.BeforeAll
    static void bootstrap() {
        com.formacraft.test.MinecraftRegistryTestBootstrap.initialize();
    }

    private SemanticComponent entrance(int width, int height, int depth, Map<String, Object> params, List<String> features) {
        var c = new Component("ENTRANCE", "house", new Vec3i(0, 0, 0),
                new Dimensions(width, depth, height), features, params);
        var slot = new Slot("house", new Vec3i(0, 0, 0), GlobalConstraints.Facing.SOUTH, null, null, null);
        return new SemanticComponent("ENTRANCE", slot, c, "DEFAULT");
    }

    @Test
    void plainWallCellsAreNotReplacedByDefault() {
        var params = new HashMap<String, Object>();
        params.put("door_width", 2);
        params.put("door_height", 3);
        // depth=1 facade strip: 7*5*1=35 cells; only opening + jamb/lintel emit patches.
        var patches = new EntranceGenerator().generate(entrance(7, 5, 1, params, List.of()));
        var places = patches.stream().filter(p -> BlockPatch.PLACE.equals(p.action())).toList();
        var removes = patches.stream().filter(p -> BlockPatch.REMOVE.equals(p.action())).toList();
        assertFalse(removes.isEmpty());
        assertEquals(6, removes.size(), "2-wide door through height 3");
        assertTrue(places.size() < 15, "only jamb/lintel places, got " + places.size());
        assertTrue(places.size() + removes.size() < 25);
        Set<String> placeKeys = places.stream()
                .map(p -> p.dx() + "," + p.dy() + "," + p.dz())
                .collect(Collectors.toSet());
        // Far corner away from door bay must not be rewritten.
        assertFalse(placeKeys.contains("0,0,0"));
        assertFalse(placeKeys.contains("0,4,0"));
        assertFalse(placeKeys.contains("6,2,0"));
    }

    @Test
    void explicitDoorWidthTooWideFails() {
        var params = Map.<String, Object>of("door_width", 3, "door_height", 3);
        var ex = assertThrows(IllegalArgumentException.class,
                () -> new EntranceGenerator().generate(entrance(3, 4, 2, params, List.of())));
        assertTrue(ex.getMessage().startsWith("E_ENTRANCE_DOOR_WIDTH"));
    }

    @Test
    void explicitArchWidthOneFails() {
        var params = Map.<String, Object>of("door_width", 1, "door_height", 3);
        var ex = assertThrows(IllegalArgumentException.class,
                () -> new EntranceGenerator().generate(entrance(7, 4, 2, params, List.of("arch"))));
        assertTrue(ex.getMessage().startsWith("E_ENTRANCE_DOOR_WIDTH"));
        assertTrue(ex.getMessage().contains("arched"));
    }

    @Test
    void replaceWallRegionStillAllowsFullEnvelope() {
        var params = new HashMap<String, Object>();
        params.put("door_width", 2);
        params.put("door_height", 3);
        params.put("replace_wall_region", true);
        var patches = new EntranceGenerator().generate(entrance(5, 4, 2, params, List.of()));
        assertTrue(patches.stream().filter(p -> BlockPatch.PLACE.equals(p.action())).count() > 10);
    }
}
