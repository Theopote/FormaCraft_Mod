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
        assertTrue(LlmPlanPreviewBuilder.isCoercibleFullBuildPlan(plan));
    }

    @Test
    void twinMassMainCompoundIsCoercedFromPatchRejectPath() {
        LlmPlan plan = new LlmPlan(
                LlmPlan.Mode.patch,
                "chinese_pagoda",
                null,
                null,
                null,
                List.of(
                        new Component("FOUNDATION", "tower_a", new Vec3i(-9, -1, -9),
                                new Dimensions(18, 18, 1), List.of(), Map.of()),
                        new Component("MASS_MAIN", "tower_a", new Vec3i(0, 0, 0),
                                new Dimensions(16, 16, 17), List.of("hollow:true"),
                                Map.of("floor_count", 3, "hollow", true)),
                        new Component("MASS_MAIN", "tower_b", new Vec3i(28, 0, 0),
                                new Dimensions(16, 16, 17), List.of("hollow:true"),
                                Map.of("floor_count", 3, "hollow", true))
                ),
                null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null
        );
        assertTrue(LlmPlanPreviewBuilder.isCoercibleFullBuildPlan(plan));
    }

    @Test
    void facadeOnlyPatchIsNotCoercedAsFullBuild() {
        LlmPlan plan = new LlmPlan(
                LlmPlan.Mode.patch,
                "generic",
                null,
                null,
                null,
                List.of(new Component(
                        "FACADE_WINDOWS",
                        "main",
                        new Vec3i(0, 0, 0),
                        new Dimensions(10, 1, 4),
                        List.of(),
                        Map.of("allow_envelope_attach", true)
                )),
                null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null
        );
        assertFalse(LlmPlanPreviewBuilder.isCoercibleFullBuildPlan(plan));
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
