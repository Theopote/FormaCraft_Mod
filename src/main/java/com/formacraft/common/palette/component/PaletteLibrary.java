package com.formacraft.common.palette.component;

import com.formacraft.common.semantic.SemanticPart;

/**
 * PaletteLibrary（风格预设库）
 * 
 * StyleProfile → Palette 的关键桥梁
 * 
 * 核心功能：
 * - 提供不同风格的 Palette 预设
 * - 同一个 TowerGenerator → 中世纪像城堡 → 赛博朋克像数据塔 → 精灵风像树干
 */
public final class PaletteLibrary {

    private static final Palette MEDIEVAL_STONE = new Palette();
    private static final Palette CYBERPUNK = new Palette();
    private static final Palette ELVEN = new Palette();

    static {
        // ========== 中世纪石墙风格 ==========
        // 墙体主材（70% 正常石砖 + 15% 裂纹 + 10% 苔藓 + 5% 圆石）
        MEDIEVAL_STONE.add(SemanticPart.WALL, "minecraft:stone_bricks", 70);
        MEDIEVAL_STONE.add(SemanticPart.WALL, "minecraft:cracked_stone_bricks", 15);
        MEDIEVAL_STONE.add(SemanticPart.WALL, "minecraft:mossy_stone_bricks", 10);
        MEDIEVAL_STONE.add(SemanticPart.WALL, "minecraft:cobblestone", 5);

        // 墙体基础（地基）
        MEDIEVAL_STONE.add(SemanticPart.WALL_BASE, "minecraft:stone_bricks", 60);
        MEDIEVAL_STONE.add(SemanticPart.WALL_BASE, "minecraft:cracked_stone_bricks", 20);
        MEDIEVAL_STONE.add(SemanticPart.WALL_BASE, "minecraft:mossy_stone_bricks", 15);
        MEDIEVAL_STONE.add(SemanticPart.WALL_BASE, "minecraft:cobblestone", 5);

        // 墙体装饰（强调）
        MEDIEVAL_STONE.add(SemanticPart.WALL_ACCENT, "minecraft:chiseled_stone_bricks", 50);
        MEDIEVAL_STONE.add(SemanticPart.WALL_ACCENT, "minecraft:stone_bricks", 30);
        MEDIEVAL_STONE.add(SemanticPart.WALL_ACCENT, "minecraft:cracked_stone_bricks", 20);

        // 结构柱（使用 PILLAR）
        MEDIEVAL_STONE.add(SemanticPart.PILLAR, "minecraft:spruce_log", 90);
        MEDIEVAL_STONE.add(SemanticPart.PILLAR, "minecraft:stripped_spruce_log", 10);

        // 地面
        MEDIEVAL_STONE.add(SemanticPart.FLOOR, "minecraft:coarse_dirt", 40);
        MEDIEVAL_STONE.add(SemanticPart.FLOOR, "minecraft:gravel", 30);
        MEDIEVAL_STONE.add(SemanticPart.FLOOR, "minecraft:cobblestone", 30);

        // 屋顶
        MEDIEVAL_STONE.add(SemanticPart.ROOF, "minecraft:spruce_planks", 60);
        MEDIEVAL_STONE.add(SemanticPart.ROOF, "minecraft:dark_oak_planks", 30);
        MEDIEVAL_STONE.add(SemanticPart.ROOF, "minecraft:oak_planks", 10);

        // 细节（使用 DECOR）
        MEDIEVAL_STONE.add(SemanticPart.DECOR, "minecraft:stone_brick_slab", 50);
        MEDIEVAL_STONE.add(SemanticPart.DECOR, "minecraft:cobblestone_slab", 30);
        MEDIEVAL_STONE.add(SemanticPart.DECOR, "minecraft:stone_slab", 20);

        // 道路表面
        MEDIEVAL_STONE.add(SemanticPart.ROAD_SURFACE, "minecraft:gravel", 50);
        MEDIEVAL_STONE.add(SemanticPart.ROAD_SURFACE, "minecraft:cobblestone", 40);
        MEDIEVAL_STONE.add(SemanticPart.ROAD_SURFACE, "minecraft:coarse_dirt", 10);

        // 道路边缘
        MEDIEVAL_STONE.add(SemanticPart.ROAD_EDGE, "minecraft:stone", 70);
        MEDIEVAL_STONE.add(SemanticPart.ROAD_EDGE, "minecraft:cobblestone", 30);

        // ========== 赛博朋克风格 ==========
        CYBERPUNK.add(SemanticPart.WALL, "minecraft:black_concrete", 50);
        CYBERPUNK.add(SemanticPart.WALL, "minecraft:gray_concrete", 30);
        CYBERPUNK.add(SemanticPart.WALL, "minecraft:cyan_concrete", 15);
        CYBERPUNK.add(SemanticPart.WALL, "minecraft:blue_concrete", 5);

        CYBERPUNK.add(SemanticPart.WALL_BASE, "minecraft:black_concrete", 60);
        CYBERPUNK.add(SemanticPart.WALL_BASE, "minecraft:gray_concrete", 40);

        CYBERPUNK.add(SemanticPart.WALL_ACCENT, "minecraft:cyan_concrete", 70);
        CYBERPUNK.add(SemanticPart.WALL_ACCENT, "minecraft:blue_concrete", 30);

        CYBERPUNK.add(SemanticPart.PILLAR, "minecraft:iron_block", 80);
        CYBERPUNK.add(SemanticPart.PILLAR, "minecraft:gray_concrete", 20);

        CYBERPUNK.add(SemanticPart.FLOOR, "minecraft:gray_concrete", 60);
        CYBERPUNK.add(SemanticPart.FLOOR, "minecraft:black_concrete", 40);

        CYBERPUNK.add(SemanticPart.ROOF, "minecraft:black_concrete", 70);
        CYBERPUNK.add(SemanticPart.ROOF, "minecraft:gray_concrete", 30);

        CYBERPUNK.add(SemanticPart.DECOR, "minecraft:redstone_lamp", 50);
        CYBERPUNK.add(SemanticPart.DECOR, "minecraft:glowstone", 50);

        CYBERPUNK.add(SemanticPart.ROAD_SURFACE, "minecraft:gray_concrete", 80);
        CYBERPUNK.add(SemanticPart.ROAD_SURFACE, "minecraft:black_concrete", 20);

        CYBERPUNK.add(SemanticPart.ROAD_EDGE, "minecraft:cyan_concrete", 70);
        CYBERPUNK.add(SemanticPart.ROAD_EDGE, "minecraft:blue_concrete", 30);

        // ========== 精灵风格 ==========
        ELVEN.add(SemanticPart.WALL, "minecraft:birch_log", 60);
        ELVEN.add(SemanticPart.WALL, "minecraft:stripped_birch_log", 30);
        ELVEN.add(SemanticPart.WALL, "minecraft:oak_log", 10);

        ELVEN.add(SemanticPart.WALL_BASE, "minecraft:birch_log", 70);
        ELVEN.add(SemanticPart.WALL_BASE, "minecraft:stripped_birch_log", 30);

        ELVEN.add(SemanticPart.WALL_ACCENT, "minecraft:birch_planks", 50);
        ELVEN.add(SemanticPart.WALL_ACCENT, "minecraft:stripped_birch_log", 50);

        ELVEN.add(SemanticPart.PILLAR, "minecraft:birch_log", 80);
        ELVEN.add(SemanticPart.PILLAR, "minecraft:stripped_birch_log", 20);

        ELVEN.add(SemanticPart.FLOOR, "minecraft:moss_block", 40);
        ELVEN.add(SemanticPart.FLOOR, "minecraft:grass_block", 30);
        ELVEN.add(SemanticPart.FLOOR, "minecraft:birch_planks", 30);

        ELVEN.add(SemanticPart.ROOF, "minecraft:birch_planks", 60);
        ELVEN.add(SemanticPart.ROOF, "minecraft:oak_planks", 40);

        ELVEN.add(SemanticPart.DECOR, "minecraft:vine", 40);
        ELVEN.add(SemanticPart.DECOR, "minecraft:moss_block", 60);

        ELVEN.add(SemanticPart.ROAD_SURFACE, "minecraft:moss_block", 50);
        ELVEN.add(SemanticPart.ROAD_SURFACE, "minecraft:grass_path", 30);
        ELVEN.add(SemanticPart.ROAD_SURFACE, "minecraft:birch_planks", 20);

        ELVEN.add(SemanticPart.ROAD_EDGE, "minecraft:birch_log", 70);
        ELVEN.add(SemanticPart.ROAD_EDGE, "minecraft:stripped_birch_log", 30);
    }

    private PaletteLibrary() {}

    /**
     * 根据风格 ID 获取对应的 Palette
     * 
     * @param styleProfile 风格 ID（例如 "MEDIEVAL_CLASSIC", "CYBERPUNK", "ELVEN"）
     * @return Palette 实例（如果未找到，返回默认的中世纪风格）
     */
    public static Palette forStyle(String styleProfile) {
        if (styleProfile == null || styleProfile.isBlank()) {
            return MEDIEVAL_STONE; // 默认
        }

        String s = styleProfile.trim().toUpperCase();
        return switch (s) {
            case "MEDIEVAL_CLASSIC", "MEDIEVAL", "MEDIEVAL_STONE" -> MEDIEVAL_STONE;
            case "CYBERPUNK", "CYBER", "FUTURISTIC" -> CYBERPUNK;
            case "ELVEN", "ELVISH", "NATURE" -> ELVEN;
            default -> MEDIEVAL_STONE; // 默认返回中世纪风格
        };
    }
}

