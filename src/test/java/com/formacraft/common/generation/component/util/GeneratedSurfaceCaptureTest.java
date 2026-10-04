package com.formacraft.common.generation.component.util;

import com.formacraft.common.compiler.semantic.SemanticComponent;
import com.formacraft.common.generation.component.impl.MassMainGenerator;
import com.formacraft.common.generation.component.impl.RoofGenerator;
import com.formacraft.common.llm.dto.*;
import com.formacraft.common.patch.BlockPatch;
import com.formacraft.test.MinecraftRegistryTestBootstrap;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class GeneratedSurfaceCaptureTest {
    @Test void circleDecisionsFollowActualCurvedWallAndDoorOpenings() {
        MinecraftRegistryTestBootstrap.initialize();
        var c = new Component("MASS_MAIN", null, new Vec3i(0,0,0), new Dimensions(9,9,8),
                List.of("hollow", "door", "windows"), Map.of("anchor_mode","min_corner", "shape","circle", "hollow",true));
        var cells = new ArrayList<GeneratedSurfaceCapture.Cell>();
        List<BlockPatch> patches;
        try (var scope = GeneratedSurfaceCapture.captureTo(cells::add)) {
            patches = new MassMainGenerator().generate(new SemanticComponent("MASS_MAIN", null, c));
        }
        assertTrue(GeneratedSurfaceCapture.missing(cells, patches).isEmpty());
        assertTrue(cells.stream().anyMatch(cell -> cell.role() == GeneratedSurfaceCapture.Role.WALL
                && cell.position().getX() > 0 && cell.position().getX() < 8
                && cell.position().getZ() > 0 && cell.position().getZ() < 8));
        assertTrue(cells.stream().anyMatch(cell -> cell.role() == GeneratedSurfaceCapture.Role.DOOR_OPENING));
        var wall = cells.stream().filter(GeneratedSurfaceCapture.Cell::requiresBlock).findFirst().orElseThrow();
        var removed = new ArrayList<>(patches);
        removed.add(new BlockPatch(BlockPatch.REMOVE, wall.position().getX(), wall.position().getY(), wall.position().getZ(), "minecraft:air"));
        assertTrue(GeneratedSurfaceCapture.missing(cells, removed).stream().anyMatch(cell -> cell.position().equals(wall.position())));
    }
    @Test void roofCaptureIncludesOverhangOutsideHostFootprint() {
        MinecraftRegistryTestBootstrap.initialize();
        var c = new Component("ROOF", null, new Vec3i(0,7,0), new Dimensions(6,6,1), List.of(),
                Map.of("anchor_mode","min_corner","roof_type","flat","overhang",2));
        var cells = new ArrayList<GeneratedSurfaceCapture.Cell>();
        List<BlockPatch> patches;
        try (var scope = GeneratedSurfaceCapture.captureTo(cells::add)) {
            patches = new RoofGenerator().generate(new SemanticComponent("ROOF", null, c));
        }
        assertFalse(cells.isEmpty());
        assertTrue(cells.stream().anyMatch(cell -> cell.position().getX() < 0));
        assertTrue(GeneratedSurfaceCapture.missing(cells, patches).isEmpty());
    }
    @Test void nestedCaptureRestoresPreviousSinkWithoutLeakingAndHonorsOpeningDecision() {
        var outer = new ArrayList<GeneratedSurfaceCapture.Cell>();
        var inner = new ArrayList<GeneratedSurfaceCapture.Cell>();
        try (var scope = GeneratedSurfaceCapture.captureTo(outer::add)) {
            GeneratedSurfaceCapture.record(0,1,0, GeneratedSurfaceCapture.Role.WALL);
            try (var nested = GeneratedSurfaceCapture.captureTo(inner::add)) {
                GeneratedSurfaceCapture.record(1,1,0, GeneratedSurfaceCapture.Role.ROOF);
            }
            GeneratedSurfaceCapture.record(0,1,0, GeneratedSurfaceCapture.Role.FACADE_OPENING);
        }
        GeneratedSurfaceCapture.record(2,1,0, GeneratedSurfaceCapture.Role.WALL);
        assertEquals(2, outer.size()); assertEquals(1, inner.size());
        assertTrue(GeneratedSurfaceCapture.missing(outer, List.of()).isEmpty());
        assertEquals(new BlockPos(11,3,20), inner.getFirst().shift(new BlockPos(10,2,20)).position());
        assertEquals(1, GeneratedSurfaceCapture.missing(inner, List.of()).size());
    }
}
