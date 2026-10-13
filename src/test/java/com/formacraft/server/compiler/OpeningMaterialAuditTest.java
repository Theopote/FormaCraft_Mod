package com.formacraft.server.compiler;

import com.formacraft.common.generation.component.util.GeneratedSurfaceCapture;
import com.formacraft.common.patch.BlockPatch;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class OpeningMaterialAuditTest {
    @Test
    void windowMaterialChangedProducesGap() {
        Map<BlockPos, OpeningMaterialAudit.Expectation> expectations = new LinkedHashMap<>();
        expectations.put(new BlockPos(1, 2, 3),
                new OpeningMaterialAudit.Expectation("win1", GeneratedSurfaceCapture.Role.WINDOW, "minecraft:glass"));
        var result = OpeningMaterialAudit.inspect(expectations, List.of(
                new BlockPatch(BlockPatch.PLACE, 1, 2, 3, "minecraft:stone")
        ));
        assertEquals("mismatch", result.status());
        assertEquals(1, result.mismatchCells());
        var gap = OpeningMaterialAudit.toCapabilityGap(result);
        assertTrue(gap.isPresent());
        assertEquals("E_OPENING_MATERIAL_MISMATCH", gap.get().code());
    }

    @Test
    void doorOpeningBlockedProducesGap() {
        Map<BlockPos, OpeningMaterialAudit.Expectation> expectations = new LinkedHashMap<>();
        expectations.put(new BlockPos(0, 1, 0),
                new OpeningMaterialAudit.Expectation("door1", GeneratedSurfaceCapture.Role.DOOR_OPENING, null));
        var result = OpeningMaterialAudit.inspect(expectations, List.of(
                new BlockPatch(BlockPatch.PLACE, 0, 1, 0, "minecraft:cobblestone")
        ));
        assertTrue(OpeningMaterialAudit.toCapabilityGap(result).isPresent());
        assertEquals("door_opening_blocked", result.firstIssue().reason());
    }

    @Test
    void unresolvedExpectationProducesGap() {
        Map<BlockPos, OpeningMaterialAudit.Expectation> expectations = new LinkedHashMap<>();
        expectations.put(new BlockPos(5, 5, 5),
                new OpeningMaterialAudit.Expectation("win1", GeneratedSurfaceCapture.Role.WINDOW, "minecraft:glass"));
        var result = OpeningMaterialAudit.inspect(expectations, List.of());
        assertEquals(1, result.unresolvedCells());
        var gap = OpeningMaterialAudit.toCapabilityGap(result);
        assertTrue(gap.isPresent());
        assertEquals("E_OPENING_UNRESOLVED", gap.get().code());
    }

    @Test
    void matchedWindowPasses() {
        Map<BlockPos, OpeningMaterialAudit.Expectation> expectations = new LinkedHashMap<>();
        expectations.put(new BlockPos(1, 2, 3),
                new OpeningMaterialAudit.Expectation("win1", GeneratedSurfaceCapture.Role.WINDOW, "minecraft:glass"));
        var result = OpeningMaterialAudit.inspect(expectations, List.of(
                new BlockPatch(BlockPatch.PLACE, 1, 2, 3, "minecraft:glass")
        ));
        assertTrue(result.isMatched());
        assertTrue(OpeningMaterialAudit.toCapabilityGap(result).isEmpty());
    }
}
