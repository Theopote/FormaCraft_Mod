package com.formacraft.common.llm.dto;

import com.formacraft.common.alignment.AlignmentAndSymmetry;

import java.util.List;
import java.util.Map;

/** Test-only helper to avoid updating every {@link LlmPlan} constructor when fields are added. */
public final class LlmPlanTestFixtures {

    private LlmPlanTestFixtures() {}

    public static LlmPlan minimal(Map<String, Object> proportionHints, List<Component> components) {
        return new LlmPlan(
                LlmPlan.Mode.build,
                "DEFAULT",
                new Vec3i(0, 64, 0),
                new GlobalConstraints(
                        GlobalConstraints.Facing.SOUTH,
                        GlobalConstraints.Symmetry.NONE,
                        GlobalConstraints.TerrainStrategy.ADAPTIVE),
                new Layout(null, false, List.of()),
                components,
                null,
                null,
                proportionHints,
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
    }

    public static LlmPlan withAlignment(AlignmentAndSymmetry alignment, List<Component> components) {
        return new LlmPlan(
                LlmPlan.Mode.build,
                "DEFAULT",
                new Vec3i(0, 64, 0),
                new GlobalConstraints(
                        GlobalConstraints.Facing.SOUTH,
                        GlobalConstraints.Symmetry.MIRROR_X,
                        GlobalConstraints.TerrainStrategy.ADAPTIVE),
                new Layout(null, false, List.of()),
                components,
                null,
                null,
                null,
                alignment,
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
    }
}
