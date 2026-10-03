package com.formacraft.common.llm.parser;

import com.formacraft.common.llm.dto.LlmPlanTestFixtures;

import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.dto.Dimensions;
import com.formacraft.common.llm.dto.GlobalConstraints;
import com.formacraft.common.llm.dto.Layout;
import com.formacraft.common.llm.dto.LlmPlan;
import com.formacraft.common.llm.dto.Slot;
import com.formacraft.common.llm.dto.Vec3i;
import com.formacraft.common.patch.BlockPatch;
import com.formacraft.server.compiler.ComponentPlanCompiler;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LlmPlanAnchorNormalizerTest {

    @Test
    void convertsAbsoluteSlotAnchorsToPlanRelative() {
        LlmPlan plan = LlmPlanTestFixtures.builder()
                .mode(LlmPlan.Mode.build)
                .styleProfile("Chinese_Traditional")
                .anchor(new Vec3i(-54, 111, 160))
                .globalConstraints(new GlobalConstraints(GlobalConstraints.Facing.SOUTH, null, null))
                .layout(new Layout(Layout.SkeletonType.COMPOUND, true, List.of(
                        new Slot("villa_1", new Vec3i(-30, 111, 160), GlobalConstraints.Facing.SOUTH, "RESIDENTIAL", null, null),
                        new Slot("villa_2", new Vec3i(0, 111, 160), GlobalConstraints.Facing.SOUTH, "RESIDENTIAL", null, null),
                        new Slot("villa_3", new Vec3i(30, 111, 160), GlobalConstraints.Facing.SOUTH, "RESIDENTIAL", null, null)
                )))
                .components(List.of(
                        new Component(
                                "MASS_MAIN",
                                "villa_1",
                                new Vec3i(0, 0, 0),
                                new Dimensions(15, 8, 12),
                                List.of(),
                                Map.of("anchor_mode", "center")
                        )
                ))
                .build();

        LlmPlan normalized = LlmPlanAnchorNormalizer.normalize(plan);

        assertEquals(new Vec3i(24, 0, 0), normalized.layout().slots().get(0).anchor());
        assertEquals(new Vec3i(54, 0, 0), normalized.layout().slots().get(1).anchor());
        assertEquals(new Vec3i(84, 0, 0), normalized.layout().slots().get(2).anchor());
    }

    @Test
    void leavesAlreadyRelativeSlotAnchorsUntouched() {
        LlmPlan plan = LlmPlanTestFixtures.builder()
                .mode(LlmPlan.Mode.build)
                .styleProfile("Chinese_Traditional")
                .anchor(new Vec3i(-54, 111, 160))
                .layout(new Layout(null, false, List.of(
                        new Slot("villa_1", new Vec3i(24, 0, 0), null, null, null, null)
                )))
                .build();


        LlmPlan normalized = LlmPlanAnchorNormalizer.normalize(plan);
        assertEquals(new Vec3i(24, 0, 0), normalized.layout().slots().get(0).anchor());
    }

    @Test
    void compiledWorldYMatchesPlanAnchorAfterNormalization() {
        LlmPlan plan = LlmPlanAnchorNormalizer.normalize(threeVillaPlanWithAbsoluteSlots());

        BlockPos origin = new BlockPos(-54, 111, 160);
        List<BlockPatch> patches = ComponentPlanCompiler.compile(plan, origin, null, null, false);
        assertFalse(patches.isEmpty());

        int minDy = patches.stream().mapToInt(BlockPatch::dy).min().orElse(Integer.MAX_VALUE);
        int maxDy = patches.stream().mapToInt(BlockPatch::dy).max().orElse(Integer.MIN_VALUE);

        assertTrue(minDy >= 0, "building base should sit on plan anchor Y, not double-count slot Y");
        assertTrue(maxDy < 32, "unexpected vertical span after anchor normalization");
        assertEquals(111, origin.getY() + minDy);
    }

    private static LlmPlan threeVillaPlanWithAbsoluteSlots() {
        return LlmPlanTestFixtures.builder()
                .mode(LlmPlan.Mode.build)
                .styleProfile("Chinese_Traditional")
                .anchor(new Vec3i(-54, 111, 160))
                .globalConstraints(new GlobalConstraints(GlobalConstraints.Facing.SOUTH, null, null))
                .layout(new Layout(Layout.SkeletonType.COMPOUND, true, List.of(
                        new Slot("villa_1", new Vec3i(-30, 111, 160), GlobalConstraints.Facing.SOUTH, "RESIDENTIAL", null, null),
                        new Slot("villa_2", new Vec3i(0, 111, 160), GlobalConstraints.Facing.SOUTH, "RESIDENTIAL", null, null),
                        new Slot("villa_3", new Vec3i(30, 111, 160), GlobalConstraints.Facing.SOUTH, "RESIDENTIAL", null, null)
                )))
                .components(List.of(
                        new Component(
                                "MASS_MAIN",
                                "villa_1",
                                new Vec3i(0, 0, 0),
                                new Dimensions(15, 8, 12),
                                List.of(),
                                Map.of("anchor_mode", "center")
                        ),
                        new Component(
                                "ROOF",
                                "villa_1",
                                new Vec3i(0, 8, 0),
                                new Dimensions(17, 4, 14),
                                List.of(),
                                Map.of("roof_type", "xuanshan")
                        )
                ))
                .build();
    }
}
