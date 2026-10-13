package com.formacraft.server.compiler;

import com.formacraft.common.generation.component.util.GeneratedSurfaceCapture;
import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.dto.Vec3i;
import com.formacraft.common.patch.BlockPatch;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SurfaceOwnershipLedgerTest {
    @Test
    void decorCannotReplaceWindowSurface() {
        var ledger = new SurfaceOwnershipLedger();
        var mass = component("MASS_MAIN", "mass1", Map.of("component_id", "mass1", "host_id", "b1"));
        ledger.accept(mass,
                List.of(new BlockPatch(BlockPatch.PLACE, 0, 1, 0, "minecraft:glass")),
                List.of(new GeneratedSurfaceCapture.Cell(new BlockPos(0, 1, 0), GeneratedSurfaceCapture.Role.WINDOW)),
                BlockPos.ORIGIN);

        var decor = component("DECOR_DETAIL", "decor1", Map.of("component_id", "decor1", "host_id", "b1"));
        var conflict = ledger.check(decor, List.of(
                new BlockPatch(BlockPatch.PLACE, 0, 1, 0, "minecraft:stone_bricks")));
        assertTrue(conflict.isPresent());
        assertTrue(conflict.get().reason().contains("WINDOW"));
    }

    @Test
    void facadeWindowsCanInstallGlassOnWall() {
        var ledger = new SurfaceOwnershipLedger();
        var mass = component("MASS_MAIN", "mass1", Map.of("component_id", "mass1", "host_id", "b1"));
        ledger.accept(mass,
                List.of(new BlockPatch(BlockPatch.PLACE, 0, 1, 0, "minecraft:stone")),
                List.of(new GeneratedSurfaceCapture.Cell(new BlockPos(0, 1, 0), GeneratedSurfaceCapture.Role.WALL)),
                BlockPos.ORIGIN);

        var windows = component("FACADE_WINDOWS", "win1", Map.of("component_id", "win1", "host_id", "b1"));
        var conflict = ledger.check(windows, List.of(
                new BlockPatch(BlockPatch.PLACE, 0, 1, 0, "minecraft:glass")));
        assertTrue(conflict.isEmpty());
    }

    @Test
    void decorCannotEraseOccupiedSurface() {
        var ledger = new SurfaceOwnershipLedger();
        var mass = component("MASS_MAIN", "mass1", Map.of("component_id", "mass1"));
        ledger.accept(mass,
                List.of(new BlockPatch(BlockPatch.PLACE, 0, 1, 0, "minecraft:stone")),
                List.of(new GeneratedSurfaceCapture.Cell(new BlockPos(0, 1, 0), GeneratedSurfaceCapture.Role.WALL)),
                BlockPos.ORIGIN);

        var decor = component("DECOR_DETAIL", "decor1", Map.of("component_id", "decor1"));
        var conflict = ledger.check(decor, List.of(
                new BlockPatch(BlockPatch.REMOVE, 0, 1, 0, "minecraft:air")));
        assertTrue(conflict.isPresent());
    }

    private static Component component(String type, String slot, Map<String, Object> params) {
        return new Component(type, slot, new Vec3i(0, 0, 0), null, null, params);
    }
}
