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
 * 这是唯一 setBlock 的地方（通过返回 BlockState）
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

        PaletteRule rule = style.getRule(op.part());
        if (rule == null || rule.isEmpty()) {
            // fallback：防止空规则
            return Blocks.STONE.getDefaultState();
        }

        BlockState state = rule.pick(random);
        return state != null ? state : Blocks.STONE.getDefaultState();
    }

    /**
     * 静态方法：使用 profileId 创建解析器
     */
    public static SemanticPaletteResolver create(String profileId, Random random) {
        SemanticStyleProfile profile = SemanticStyleProfileRegistry.getOrDefault(profileId);
        return new SemanticPaletteResolver(profile, random);
    }
}

