package com.formacraft.common.network;

import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.dto.Dimensions;
import com.formacraft.common.llm.dto.Layout;
import com.formacraft.common.llm.dto.LlmPlan;
import com.formacraft.common.llm.dto.Slot;
import com.formacraft.common.llm.dto.Vec3i;
import com.formacraft.common.model.request.FormaRequest;
import com.formacraft.server.build.PlannedBlock;
import net.minecraft.block.Blocks;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class LlmPlanTerrainBoundsTest {

    @Test
    void wantsStiltFoundationDetectsChineseKeyword() {
        FormaRequest req = new FormaRequest();
        req.setUserMessage("在悬崖边建一座悬空别墅");
        assertTrue(LlmPlanTerrainBounds.wantsStiltFoundation(req));
    }

    @Test
    void computePlannedBlockBoundsFromBlocks() {
        List<PlannedBlock> blocks = List.of(
                new PlannedBlock(new BlockPos(10, 64, 20), Blocks.STONE.getDefaultState()),
                new PlannedBlock(new BlockPos(12, 66, 22), Blocks.STONE.getDefaultState())
        );
        LlmPlanTerrainBounds.Bounds b = LlmPlanTerrainBounds.computePlannedBlockBounds(blocks);
        assertNotNull(b);
        assertEquals(10, b.minX());
        assertEquals(12, b.maxX());
        assertEquals(64, b.minY());
        assertEquals(66, b.maxY());
    }

    @Test
    void computeComponentBoundsUsesMassFootprint() {
        LlmPlan plan = new LlmPlan(
                LlmPlan.Mode.build,
                "modern",
                new Vec3i(0, 64, 0),
                null,
                new Layout(null, false, List.of(new Slot("main", new Vec3i(0, 0, 0), null, null, null, null))),
                List.of(new Component(
                        "MASS_MAIN",
                        "main",
                        new Vec3i(0, 0, 0),
                        new Dimensions(10, 8, 12),
                        null,
                        null
                )),
                null,
                null,
                null,
                null,
                null,
                null,
                null
        );
        BlockPos origin = new BlockPos(100, 64, 200);
        LlmPlanTerrainBounds.Bounds b = LlmPlanTerrainBounds.computeComponentBounds(plan, origin);
        assertNotNull(b);
        assertEquals(95, b.minX());
        assertEquals(104, b.maxX());
        assertEquals(200, b.minZ());
        assertEquals(211, b.maxZ());
    }
}
