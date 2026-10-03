package com.formacraft.common.llm.dto;

import com.formacraft.common.alignment.AlignmentAndSymmetry;
import java.util.List;
import java.util.Map;

/** Named fixtures: schema additions require updating only Builder.build(). */
public final class LlmPlanTestFixtures {
    private LlmPlanTestFixtures() {}
    public static Builder builder() { return new Builder(); }
    public static LlmPlan minimal(Map<String, Object> hints, List<Component> components) {
        return defaults().proportionHints(hints).components(components).build();
    }
    public static LlmPlan withAlignment(AlignmentAndSymmetry alignment, List<Component> components) {
        return defaults().globalConstraints(new GlobalConstraints(GlobalConstraints.Facing.SOUTH,
                GlobalConstraints.Symmetry.MIRROR_X, GlobalConstraints.TerrainStrategy.ADAPTIVE))
                .alignmentAndSymmetry(alignment).components(components).build();
    }
    private static Builder defaults() {
        return builder().mode(LlmPlan.Mode.build).styleProfile("DEFAULT").anchor(new Vec3i(0, 64, 0))
                .globalConstraints(new GlobalConstraints(GlobalConstraints.Facing.SOUTH,
                        GlobalConstraints.Symmetry.NONE, GlobalConstraints.TerrainStrategy.ADAPTIVE))
                .layout(new Layout(null, false, List.of()));
    }
    /** Unspecified fields remain null so tests can exercise missing-field behavior. */
    public static final class Builder {
        private LlmPlan.Mode mode;
        private String styleProfile;
        private Vec3i anchor;
        private GlobalConstraints globalConstraints;
        private Layout layout;
        private List<Component> components;
        private com.formacraft.common.genome.BuildingGenome genome;
        private StyleAttributes styleAttributes;
        private Map<String, Object> proportionHints;
        private AlignmentAndSymmetry alignmentAndSymmetry;
        private String targetSlotId;
        private String allowedArea;
        private PatchBlockSection patch;
        private PlanProgram planProgram;
        private PlanSkeleton planSkeleton;
        private String planStatus;
        private String error;
        private CapabilityGap capabilityGap;
        private String playerFidelityNoticeZh;
        private List<String> distinguishingFeatures;
        private String enrichmentGuard;

        public Builder mode(LlmPlan.Mode value) { mode = value; return this; }
        public Builder styleProfile(String value) { styleProfile = value; return this; }
        public Builder anchor(Vec3i value) { anchor = value; return this; }
        public Builder globalConstraints(GlobalConstraints value) { globalConstraints = value; return this; }
        public Builder layout(Layout value) { layout = value; return this; }
        public Builder components(List<Component> value) { components = value; return this; }
        public Builder genome(com.formacraft.common.genome.BuildingGenome value) { genome = value; return this; }
        public Builder styleAttributes(StyleAttributes value) { styleAttributes = value; return this; }
        public Builder proportionHints(Map<String, Object> value) { proportionHints = value; return this; }
        public Builder alignmentAndSymmetry(AlignmentAndSymmetry value) { alignmentAndSymmetry = value; return this; }
        public Builder targetSlotId(String value) { targetSlotId = value; return this; }
        public Builder allowedArea(String value) { allowedArea = value; return this; }
        public Builder patch(PatchBlockSection value) { patch = value; return this; }
        public Builder planProgram(PlanProgram value) { planProgram = value; return this; }
        public Builder planSkeleton(PlanSkeleton value) { planSkeleton = value; return this; }
        public Builder planStatus(String value) { planStatus = value; return this; }
        public Builder error(String value) { error = value; return this; }
        public Builder capabilityGap(CapabilityGap value) { capabilityGap = value; return this; }
        public Builder playerFidelityNoticeZh(String value) { playerFidelityNoticeZh = value; return this; }
        public Builder distinguishingFeatures(List<String> value) { distinguishingFeatures = value; return this; }
        public Builder enrichmentGuard(String value) { enrichmentGuard = value; return this; }

        public LlmPlan build() {
            return new LlmPlan(
                    mode,
                    styleProfile,
                    anchor,
                    globalConstraints,
                    layout,
                    components,
                    genome,
                    styleAttributes,
                    proportionHints,
                    alignmentAndSymmetry,
                    targetSlotId,
                    allowedArea,
                    patch,
                    planProgram,
                    planSkeleton,
                    planStatus,
                    error,
                    capabilityGap,
                    playerFidelityNoticeZh,
                    distinguishingFeatures,
                    enrichmentGuard);
        }
    }
}
