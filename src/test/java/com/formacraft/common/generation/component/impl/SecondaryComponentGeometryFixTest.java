package com.formacraft.common.generation.component.impl;

import com.formacraft.common.compiler.semantic.SemanticComponent;
import com.formacraft.common.llm.dto.*;
import com.formacraft.common.patch.BlockPatch;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class SecondaryComponentGeometryFixTest {
    @BeforeAll
    static void bootstrap() {
        com.formacraft.test.MinecraftRegistryTestBootstrap.initialize();
    }

    @Test
    void hollowTowerWithRoofSealsTopDisk() {
        var params = new HashMap<String, Object>(Map.of(
                "hollow", true, "wall_thickness", 1, "floor_count", 1));
        var c = new Component("TOWER", null, new Vec3i(0, 0, 0), new Dimensions(9, 9, 6),
                List.of("roof", "interior"), params);
        var patches = new TowerComponentGenerator().generate(new SemanticComponent("TOWER", null, c, "DEFAULT"));
        assertTrue(patches.stream().anyMatch(p -> p.dx() == 0 && p.dy() == 5 && p.dz() == 0
                && BlockPatch.PLACE.equals(p.action())));
    }

    @Test
    void evenWidthTowerStaysInsideDeclaredFootprint() {
        var c = new Component("TOWER", null, new Vec3i(0, 0, 0), new Dimensions(8, 8, 5),
                List.of(), Map.of("hollow", false));
        var patches = new TowerComponentGenerator().generate(new SemanticComponent("TOWER", null, c, "DEFAULT"));
        // Center-relative with radius=(8-1)/2=3 → offsets in [-3,3]
        assertTrue(patches.stream().allMatch(p -> Math.abs(p.dx()) <= 3 && Math.abs(p.dz()) <= 3));
        assertFalse(patches.stream().anyMatch(p -> Math.abs(p.dx()) == 4 || Math.abs(p.dz()) == 4));
    }

    @Test
    void spiralStairsCarryFacingAndDoNotSprayFourPerLevel() {
        var params = new HashMap<String, Object>(Map.of(
                "hollow", true, "wall_thickness", 1, "floor_count", 2, "floor_height", 4));
        var c = new Component("TOWER", null, new Vec3i(0, 0, 0), new Dimensions(9, 9, 8),
                List.of("stairs", "interior"), params);
        var patches = new TowerComponentGenerator().generate(new SemanticComponent("TOWER", null, c, "DEFAULT"));
        long stairsAtY1 = patches.stream()
                .filter(p -> p.dy() == 1 && p.targetBlock() != null && p.targetBlock().contains("stairs"))
                .count();
        assertEquals(1, stairsAtY1);
        assertTrue(patches.stream().anyMatch(p -> p.targetBlock() != null
                && p.targetBlock().contains("stairs") && p.targetBlock().contains("facing=")));
    }

    @Test
    void nullFloorBlockParamDoesNotEmitLiteralNullId() {
        var params = new HashMap<String, Object>();
        params.put("hollow", true);
        params.put("floor_count", 2);
        params.put("floor_height", 4);
        params.put("wall_thickness", 1);
        params.put("floor_block", null);
        var c = new Component("TOWER", null, new Vec3i(0, 0, 0), new Dimensions(9, 9, 8), List.of(), params);
        var patches = new TowerComponentGenerator().generate(new SemanticComponent("TOWER", null, c, "DEFAULT"));
        assertFalse(patches.stream().anyMatch(p -> "null".equals(p.targetBlock())));
    }

    @Test
    void entranceAcceptsLowercaseFacing() {
        var params = new HashMap<String, Object>(Map.of("facing", "south", "door_width", 2));
        var c = new Component("ENTRANCE", null, new Vec3i(0, 0, 0), new Dimensions(5, 4, 1), List.of(), params);
        assertDoesNotThrow(() -> new EntranceGenerator().generate(new SemanticComponent("ENTRANCE", null, c, "DEFAULT")));
    }

    @Test
    void balconyLeavesWallSideOpenForEntry() {
        var params = Map.<String, Object>of("facing", "SOUTH");
        var c = new Component("BALCONY", null, new Vec3i(0, 0, 0), new Dimensions(5, 3, 2), List.of(), params);
        var patches = new BalconyGenerator().generate(new SemanticComponent("BALCONY", null, c, "DEFAULT"));
        assertFalse(patches.stream().anyMatch(p -> p.dy() == 1 && p.dz() == 0));
        assertTrue(patches.stream().anyMatch(p -> p.dy() == 1 && p.dz() == 2));
    }
}
