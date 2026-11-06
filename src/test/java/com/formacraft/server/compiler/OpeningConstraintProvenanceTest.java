package com.formacraft.server.compiler;

import com.formacraft.common.generation.component.util.GeneratedSurfaceCapture;
import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.dto.Dimensions;
import com.formacraft.common.llm.dto.LlmPlan;
import com.formacraft.common.llm.dto.LlmPlanTestFixtures;
import com.formacraft.common.llm.dto.Vec3i;
import com.formacraft.common.patch.BlockPatch;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Batch B: request_intent → opening constraint_source provenance.
 */
class OpeningConstraintProvenanceTest {

    @Test
    void inferredDoubleDoorWidthStaysSoftAndMayBeCorrected() {
        Component door = new Component(
                "ENTRANCE", "main", new Vec3i(0, 0, 0),
                new Dimensions(2, 3, 1), List.of(),
                Map.of("door_width", 2, "frame_block", "minecraft:oak_log"));
        LlmPlan plan = RequestIntentInjector.ensure(
                LlmPlanTestFixtures.builder().components(List.of(door)).build(),
                "生成一座徽派民居");
        Component stamped = plan.components().getFirst();
        assertEquals("LLM_INFERRED", String.valueOf(stamped.params().get("constraint_source")));
        assertFalse(OpeningMaterialAudit.isUserExplicitOpening(stamped));

        Map<BlockPos, OpeningMaterialAudit.Expectation> expectations = new LinkedHashMap<>();
        OpeningMaterialAudit.capture(
                expectations,
                stamped,
                List.of(new GeneratedSurfaceCapture.Cell(
                        BlockPos.ORIGIN, GeneratedSurfaceCapture.Role.DOOR_OPENING)),
                BlockPos.ORIGIN);
        var result = OpeningMaterialAudit.inspect(expectations, List.of(
                new BlockPatch(BlockPatch.PLACE, 0, 0, 0, "minecraft:cobblestone")
        ));
        assertTrue(OpeningMaterialAudit.toCapabilityGap(result).isEmpty());
        assertEquals(0, result.hardFailCells());
    }

    @Test
    void userExplicitDoubleDoorMustRemainHard() {
        Component door = new Component(
                "ENTRANCE", "main", new Vec3i(0, 0, 0),
                new Dimensions(2, 3, 1), List.of(),
                Map.of("door_width", 2));
        LlmPlan plan = RequestIntentInjector.ensure(
                LlmPlanTestFixtures.builder().components(List.of(door)).build(),
                "生成徽派民居，入口做双开门");
        Component stamped = plan.components().getFirst();
        assertEquals("USER_EXPLICIT", String.valueOf(stamped.params().get("constraint_source")));
        assertTrue(OpeningMaterialAudit.isUserExplicitOpening(stamped));

        Map<BlockPos, OpeningMaterialAudit.Expectation> expectations = new LinkedHashMap<>();
        OpeningMaterialAudit.capture(
                expectations,
                stamped,
                List.of(new GeneratedSurfaceCapture.Cell(
                        BlockPos.ORIGIN, GeneratedSurfaceCapture.Role.DOOR_OPENING)),
                BlockPos.ORIGIN);
        var result = OpeningMaterialAudit.inspect(expectations, List.of(
                new BlockPatch(BlockPatch.PLACE, 0, 0, 0, "minecraft:cobblestone")
        ));
        assertTrue(OpeningMaterialAudit.toCapabilityGap(result).isPresent());
        assertEquals(1, result.hardFailCells());
    }

    @Test
    void userBlackStainedGlassOverwriteMustHardFail() {
        Component window = new Component(
                "FACADE_WINDOWS", "main", new Vec3i(0, 1, 0),
                new Dimensions(2, 2, 1), List.of(),
                Map.of("glass_block", "minecraft:black_stained_glass"));
        LlmPlan plan = RequestIntentInjector.ensure(
                LlmPlanTestFixtures.builder().components(List.of(window)).build(),
                "生成房屋，窗户使用黑色染色玻璃");
        Component stamped = plan.components().getFirst();
        assertTrue(OpeningMaterialAudit.isUserExplicitWindowMaterial(stamped));

        Map<BlockPos, OpeningMaterialAudit.Expectation> expectations = new LinkedHashMap<>();
        OpeningMaterialAudit.capture(
                expectations,
                stamped,
                List.of(new GeneratedSurfaceCapture.Cell(
                        new BlockPos(1, 2, 3), GeneratedSurfaceCapture.Role.WINDOW)),
                BlockPos.ORIGIN);
        var result = OpeningMaterialAudit.inspect(expectations, List.of(
                new BlockPatch(BlockPatch.PLACE, 1, 2, 3, "minecraft:glass")
        ));
        assertTrue(OpeningMaterialAudit.toCapabilityGap(result).isPresent());
        assertEquals("E_OPENING_MATERIAL_MISMATCH",
                OpeningMaterialAudit.toCapabilityGap(result).orElseThrow().code());
    }

    @Test
    void styleDefaultWindowAllowsSoftDegrade() {
        Component window = new Component(
                "FACADE_WINDOWS", "main", new Vec3i(0, 1, 0),
                new Dimensions(2, 2, 1), List.of(),
                Map.of("glass_block", "minecraft:glass", "constraint_source", "STYLE_DEFAULT"));
        assertFalse(OpeningMaterialAudit.isUserExplicitWindowMaterial(window));

        Map<BlockPos, OpeningMaterialAudit.Expectation> expectations = new LinkedHashMap<>();
        OpeningMaterialAudit.capture(
                expectations,
                window,
                List.of(new GeneratedSurfaceCapture.Cell(
                        new BlockPos(1, 2, 3), GeneratedSurfaceCapture.Role.WINDOW)),
                BlockPos.ORIGIN);
        var result = OpeningMaterialAudit.inspect(expectations, List.of(
                new BlockPatch(BlockPatch.PLACE, 1, 2, 3, "minecraft:glass_pane")
        ));
        assertTrue(OpeningMaterialAudit.toCapabilityGap(result).isEmpty());
        assertEquals(0, result.hardFailCells());
        assertEquals(1, result.mismatchCells());
    }
}
