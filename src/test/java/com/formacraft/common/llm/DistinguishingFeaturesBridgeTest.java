package com.formacraft.common.llm;

import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.dto.Dimensions;
import com.formacraft.common.llm.dto.GlobalConstraints;
import com.formacraft.common.llm.dto.LlmPlan;
import com.formacraft.common.llm.dto.Vec3i;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DistinguishingFeaturesBridgeTest {

    @Test
    void slugifyMatchesPythonPlanSpecificityGuard() {
        assertEquals("white_sail_shells", DistinguishingFeaturesBridge.slugify("white sail shells"));
        assertEquals("shell_roof", DistinguishingFeaturesBridge.slugify("shell roof"));
    }

    @Test
    void enrichMergesTopLevelIntoStyleAttributesAndComponentFeatures() {
        LlmPlan plan = new LlmPlan(
                LlmPlan.Mode.build,
                "Modern_Expressionist",
                new Vec3i(0, 64, 0),
                new GlobalConstraints(GlobalConstraints.Facing.SOUTH, GlobalConstraints.Symmetry.NONE, null),
                null,
                List.of(new Component(
                        "MASS_MAIN",
                        null,
                        new Vec3i(0, 0, 0),
                        new Dimensions(20, 14, 10),
                        List.of(),
                        Map.of()
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
                null,
                null,
                List.of("white sail shells", "shell roof"),
                null
        );

        LlmPlan enriched = DistinguishingFeaturesBridge.enrich(plan);

        assertNotNull(enriched.styleAttributes());
        assertTrue(enriched.styleAttributes().decorativeElements().contains("white sail shells"));
        assertTrue(enriched.styleAttributes().decorativeElements().contains("shell roof"));

        Component mass = enriched.components().get(0);
        assertTrue(mass.features().contains("white_sail_shells"));
        assertTrue(mass.features().contains("shell_roof"));
        assertEquals(List.of("white sail shells", "shell roof"), mass.params().get("distinctive_features"));
    }

    @Test
    void enrichIsIdempotent() {
        LlmPlan plan = new LlmPlan(
                LlmPlan.Mode.build,
                "DEFAULT",
                new Vec3i(0, 64, 0),
                null,
                null,
                List.of(new Component(
                        "ROOF",
                        null,
                        new Vec3i(0, 10, 0),
                        new Dimensions(20, 16, 5),
                        List.of("overhang"),
                        Map.of()
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
                null,
                null,
                List.of("lattice windows"),
                null
        );

        LlmPlan once = DistinguishingFeaturesBridge.enrich(plan);
        LlmPlan twice = DistinguishingFeaturesBridge.enrich(once);

        assertEquals(
                once.styleAttributes().decorativeElements().size(),
                twice.styleAttributes().decorativeElements().size()
        );
        assertEquals(once.components().get(0).features().size(), twice.components().get(0).features().size());
    }

    @Test
    void enrichNoOpWhenDistinguishingFeaturesMissing() {
        LlmPlan plan = new LlmPlan(
                LlmPlan.Mode.build,
                "DEFAULT",
                new Vec3i(0, 64, 0),
                null,
                null,
                List.of(),
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
                null,
                null,
                null,
                null
        );

        assertEquals(plan, DistinguishingFeaturesBridge.enrich(plan));
    }
}
