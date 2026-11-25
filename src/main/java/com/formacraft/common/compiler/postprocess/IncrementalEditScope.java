package com.formacraft.common.compiler.postprocess;

import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.dto.LlmPlan;
import com.formacraft.common.llm.dto.PatchBlock;
import com.formacraft.common.network.IncrementalPrimitivePolicy;
import com.formacraft.common.network.IncrementalWindowQuery;
import net.minecraft.util.math.BlockPos;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Edit-scope contract for incremental LlmPlan patches ({@code mode=patch}).
 * <p>
 * Generating a standalone façade/decor component under {@code mode=build} is a
 * different operation from editing openings on an existing building. Aesthetic
 * post-pass expansion is suppressed only for the latter.
 * <p>
 * Phase 01.9 edit classes: openings/façade, material recolor, local REMOVE,
 * component add (satellites), mixed (union). Aesthetic expansion is suppressed
 * whenever {@code mode=patch} and an authorized write set Ω is defined.
 * <p>
 * Classification: HARD_CONSTRAINT write isolation (GEOMETRY) — not style heuristics.
 */
public final class IncrementalEditScope {
    private IncrementalEditScope() {}

    /** Phase 01.9 incremental edit classes under {@code mode=patch}. */
    public enum EditClass {
        NONE,
        OPENINGS_FACADE,
        MATERIAL_RECOLOR,
        LOCAL_REMOVE,
        COMPONENT_ADD,
        MIXED
    }

    /**
     * True when Detail/WindowOrder/Lighting/MaterialVariation must not expand the
     * compile result beyond the authorized incremental edit.
     */
    public static boolean suppressesAestheticExpansion(LlmPlan plan) {
        if (plan == null || plan.mode() != LlmPlan.Mode.patch) {
            return false;
        }
        // Phase 01.9: any defined authorized write set suppresses aesthetic growth —
        // not only WINDOW/FACADE keywords.
        return !authorizedWriteCells(plan).isEmpty();
    }

    /**
     * Classify the patch plan's edit intent. {@link EditClass#NONE} when not a patch
     * or when no bounded write set can be derived.
     */
    public static EditClass classify(LlmPlan plan) {
        if (plan == null || plan.mode() != LlmPlan.Mode.patch) {
            return EditClass.NONE;
        }
        boolean openings = isOpeningOrFacadeSatelliteEdit(plan) || isBoundedOpeningPatchBlocksEdit(plan);
        boolean recolor = isMaterialRecolorPatchBlocksEdit(plan);
        boolean remove = isLocalRemovePatchBlocksEdit(plan);
        boolean componentAdd = isSatelliteComponentAddEdit(plan);
        int n = (openings ? 1 : 0) + (recolor ? 1 : 0) + (remove ? 1 : 0) + (componentAdd ? 1 : 0);
        if (n > 1) return EditClass.MIXED;
        if (n == 1) {
            if (openings) return EditClass.OPENINGS_FACADE;
            if (recolor) return EditClass.MATERIAL_RECOLOR;
            if (remove) return EditClass.LOCAL_REMOVE;
            return EditClass.COMPONENT_ADD;
        }
        // Homogeneous predicates missed a heterogeneous patch.blocks[] batch — classify by cell kinds.
        EditClass fromBlocks = classifyHeterogeneousPatchBlocks(plan);
        if (fromBlocks != EditClass.NONE) {
            return fromBlocks;
        }
        return authorizedWriteCells(plan).isEmpty() ? EditClass.NONE : EditClass.MIXED;
    }

