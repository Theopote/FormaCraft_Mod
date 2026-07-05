package com.formacraft.common.network;

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

import static org.junit.jupiter.api.Assertions.*;

class LlmPlanTerrainBoundsTest {

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
                null,
                null,
                null,
                null,
                null,
                null
        );
        BlockPos origin = new BlockPos(100, 64, 200);
        LlmPlanTerrainBounds.Bounds bounds = LlmPlanTerrainBounds.computeComponentBounds(plan, origin);
        assertNotNull(bounds);
        assertTrue(bounds.width() >= 10);
        assertTrue(bounds.depth() >= 12);
        assertTrue(bounds.height() >= 8);
    }
}
