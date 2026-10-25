package com.formacraft.common.network;

import com.formacraft.common.llm.dto.LlmPlanTestFixtures;

import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.dto.Dimensions;
import com.formacraft.common.llm.dto.Layout;
import com.formacraft.common.llm.dto.LlmPlan;
import com.formacraft.common.llm.dto.Slot;
import com.formacraft.common.llm.dto.Vec3i;
import com.formacraft.common.model.request.FormaRequest;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class LlmPlanTerrainBoundsTest {
    @Test void loggedHighLegCabinPreservesAuthoredRaisedElevation() {
        var req=new FormaRequest();
        req.setUserMessage("在岸边建一座7×7格的高脚木屋，用橡木柱支撑，居住层高于水面");
        assertTrue(LlmPlanTerrainBounds.wantsStiltFoundation(req));
        req.setUserMessage("建一座普通木屋");
        assertFalse(LlmPlanTerrainBounds.wantsStiltFoundation(req));
    }

    @Test
    void oakFenceWallPlanIsLightweightLinear() {
        LlmPlan plan = LlmPlanTestFixtures.builder()
                .mode(LlmPlan.Mode.build)
                .components(List.of(new Component(
                        "WALL", "s", new Vec3i(0, 0, 0), new Dimensions(12, 1, 2), List.of(),
                        Map.of("wall_block", "minecraft:oak_fence"))))
                .build();
        assertTrue(LlmPlanTerrainBounds.isLightweightLinearPlan(plan));
    }

    @Test
    void massPlusFenceIsNotLightweightLinear() {
        LlmPlan plan = LlmPlanTestFixtures.builder()
                .mode(LlmPlan.Mode.build)
                .components(List.of(
                        new Component("MASS_MAIN", "s", new Vec3i(0, 0, 0), new Dimensions(9, 7, 5), null, null),
                        new Component("WALL", "s", new Vec3i(0, 0, 0), new Dimensions(9, 1, 1), List.of("railing"),
                                Map.of("wall_block", "minecraft:oak_fence"))))
                .build();
        assertFalse(LlmPlanTerrainBounds.isLightweightLinearPlan(plan));
    }

    @Test
    void padHeightFallbackTranslatesRoomsRoofAndAirTogether() {
        com.formacraft.test.MinecraftRegistryTestBootstrap.initialize();
        var floor = new com.formacraft.common.build.PlannedBlock(new BlockPos(-15,64,0), net.minecraft.block.Blocks.STONE.getDefaultState());
        var room = new com.formacraft.common.build.PlannedBlock(new BlockPos(-15,65,0), net.minecraft.block.Blocks.AIR.getDefaultState());
        var roof = new com.formacraft.common.build.PlannedBlock(new BlockPos(-15,72,0), net.minecraft.block.Blocks.OAK_PLANKS.getDefaultState());
        var input = List.of(floor, room, roof);
        for (int dy : new int[]{-5,4}) {
            var output = LlmPlanTerrainBounds.translateBlocks(input, dy);
            for (int i=0; i<input.size(); i++) {
                assertEquals(input.get(i).getPos().up(dy), output.get(i).getPos());
                assertSame(input.get(i).getTargetState(), output.get(i).getTargetState());
            }
            assertEquals(8, output.get(2).getPos().getY()-output.get(0).getPos().getY());
        }
    }

    @Test
    void wantsStiltFoundationDetectsChineseKeyword() {
        FormaRequest req = new FormaRequest();
        req.setUserMessage("在悬崖边建一座悬空别墅");
        assertTrue(LlmPlanTerrainBounds.wantsStiltFoundation(req));
    }

    @Test
    void boundsUnionExpandsFootprint() {
        LlmPlanTerrainBounds.Bounds a = new LlmPlanTerrainBounds.Bounds(0, 64, 0, 9, 72, 9);
        LlmPlanTerrainBounds.Bounds b = new LlmPlanTerrainBounds.Bounds(5, 64, 5, 14, 72, 14);
        LlmPlanTerrainBounds.Bounds u = a.union(b);
        assertEquals(0, u.minX());
        assertEquals(14, u.maxX());
        assertEquals(15, u.width());
    }

    @Test
    void computeComponentBoundsUsesMassFootprint() {
        LlmPlan plan = LlmPlanTestFixtures.builder()
                .mode(LlmPlan.Mode.build)
                .styleProfile("modern")
                .anchor(new Vec3i(0, 64, 0))
                .layout(new Layout(null, false, List.of(new Slot("main", new Vec3i(0, 0, 0), null, null, null, null))))
                .components(List.of(new Component(
                        "MASS_MAIN",
                        "main",
                        new Vec3i(0, 0, 0),
                        new Dimensions(10, 8, 12),
                        null,
                        null
                )))
                .build();

        BlockPos origin = new BlockPos(100, 64, 200);
        LlmPlanTerrainBounds.Bounds bounds = LlmPlanTerrainBounds.computeComponentBounds(plan, origin);
        assertNotNull(bounds);
        assertTrue(bounds.width() >= 10);
        assertTrue(bounds.depth() >= 12);
        assertTrue(bounds.height() >= 8);
    }

    @Test
    void lightweightFencePlanSkipsTerrainPadClassification() {
        LlmPlan fence = LlmPlanTestFixtures.builder()
                .mode(LlmPlan.Mode.build)
                .components(List.of(new Component(
                        "WALL", "s", new Vec3i(0, 0, 0), new Dimensions(12, 1, 2), List.of(),
                        java.util.Map.of("wall_block", "minecraft:oak_fence"))))
                .build();
        assertTrue(LlmPlanTerrainBounds.isLightweightLinearPlan(fence));

        LlmPlan house = LlmPlanTestFixtures.builder()
                .mode(LlmPlan.Mode.build)
                .components(List.of(new Component(
                        "MASS_MAIN", "main", new Vec3i(0, 0, 0), new Dimensions(10, 8, 12), null, null)))
                .build();
        assertFalse(LlmPlanTerrainBounds.isLightweightLinearPlan(house));
    }
}
