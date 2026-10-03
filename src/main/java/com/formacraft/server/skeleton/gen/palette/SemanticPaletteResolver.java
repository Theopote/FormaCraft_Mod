package com.formacraft.server.skeleton.gen.palette;

import com.formacraft.common.semantic.SemanticPlacementOp;
import com.formacraft.common.style.PaletteRule;
import com.formacraft.common.style.SemanticStyleProfile;
import com.formacraft.common.style.SemanticStyleProfileRegistry;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;

import java.util.Random;

/**
 * Semantic → BlockState 的最终执行器
 * 
 * 返回方块状态供规划阶段使用；不写入世界。
 */
public class SemanticPaletteResolver {

    private final SemanticStyleProfile style;
    private final Random random;

    public SemanticPaletteResolver(SemanticStyleProfile style, Random random) {
        this.style = style;
        this.random = random;
    }

    /**
     * 解析 SemanticPlacementOp 为 BlockState
     */
    public BlockState resolve(SemanticPlacementOp op) {
        if (op == null || op.part() == null) {
            return Blocks.STONE.getDefaultState();
        }

        PaletteRule rule = style == null ? null : style.getRule(op.part());
        BlockState fallback = op.part() == com.formacraft.common.semantic.SemanticPart.STAIR_STEP
            ? Blocks.STONE_BRICK_STAIRS.getDefaultState() : Blocks.STONE.getDefaultState();
        BlockState state = rule == null || rule.isEmpty() ? fallback : rule.pick(random);
        if (state == null) state = fallback;
        if (op.part() == com.formacraft.common.semantic.SemanticPart.STAIR_STEP) {
            if (!(state.getBlock() instanceof net.minecraft.block.StairsBlock))
                throw new IllegalArgumentException("STAIR_STEP palette must resolve to a stairs block");
            var facing = op.facing();
            if (facing == null || !facing.getAxis().isHorizontal())
                throw new IllegalArgumentException("STAIR_STEP requires horizontal facing");
            state = state.with(net.minecraft.state.property.Properties.HORIZONTAL_FACING, facing);
        }
        return state;
    }

    /**
     * 静态方法：使用 profileId 创建解析器
     */
    public static SemanticPaletteResolver create(String profileId, Random random) {
        SemanticStyleProfile profile = SemanticStyleProfileRegistry.getOrDefault(profileId);
        return new SemanticPaletteResolver(profile, random);
    }
}

