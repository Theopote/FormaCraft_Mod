package com.formacraft.server.compiler;

import com.formacraft.common.generation.component.util.GeneratedSurfaceCapture;
import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.dto.Dimensions;
import com.formacraft.common.llm.dto.Vec3i;
import com.formacraft.common.patch.BlockPatch;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class OpeningMaterialAuditTest {

    @Test
    void styleDefaultFrameBlockIsNotUserExplicit() {
        Component door = new Component(
                "ENTRANCE", "main", new Vec3i(0, 0, 0),
                new Dimensions(1, 3, 1), List.of(),
                Map.of("frame_block", "minecraft:oak_log", "entrance_type", "doorway"));
        assertFalse(OpeningMaterialAudit.isUserExplicitOpening(door));
    }

    @Test
    void doorWidthAndConstraintSourceAreUserExplicit() {
        Component byFlag = new Component(
                "ENTRANCE", "main", new Vec3i(0, 0, 0),
                new Dimensions(1, 3, 1), List.of(),
                Map.of("door_explicit", true));
        Component byWidth = new Component(
                "ENTRANCE", "main", new Vec3i(0, 0, 0),
                new Dimensions(1, 3, 1), List.of(),
                Map.of("door_width", 3));
        Component bySource = new Component(
                "ENTRANCE", "main", new Vec3i(0, 0, 0),
                new Dimensions(1, 3, 1), List.of(),
                Map.of("constraint_source", "USER_EXPLICIT", "frame_block", "minecraft:oak_log"));
        assertTrue(OpeningMaterialAudit.isUserExplicitOpening(byFlag));
        assertTrue(OpeningMaterialAudit.isUserExplicitOpening(byWidth));
        assertTrue(OpeningMaterialAudit.isUserExplicitOpening(bySource));
    }

    @Test
    void authoredWindowMaterialMismatchIsHardGap() {
        Map<BlockPos, OpeningMaterialAudit.Expectation> expectations = new LinkedHashMap<>();
        expectations.put(new BlockPos(1, 2, 3),
                new OpeningMaterialAudit.Expectation(
                        "win1", GeneratedSurfaceCapture.Role.WINDOW, "minecraft:glass", true));
        var result = OpeningMaterialAudit.inspect(expectations, List.of(
                new BlockPatch(BlockPatch.PLACE, 1, 2, 3, "minecraft:stone")
        ));
        assertEquals("mismatch", result.status());
        assertEquals(1, result.mismatchCells());
        assertEquals(1, result.hardFailCells());
        var gap = OpeningMaterialAudit.toCapabilityGap(result);
        assertTrue(gap.isPresent());
        assertEquals("E_OPENING_MATERIAL_MISMATCH", gap.get().code());
    }

    @Test
    void autofillDoorOpeningBlockedIsSoftOnly() {
        Map<BlockPos, OpeningMaterialAudit.Expectation> expectations = new LinkedHashMap<>();
        expectations.put(new BlockPos(0, 1, 0),
                new OpeningMaterialAudit.Expectation(
                        "door1", GeneratedSurfaceCapture.Role.DOOR_OPENING, null, false));
        var result = OpeningMaterialAudit.inspect(expectations, List.of(
                new BlockPatch(BlockPatch.PLACE, 0, 1, 0, "minecraft:cobblestone")
        ));
        assertTrue(OpeningMaterialAudit.toCapabilityGap(result).isEmpty());
        assertEquals(0, result.hardFailCells());
        assertEquals("door_opening_blocked", result.firstIssue().reason());
    }

    @Test
    void explicitDoorBlockedIsHardGap() {
        Map<BlockPos, OpeningMaterialAudit.Expectation> expectations = new LinkedHashMap<>();
        expectations.put(new BlockPos(0, 1, 0),
                new OpeningMaterialAudit.Expectation(
                        "door1", GeneratedSurfaceCapture.Role.DOOR_OPENING, null, true));
        var result = OpeningMaterialAudit.inspect(expectations, List.of(
                new BlockPatch(BlockPatch.PLACE, 0, 1, 0, "minecraft:cobblestone")
        ));
        var gap = OpeningMaterialAudit.toCapabilityGap(result);
        assertTrue(gap.isPresent());
        assertEquals("E_OPENING_MATERIAL_MISMATCH", gap.get().code());
    }

    @Test
    void authoredWindowUnresolvedIsHardGap() {
        Map<BlockPos, OpeningMaterialAudit.Expectation> expectations = new LinkedHashMap<>();
        expectations.put(new BlockPos(5, 5, 5),
                new OpeningMaterialAudit.Expectation(
                        "win1", GeneratedSurfaceCapture.Role.WINDOW, "minecraft:glass", true));
        var result = OpeningMaterialAudit.inspect(expectations, List.of());
        assertEquals(1, result.unresolvedCells());
        assertEquals(1, result.hardFailCells());
        var gap = OpeningMaterialAudit.toCapabilityGap(result);
        assertTrue(gap.isPresent());
        assertEquals("E_OPENING_UNRESOLVED", gap.get().code());
    }

    @Test
    void softUnresolvedDoorStaysAdvisory() {
        Map<BlockPos, OpeningMaterialAudit.Expectation> expectations = new LinkedHashMap<>();
        expectations.put(new BlockPos(5, 5, 5),
                new OpeningMaterialAudit.Expectation(
                        "door1", GeneratedSurfaceCapture.Role.DOOR_OPENING, null, false));
        var result = OpeningMaterialAudit.inspect(expectations, List.of());
        assertEquals(1, result.unresolvedCells());
        assertTrue(OpeningMaterialAudit.toCapabilityGap(result).isEmpty());
    }

    @Test
    void matchedWindowPasses() {
        Map<BlockPos, OpeningMaterialAudit.Expectation> expectations = new LinkedHashMap<>();
        expectations.put(new BlockPos(1, 2, 3),
                new OpeningMaterialAudit.Expectation(
                        "win1", GeneratedSurfaceCapture.Role.WINDOW, "minecraft:glass", true));
        var result = OpeningMaterialAudit.inspect(expectations, List.of(
                new BlockPatch(BlockPatch.PLACE, 1, 2, 3, "minecraft:glass")
        ));
        assertTrue(result.isMatched());
        assertTrue(OpeningMaterialAudit.toCapabilityGap(result).isEmpty());
    }
}
