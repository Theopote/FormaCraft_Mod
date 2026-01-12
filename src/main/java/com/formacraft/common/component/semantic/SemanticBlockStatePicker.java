package com.formacraft.common.component.semantic;

import com.formacraft.common.semantic.SemanticPart;
import com.formacraft.common.style.PaletteRule;
import com.formacraft.common.style.SemanticStyleProfile;
import com.formacraft.common.style.SemanticStyleProfileRegistry;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;

import java.util.Random;

/**
 * v1：SemanticPart -> BlockState（使用 SemanticStyleProfileRegistry）。
 * <p>
 * 这是“构件语义化”的落地点：Component 只给形状/语义，style 决定材质。
 */
public final class SemanticBlockStatePicker {
    private SemanticBlockStatePicker() {}

    public static BlockState pick(String styleProfileId, SemanticPart part, long seed) {
        if (part == null) return Blocks.STONE.getDefaultState();
        SemanticStyleProfile profile = SemanticStyleProfileRegistry.getOrDefault(styleProfileId);
        if (profile == null) return Blocks.STONE.getDefaultState();
        PaletteRule rule = profile.getRule(part);
        if (rule == null || rule.isEmpty()) return Blocks.STONE.getDefaultState();
        BlockState s = rule.pick(new Random(seed));
        return s != null ? s : Blocks.STONE.getDefaultState();
    }
}

