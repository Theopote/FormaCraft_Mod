package com.formacraft.server.network;

import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.dto.Dimensions;
import com.formacraft.common.llm.dto.LlmPlan;
import com.formacraft.common.llm.dto.Vec3i;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class TypologyStructurePatchCoercionTest {

    @Test
    void twinPagodaStructurePlanIsCoercedFromPatchRejectPath() {
        LlmPlan plan = new LlmPlan(
                LlmPlan.Mode.patch,
                "chinese_pagoda_twin",
                null,
                null,
                null,
                List.of(
                        structure("pagoda_a", -28, "minecraft:blackstone_slab"),
                        structure("pagoda_b", 28, "minecraft:deepslate_tiles")
                ),
                null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null
        );
        assertTrue(LlmPlanPreviewBuilder.isTypologyStructureBuildPlan(plan));
    }

    @Test
    void massMainRebuildStillRejectedAsTypologyStructure() {
        LlmPlan plan = new LlmPlan(
                LlmPlan.Mode.patch,
                "generic",
                null,
                null,
                null,
                List.of(new Component(
                        "MASS_MAIN",
                        "main",
                        new Vec3i(0, 0, 0),
                        new Dimensions(10, 10, 10),
                        List.of(),
                        Map.of()
                )),
                null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null
        );
        assertFalse(LlmPlanPreviewBuilder.isTypologyStructureBuildPlan(plan));
    }

    private static Component structure(String id, int x, String roof) {
        return new Component(
                "STRUCTURE",
                id + "_slot",
                new Vec3i(x, 0, 0),
                new Dimensions(20, 20, 20),
                List.of("typology:dense_eaves_pagoda"),
                Map.of(
                        "component_id", id,
                        "typology_id", "dense_eaves_pagoda",
                        "floor_count", 3,
                        "eaves_per_level", 2,
                        "roof_block", roof
                )
        );
    }
}