    /**
     * When {@code patch.blocks[]} mixes openings / recolor / remove cells, return
     * {@link EditClass#MIXED}; a single kind still maps to that class.
     */
    private static EditClass classifyHeterogeneousPatchBlocks(LlmPlan plan) {
        if (plan.components() != null && !plan.components().isEmpty()) {
            return EditClass.NONE;
        }
        if (plan.patch() == null || plan.patch().blocks() == null || plan.patch().blocks().isEmpty()) {
            return EditClass.NONE;
        }
        boolean hasOpening = false;
        boolean hasRecolor = false;
        boolean hasRemove = false;
        for (PatchBlock b : plan.patch().blocks()) {
            if (b == null) return EditClass.NONE;
            String block = b.targetBlock() == null ? "" : b.targetBlock().toLowerCase(Locale.ROOT);
            String action = b.action() == null ? "" : b.action().trim().toLowerCase(Locale.ROOT);
            boolean removeOrAir = "remove".equals(action) || block.contains("air");
            boolean openingPlace = ("place".equals(action) || "replace".equals(action))
                    && (block.contains("glass") || block.contains("door")
                    || block.contains("trapdoor") || block.contains("pane"));
            boolean recolorPlace = ("place".equals(action) || "replace".equals(action))
                    && !removeOrAir && !openingPlace;
            if (removeOrAir) hasRemove = true;
            else if (openingPlace) hasOpening = true;
            else if (recolorPlace) hasRecolor = true;
            else return EditClass.NONE;
        }
        int kinds = (hasOpening ? 1 : 0) + (hasRecolor ? 1 : 0) + (hasRemove ? 1 : 0);
        if (kinds == 0) return EditClass.NONE;
        if (kinds > 1) return EditClass.MIXED;
        if (hasOpening) return EditClass.OPENINGS_FACADE;
        if (hasRecolor) return EditClass.MATERIAL_RECOLOR;
        return EditClass.LOCAL_REMOVE;
    }

