package com.formacraft.common.palette;

import com.formacraft.common.semantic.SemanticPart;
import com.formacraft.common.semantic.SemanticRole;

import java.util.List;

/**
 * 默认调色板
 * 
 * 提供基础的调色板定义（道路 + 中世纪石墙）
 */
public final class DefaultPalettes {

    private DefaultPalettes() {}

    /**
     * 初始化默认调色板
     * 
     * 在 mod init（common）阶段调用一次
     */
    public static void bootstrap() {
        // 兜底默认调色板
        SemanticPaletteRegistry.register(new SemanticPalette("DEFAULT")
                .put(SemanticPart.PATH_BASE, List.of(
                        new WeightedBlock("minecraft:gravel", 40),
                        new WeightedBlock("minecraft:coarse_dirt", 35),
                        new WeightedBlock("minecraft:cobblestone", 25)
                ))
                .put(SemanticPart.WALL, List.of(
                        new WeightedBlock("minecraft:stone_bricks", 70),
                        new WeightedBlock("minecraft:cracked_stone_bricks", 15),
                        new WeightedBlock("minecraft:mossy_stone_bricks", 10),
                        new WeightedBlock("minecraft:cobblestone", 5)
                ))
                .put(SemanticPart.FOUNDATION, List.of(
                        new WeightedBlock("minecraft:deepslate_bricks", 70),
                        new WeightedBlock("minecraft:deepslate_tiles", 30)
                ))
                .put(SemanticPart.PATH_EDGE, List.of(
                        new WeightedBlock("minecraft:stone_brick_slab", 70),
                        new WeightedBlock("minecraft:cobblestone_slab", 30)
                ))
                // role override 示例：边缘用更"硬"的块
                .put(SemanticPart.PATH_BASE, SemanticRole.EDGE, List.of(
                        new WeightedBlock("minecraft:stone", 60),
                        new WeightedBlock("minecraft:cobblestone", 40)
                ))
        );
    }
}

