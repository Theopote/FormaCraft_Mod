package com.formacraft.server.generation.typology.builder;

import com.formacraft.common.build.GeneratedStructure;
import com.formacraft.common.build.PlannedBlock;
import com.formacraft.common.typology.TypologyParamResolver;
import com.formacraft.common.typology.TypologyParams;
import com.formacraft.test.MinecraftRegistryTestBootstrap;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DenseEavesPagodaLevelsTest {

    @BeforeAll
    static void boot() {
        MinecraftRegistryTestBootstrap.initialize();
    }

    @Test
    void explicitThreeLevelsNotClampedToSeven() {
        TypologyParams p = new TypologyParams(Map.of("levels", 3));
        assertEquals(3, PagodaVerticalSemantics.resolve(p, 13, 7, 15).floorCount());
    }

    @Test
    void floorCountThreeHonored() {
        TypologyParams p = new TypologyParams(Map.of("floor_count", 3));
        assertEquals(3, PagodaVerticalSemantics.resolve(p, 13, 7, 15).floorCount());
    }

    @Test
    void threeStoreyChongyanNotInflatedToSevenPlus() {
        TypologyParams p = new TypologyParams(Map.of(
                "floor_count", 3,
                "eaves_per_level", 2
        ));
        PagodaVerticalSemantics v = PagodaVerticalSemantics.resolve(p, 13, 7, 15);
        assertEquals(3, v.floorCount());
        assertEquals(6, v.eaveTiers());
    }

    @Test
    void implicitDefaultKeepsTallMin() {
        TypologyParams p = new TypologyParams(Map.of());
        assertEquals(13, PagodaVerticalSemantics.resolve(p, 13, 7, 15).floorCount());
    }

    @Test
    void octagonVerticalSpanFitsPlannedHeightWithDoubleEaves() {
        // Historical failure: body used full height then stacked eaves outside the budget.
        var component = new com.formacraft.common.llm.dto.Component(
                "STRUCTURE",
                null,
                new com.formacraft.common.llm.dto.Vec3i(0, 0, 0),
                new com.formacraft.common.llm.dto.Dimensions(7, 7, 28),
                java.util.List.of("typology:dense_eaves_pagoda"),
                Map.of(
                        "floor_count", 3,
                        "eaves_per_level", 2,
                        "height", 28,
                        "baseWidth", 7,
                        "footprint", "octagon",
                        "detailLevel", "simple"
                )
        );
        Map<String, Object> params = TypologyParamResolver.merge("dense_eaves_pagoda", component);
        GeneratedStructure structure = DenseEavesPagodaBuilder.generate(
                params, new BlockPos(0, 64, 0), null, null);
        int minY = Integer.MAX_VALUE;
        int maxY = Integer.MIN_VALUE;
        for (PlannedBlock block : structure.getBlocks()) {
            int y = block.getPos().getY();
            minY = Math.min(minY, y);
            maxY = Math.max(maxY, y);
        }
        int span = maxY - minY + 1;
        assertTrue(span <= 30, "span=" + span + " should fit planned height 28 (±2 for finial)");
        assertTrue(span >= 20, "span=" + span + " should use most of the planned height");
    }

    @Test
    void smallOctagonPagodaIsHollowWithUsableClearHeight() {
        var component = new com.formacraft.common.llm.dto.Component(
                "STRUCTURE",
                null,
                new com.formacraft.common.llm.dto.Vec3i(0, 0, 0),
                new com.formacraft.common.llm.dto.Dimensions(7, 7, 32),
                java.util.List.of("typology:dense_eaves_pagoda"),
                Map.of(
                        "floor_count", 3,
                        "eaves_per_level", 1,
                        "height", 32,
                        "baseWidth", 7,
                        "footprint", "octagon",
                        "detailLevel", "simple"
                )
        );
        Map<String, Object> params = TypologyParamResolver.merge("dense_eaves_pagoda", component);
        BlockPos origin = new BlockPos(0, 64, 0);
        GeneratedStructure structure = DenseEavesPagodaBuilder.generate(params, origin, null, null);

        // Ground-storey cavity: axis cell above the floor plate must be empty (not solid fill).
        boolean axisAir = structure.getBlocks().stream().noneMatch(b ->
                b.getPos().getX() == origin.getX()
                        && b.getPos().getZ() == origin.getZ()
                        && b.getPos().getY() == origin.getY() + 2
                        && b.getTargetState() != null
                        && !b.getTargetState().isAir());
        assertTrue(axisAir, "pagoda shaft should be hollow at axis Y+2");

        // First storey body should reach at least min clear height before eaves.
        int solidMaxInLowerBand = structure.getBlocks().stream()
                .filter(b -> b.getTargetState() != null && !b.getTargetState().isAir())
                .filter(b -> {
                    int dy = b.getPos().getY() - origin.getY();
                    return dy >= 0 && dy < 4;
                })
                .mapToInt(b -> b.getPos().getY() - origin.getY())
                .max()
                .orElse(-1);
        assertTrue(solidMaxInLowerBand >= 2,
                "ground storey body should span ≥3 clear (max dy in lower band=" + solidMaxInLowerBand + ")");
    }
}