    /**
     * Authorized write cells Ω for {@code mode=patch} (plan-local). Empty when the
     * plan is not a bounded incremental edit.
     */
    public static Set<BlockPos> authorizedWriteCells(LlmPlan plan) {
        Set<BlockPos> out = new HashSet<>();
        if (plan == null || plan.mode() != LlmPlan.Mode.patch) {
            return Set.of();
        }
        if (plan.patch() != null && plan.patch().blocks() != null) {
            for (PatchBlock b : plan.patch().blocks()) {
                if (b == null) continue;
                out.add(new BlockPos(b.dx(), b.dy(), b.dz()));
            }
        }
        if (plan.components() != null) {
            for (Component c : plan.components()) {
                if (c == null || c.relativePosition() == null || c.dimensions() == null) continue;
                String t = c.componentType() == null ? "" : c.componentType().trim().toUpperCase(Locale.ROOT);
                if (isWholeBuildingRewriteType(t)) {
                    // Whole-building rewrite is not a bounded Ω — clear and abort.
                    return Set.of();
                }
                if (!(isOpeningOrFacadeType(t)
                        || IncrementalPrimitivePolicy.allows(IncrementalWindowQuery.normalize(c))
                        || isSatelliteAddType(t))) {
                    return Set.of();
                }
                int ox = c.relativePosition().x();
                int oy = c.relativePosition().y();
                int oz = c.relativePosition().z();
                int w = Math.max(1, c.dimensions().width());
                int h = Math.max(1, c.dimensions().height());
                int d = Math.max(1, c.dimensions().depth());
                for (int x = 0; x < w; x++) {
                    for (int y = 0; y < h; y++) {
                        for (int z = 0; z < d; z++) {
                            out.add(new BlockPos(ox + x, oy + y, oz + z));
                        }
                    }
                }
            }
        }
        return Set.copyOf(out);
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
     * Satellite-only component add under {@code mode=patch} (decor / signage / non-mass).
     * Distinct from openings when the type is not window/façade/door.
     */
    public static boolean isSatelliteComponentAddEdit(LlmPlan plan) {
        if (plan == null || plan.mode() != LlmPlan.Mode.patch) {
            return false;
        }
        if (plan.components() == null || plan.components().isEmpty()) {
            return false;
        }
        boolean anySatellite = false;
        for (Component c : plan.components()) {
            if (c == null || c.componentType() == null) return false;
            String t = c.componentType().trim().toUpperCase(Locale.ROOT);
            if (isWholeBuildingRewriteType(t)) return false;
            if (isOpeningOrFacadeType(t) || IncrementalPrimitivePolicy.allows(IncrementalWindowQuery.normalize(c))) {
                // Openings are their own class; component-add is the residual satellite set.
                continue;
            }
            if (!isSatelliteAddType(t)) return false;
            anySatellite = true;
        }
        return anySatellite;
    }

    /**
     * {@code mode=patch} with {@code patch.blocks[]} only (no components) when every
     * authored cell is an opening-like PLACE/REMOVE (glass / door / air).
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
        boolean anyOpeningPlace = false;
        for (PatchBlock b : blocks) {
            if (b == null) {
                return false;
            }
            String block = b.targetBlock() == null ? "" : b.targetBlock().toLowerCase(Locale.ROOT);
            String action = b.action() == null ? "" : b.action().trim().toLowerCase(Locale.ROOT);
            boolean removeOrAir = "remove".equals(action) || block.contains("air");
            boolean openingPlace = ("place".equals(action) || "replace".equals(action))
                    && (block.contains("glass") || block.contains("door")
                    || block.contains("trapdoor") || block.contains("pane"));
            if (!removeOrAir && !openingPlace) {
                return false;
            }
            if (openingPlace) {
                anyOpeningPlace = true;
            }
        }
        // Pure REMOVE batches are {@link #isLocalRemovePatchBlocksEdit}; openings need a PLACE.
        return anyOpeningPlace;
    }

    /**
     * Same-cell PLACE material changes in {@code patch.blocks[]} (non-opening solids).
     * Classification: HARD_CONSTRAINT write isolation for recolor edits.
     */
    public static boolean isMaterialRecolorPatchBlocksEdit(LlmPlan plan) {
        if (plan == null || plan.mode() != LlmPlan.Mode.patch) {
            return false;
        }
        if (plan.components() != null && !plan.components().isEmpty()) {
            return false;
        }
        if (plan.patch() == null || plan.patch().blocks() == null || plan.patch().blocks().isEmpty()) {
            return false;
        }
        boolean anyRecolor = false;
        for (PatchBlock b : plan.patch().blocks()) {
            if (b == null) return false;
            String block = b.targetBlock() == null ? "" : b.targetBlock().toLowerCase(Locale.ROOT);
            String action = b.action() == null ? "" : b.action().trim().toLowerCase(Locale.ROOT);
            if (!"place".equals(action) && !"replace".equals(action)) {
                return false;
            }
            if (block.contains("air") || block.contains("glass") || block.contains("door")
                    || block.contains("trapdoor") || block.contains("pane")) {
                return false;
            }
            anyRecolor = true;
        }
        return anyRecolor;
    }

    /** Local REMOVE / air writes in {@code patch.blocks[]} only. */
    public static boolean isLocalRemovePatchBlocksEdit(LlmPlan plan) {
        if (plan == null || plan.mode() != LlmPlan.Mode.patch) {
            return false;
        }
        if (plan.components() != null && !plan.components().isEmpty()) {
            return false;
        }
        if (plan.patch() == null || plan.patch().blocks() == null || plan.patch().blocks().isEmpty()) {
            return false;
        }
        boolean anyRemove = false;
        for (PatchBlock b : plan.patch().blocks()) {
            if (b == null) return false;
            String block = b.targetBlock() == null ? "" : b.targetBlock().toLowerCase(Locale.ROOT);
            String action = b.action() == null ? "" : b.action().trim().toLowerCase(Locale.ROOT);
            boolean removeOrAir = "remove".equals(action) || block.contains("air");
            if (!removeOrAir) return false;
            anyRemove = true;
        }
        return anyRemove;
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

    private static boolean isSatelliteAddType(String t) {
        return t.startsWith("DECOR")
                || t.contains("SIGN")
                || t.contains("BANNER")
                || t.contains("RAILING")
                || t.contains("FENCE")
                || "PATH".equals(t)
                || "PLAZA".equals(t);
    }
}
