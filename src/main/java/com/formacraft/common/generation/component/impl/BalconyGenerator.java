package com.formacraft.common.generation.component.impl;

import com.formacraft.common.compiler.semantic.SemanticComponent;
import com.formacraft.common.generation.component.ComponentGenerator;
import com.formacraft.common.generation.component.util.ComponentMaterialResolver;
import com.formacraft.common.generation.component.util.ComponentParamParsers;
import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.dto.Dimensions;
import com.formacraft.common.llm.dto.GlobalConstraints;
import com.formacraft.common.llm.dto.Vec3i;
import com.formacraft.common.patch.BlockPatch;
import com.formacraft.common.semantic.SemanticPart;

import java.util.ArrayList;
import java.util.List;

/**
 * BalconyGenerator（阳台生成器）
 *
 * 生成阳台/挑檐平台：平台 + 外挑三面栏杆（靠主墙一侧留空以便开门通行）。
 */
public class BalconyGenerator implements ComponentGenerator {

    @Override
    public List<BlockPatch> generate(SemanticComponent semantic) {
        List<BlockPatch> out = new ArrayList<>();

        Component c = semantic.source();
        if (c == null || c.dimensions() == null || c.relativePosition() == null) {
            return out;
        }

        Dimensions d = c.dimensions();
        Vec3i rp = com.formacraft.common.generation.component.util.ComponentFootprintUtil.resolveLegacyCornerOrigin(c);

        int width = Math.max(1, d.width());
        int depth = Math.max(1, d.depth());
        int height = Math.max(1, Math.min(2, d.height())); // 阳台通常只有 1-2 格高

        GlobalConstraints.Facing facing = GlobalConstraints.Facing.SOUTH;
        if (semantic.slot() != null && semantic.slot().facing() != null) {
            facing = semantic.slot().facing();
        }
        facing = ComponentParamParsers.facingParam(
                c.params(), facing, "facing", "attach_side", "attachSide");

        String floorBlock = ComponentMaterialResolver.resolve(
                semantic, SemanticPart.FLOOR, "minecraft:oak_planks", "floor_block", "deck_block", "material");
        String railBlock = ComponentMaterialResolver.resolve(
                semantic, SemanticPart.RAILING, "minecraft:oak_fence", "railing_block", "fence_block", "trim_block");

        for (int x = 0; x < width; x++) {
            for (int z = 0; z < depth; z++) {
                out.add(new BlockPatch(
                        BlockPatch.PLACE,
                        rp.x() + x,
                        rp.y(),
                        rp.z() + z,
                        floorBlock
                ));
            }
        }

        if (height >= 2) {
            for (int x = 0; x < width; x++) {
                for (int z = 0; z < depth; z++) {
                    boolean isEdge = (x == 0 || x == width - 1 || z == 0 || z == depth - 1);
                    if (!isEdge || isWallSide(x, z, width, depth, facing)) continue;
                    out.add(new BlockPatch(
                            BlockPatch.PLACE,
                            rp.x() + x,
                            rp.y() + 1,
                            rp.z() + z,
                            railBlock
                    ));
                }
            }
        }

        return out;
    }

    /** Edge attached to the host wall (opposite of outward facing). */
    static boolean isWallSide(int x, int z, int width, int depth, GlobalConstraints.Facing facing) {
        return switch (facing != null ? facing : GlobalConstraints.Facing.SOUTH) {
            case SOUTH -> z == 0;
            case NORTH -> z == depth - 1;
            case EAST -> x == 0;
            case WEST -> x == width - 1;
        };
    }
}
