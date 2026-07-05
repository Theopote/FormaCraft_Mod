package com.formacraft.common.compiler.postprocess;

import com.formacraft.common.generation.component.util.ComponentFloorCorniceDecorator;
import com.formacraft.common.llm.dto.LlmPlan;
import com.formacraft.common.patch.BlockPatch;
import com.formacraft.common.palette.component.Palette;
import com.formacraft.common.palette.component.PaletteLibrary;
import com.formacraft.common.semantic.SemanticPart;
import com.formacraft.FormacraftMod;
import net.minecraft.util.math.Direction;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;

/**
 * 在 story 分界高度，把外墙顶圈替换为 inverted stairs 檐口。
 */
public class FloorCornicePostProcessor implements PostProcessor {

    private static final int MAX_REPLACEMENTS = 2000;

    @Override
    public List<BlockPatch> process(List<BlockPatch> patches, PostProcessContext context) {
        if (patches == null || patches.isEmpty() || context == null || context.plan() == null) {
            return patches;
        }
        LlmPlan plan = context.plan();
        if (!ComponentFloorCorniceDecorator.shouldApply(plan)) {
            return patches;
        }

        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxY = Integer.MIN_VALUE;
        int maxZ = Integer.MIN_VALUE;

        for (BlockPatch patch : patches) {
            if (patch == null || BlockPatch.REMOVE.equals(patch.action())) {
                continue;
            }
            String target = patch.targetBlock();
            if (target == null || target.isBlank() || "minecraft:air".equals(target)) {
                continue;
            }
            minX = Math.min(minX, patch.dx());
            minY = Math.min(minY, patch.dy());
            minZ = Math.min(minZ, patch.dz());
            maxX = Math.max(maxX, patch.dx());
            maxY = Math.max(maxY, patch.dy());
            maxZ = Math.max(maxZ, patch.dz());
        }

        if (minX == Integer.MAX_VALUE) {
            return patches;
        }

        int height = maxY - minY + 1;
        int floorHeight = ComponentFloorCorniceDecorator.resolveFloorHeight(plan, height);
        BitSet boundaryYs = ComponentFloorCorniceDecorator.computeFloorBoundaryYs(height, floorHeight);
        if (boundaryYs.isEmpty()) {
            return patches;
        }

        String styleProfile = plan.styleProfile() != null ? plan.styleProfile() : "MEDIEVAL_CLASSIC";
        Palette palette = PaletteLibrary.forStyle(styleProfile);
        String trim = palette.pick(SemanticPart.WALL_ACCENT);
        if (trim == null || trim.isBlank()) {
            trim = palette.pick(SemanticPart.DECOR);
        }
        if (trim == null || trim.isBlank()) {
            trim = "minecraft:stone_bricks";
        }

        List<BlockPatch> out = new ArrayList<>(patches.size());
        int replaced = 0;

        for (BlockPatch patch : patches) {
            if (patch == null) {
                continue;
            }
            if (replaced < MAX_REPLACEMENTS
                    && shouldReplaceWithCornice(patch, minX, maxX, minZ, maxZ, minY, boundaryYs)) {
                Direction outward = ComponentFloorCorniceDecorator.outwardFacing(
                        patch.dx(), patch.dz(), minX, maxX, minZ, maxZ);
                String stair = ComponentFloorCorniceDecorator.corniceStairBlock(trim, outward);
                out.add(new BlockPatch(BlockPatch.REPLACE, patch.dx(), patch.dy(), patch.dz(), stair));
                replaced++;
            } else {
                out.add(patch);
            }
        }

        if (replaced > 0) {
            FormacraftMod.LOGGER.debug("FloorCornicePostProcessor: replaced {} exterior cells with inverted stair cornice",
                    replaced);
        }
        return out;
    }

    private static boolean shouldReplaceWithCornice(
            BlockPatch patch,
            int minX, int maxX, int minZ, int maxZ,
            int minY,
            BitSet boundaryYs
    ) {
        if (BlockPatch.REMOVE.equals(patch.action())) {
            return false;
        }
        if (!ComponentFloorCorniceDecorator.isCorniceCandidateBlock(patch.targetBlock())) {
            return false;
        }
        if (!ComponentFloorCorniceDecorator.isPerimeter(patch.dx(), patch.dz(), minX, maxX, minZ, maxZ)) {
            return false;
        }
        int relY = patch.dy() - minY;
        return boundaryYs.get(relY);
    }
}
