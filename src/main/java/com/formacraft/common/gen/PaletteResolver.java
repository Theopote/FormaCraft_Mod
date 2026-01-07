package com.formacraft.common.gen;

/**
 * PaletteResolver（语义 → 方块ID）
 * 
 * 核心功能：根据语义键选择方块 ID
 * 
 * 语义键示例：
 * - "road.surface" - 道路表面
 * - "road.edge" - 道路边缘
 * - "wall.base" - 墙体基础
 * - "wall.detail" - 墙体装饰
 * - "roof.tile" - 屋顶瓦片
 * - "tower.body" - 塔楼主体
 * 
 * 后续会升级为权重随机（Semantic → Palette 权重随机）
 */
public interface PaletteResolver {

    /**
     * 根据语义键选择方块 ID
     * 
     * @param semanticKey 语义键（例如 "road.surface", "wall.base"）
     * @return 方块 ID（例如 "minecraft:stone_bricks"）
     */
    String pickBlockId(String semanticKey);
}

