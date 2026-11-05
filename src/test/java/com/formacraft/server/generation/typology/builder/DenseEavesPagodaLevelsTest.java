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
        assertEquals(3, DenseEavesPagodaBuilder.resolveLevels(p, 13, 7, 15));
    }

    @Test
    void floorCountThreeHonored() {
        TypologyParams p = new TypologyParams(Map.of("floor_count", 3));
        assertEquals(3, DenseEavesPagodaBuilder.resolveLevels(p, 13, 7, 15));
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
        assertEquals(3, DenseEavesPagodaBuilder.resolveLevels(p, 13, 7, 15));
    }

    @Test
    void implicitDefaultKeepsTallMin() {
        TypologyParams p = new TypologyParams(Map.of());
        assertEquals(13, DenseEavesPagodaBuilder.resolveLevels(p, 13, 7, 15));
    }

    @Test
    void octagonVerticalSpanFitsPlannedHeightWithDoubleEaves() {
        // Historical failure: body used full height then stacked eaves outside the budget.
        var component = new com.formacraft.common.llm.dto.Component(
                "STRUCTURE",
                null,
                new com.formacraft.common.llm.dto.Vec3i(0, 0, 0),
                new com.formacraft.common.llm.dto.Dimensions(7, 7, 24),
                java.util.List.of("typology:dense_eaves_pagoda"),
                Map.of(
                        "floor_count", 3,
                        "eaves_per_level", 2,
                        "height", 24,
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
        assertTrue(span <= 26, "span=" + span + " should fit planned height 24 (±2 for finial)");
        assertTrue(span >= 18, "span=" + span + " should use most of the planned height");
    }
}
