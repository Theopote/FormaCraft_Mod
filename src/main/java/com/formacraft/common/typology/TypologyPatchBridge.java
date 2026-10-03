package com.formacraft.common.typology;

import com.formacraft.common.build.GeneratedStructure;
import com.formacraft.common.build.PlannedBlock;
import com.formacraft.common.compiler.semantic.SemanticComponent;
import com.formacraft.common.patch.BlockPatch;
import com.formacraft.common.component.transform.BlockStateStringUtil;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.List;

/** Convert typology builder output to component-relative BlockPatches. */
public final class TypologyPatchBridge {

    private static final ThreadLocal<BlockPos> PLAN_WORLD_ANCHOR = new ThreadLocal<>();

    private TypologyPatchBridge() {}

    /** Set while compiling an LlmPlan with a known world anchor (plan.anchor in world space). */
    public static void setPlanWorldAnchor(BlockPos planWorldAnchor) {
        if (planWorldAnchor != null) {
            PLAN_WORLD_ANCHOR.set(planWorldAnchor);
        } else {
            PLAN_WORLD_ANCHOR.remove();
        }
    }

    public static void clearPlanWorldAnchor() {
        PLAN_WORLD_ANCHOR.remove();
    }

    public static BlockPos slotOrigin(SemanticComponent semantic) {
        if (semantic == null || semantic.slot() == null || semantic.slot().anchor() == null) {
            return null;
        }
        var a = semantic.slot().anchor();
        return new BlockPos(a.x(), a.y(), a.z());
    }

    /**
     * World-space build origin for typology interpreters: {@code planWorldAnchor + slot.anchor}.
     * Required for terrain-aware typologies (e.g. suspension_bridge followTerrain).
     */
    public static BlockPos worldBuildOrigin(SemanticComponent semantic) {
        BlockPos slot = slotOrigin(semantic);
        if (slot == null) {
            return null;
        }
        BlockPos plan = PLAN_WORLD_ANCHOR.get();
        if (plan != null) {
            return plan.add(slot.getX(), slot.getY(), slot.getZ());
        }
        return slot;
    }

    public static List<BlockPatch> toBlockPatches(GeneratedStructure structure, BlockPos worldAnchor) {
        if (structure == null || structure.getBlocks() == null || worldAnchor == null) {
            return List.of();
        }
        List<BlockPatch> patches = new ArrayList<>();
        for (PlannedBlock block : structure.getBlocks()) {
            if (block == null || block.getPos() == null) continue;
            if (block.getTargetState() == null) {
                throw new IllegalArgumentException("Typology planned block has no target state at " + block.getPos());
            }
            BlockPos relative = block.getPos().subtract(worldAnchor);
            String blockId = BlockStateStringUtil.fromState(block.getTargetState());
            patches.add(new BlockPatch(
                    BlockPatch.PLACE,
                    relative.getX(),
                    relative.getY(),
                    relative.getZ(),
                    blockId
            ));
        }
        return patches;
    }
}
