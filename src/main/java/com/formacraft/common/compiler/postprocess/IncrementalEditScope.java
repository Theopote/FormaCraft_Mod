package com.formacraft.common.compiler.postprocess;

import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.dto.LlmPlan;
import com.formacraft.common.llm.dto.PatchBlock;
import com.formacraft.common.network.IncrementalPrimitivePolicy;
import com.formacraft.common.network.IncrementalWindowQuery;

import java.util.List;
import java.util.Locale;

/**
 * Edit-scope contract for incremental LlmPlan patches ({@code mode=patch}).
 * <p>
 * Generating a standalone façade/decor component under {@code mode=build} is a
 * different operation from editing openings on an existing building. Aesthetic
 * post-pass expansion is suppressed only for the latter.
 * <p>
 * Classification: HARD_CONSTRAINT write isolation (GEOMETRY) — not style heuristics.
 * Prefer this contract over growing keyword type lists in {@code PostProcessPipeline}.
 */
public final class IncrementalEditScope {
    private IncrementalEditScope() {}

    /**
     * True when Detail/WindowOrder/Lighting/MaterialVariation must not expand the
     * compile result beyond the authorized incremental edit (e.g. a 2×2 glass insert).
     */
    public static boolean suppressesAestheticExpansion(LlmPlan plan) {
        if (plan == null || plan.mode() != LlmPlan.Mode.patch) {
            return false;
        }
        if (isOpeningOrFacadeSatelliteEdit(plan)) {
            return true;
        }
        return isBoundedOpeningPatchBlocksEdit(plan);
    }

    /**
     * {@code mode=patch} plan that carries only opening/façade satellites (no host mass).
     * Includes bounded glass {@code PRIMITIVE} boxes after {@link IncrementalWindowQuery#normalize}.
     */
    public static boolean isOpeningOrFacadeSatelliteEdit(LlmPlan plan) {
        if (plan == null || plan.components() == null || plan.components().isEmpty()) {
            return false;
        }
        boolean anyOpening = false;
        for (Component c : plan.components()) {
            if (c == null || c.componentType() == null) {
                return false;
            }
            String t = c.componentType().trim().toUpperCase(Locale.ROOT);
            if (isWholeBuildingRewriteType(t)) {
                return false;
            }
            Component normalized = IncrementalWindowQuery.normalize(c);
            if (isOpeningOrFacadeType(t) || IncrementalPrimitivePolicy.allows(normalized)) {
                anyOpening = true;
            } else {
                return false;
            }
        }
        return anyOpening;
    }

    /**
     * {@code mode=patch} with {@code patch.blocks[]} only (no components) when every
     * authored cell is an opening-like PLACE/REMOVE (glass / door / air). Material-only
     * wall recolors are a separate edit class — they do not suppress via this predicate.
     */
    public static boolean isBoundedOpeningPatchBlocksEdit(LlmPlan plan) {
        if (plan == null || plan.mode() != LlmPlan.Mode.patch) {
            return false;
        }
        if (plan.components() != null && !plan.components().isEmpty()) {
            return false;
        }
        if (plan.patch() == null || plan.patch().blocks() == null || plan.patch().blocks().isEmpty()) {
            return false;
        }
        List<PatchBlock> blocks = plan.patch().blocks();
        boolean anyOpening = false;
        for (PatchBlock b : blocks) {
            if (b == null) {
                return false;
            }
            String block = b.targetBlock() == null ? "" : b.targetBlock().toLowerCase(Locale.ROOT);
            String action = b.action() == null ? "" : b.action().trim().toLowerCase(Locale.ROOT);
            boolean removeOrAir = "remove".equals(action) || block.contains("air");
            boolean openingPlace = block.contains("glass") || block.contains("door")
                    || block.contains("trapdoor") || block.contains("pane");
            if (!removeOrAir && !openingPlace) {
                return false;
            }
            if (openingPlace || removeOrAir) {
                anyOpening = true;
            }
        }
        return anyOpening;
    }

    private static boolean isWholeBuildingRewriteType(String t) {
        return t.startsWith("MASS_")
                || "TOWER".equals(t)
                || "STRUCTURE".equals(t)
                || "FOUNDATION".equals(t)
                || "ROOF".equals(t)
                || "ROOF_STRUCTURE".equals(t)
                || t.contains("ASSEMBLY")
                || "WALL".equals(t)
                || "SHELL_BOX".equals(t)
                || "BOX_SHELL".equals(t);
    }

    private static boolean isOpeningOrFacadeType(String t) {
        return t.contains("WINDOW")
                || t.contains("ENTRANCE")
                || t.contains("DOOR")
                || t.contains("FACADE")
                || "OPENING".equals(t)
                || "SIGNAGE".equals(t)
                || t.contains("DECOR");
    }
}
