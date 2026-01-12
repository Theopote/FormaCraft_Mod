package com.formacraft.common.style.presets;

import com.formacraft.common.geometry.modifier.OverhangModifier;
import com.formacraft.common.geometry.modifier.TaperUpModifier;
import com.formacraft.common.geometry.modifier.ThickWallModifier;
import com.formacraft.common.semantic.SemanticPart;
import com.formacraft.common.style.PaletteRule;
import com.formacraft.common.style.SemanticStyleProfile;

import net.minecraft.block.Blocks;

/**
 * 示例风格：中世纪城堡（落地版）
 */
public class MedievalCastleProfile {

    public static SemanticStyleProfile create() {
        SemanticStyleProfile style = new SemanticStyleProfile("MEDIEVAL_CASTLE");

        // 墙体主体
        style.bind(SemanticPart.WALL,
                new PaletteRule()
                        .add(Blocks.STONE_BRICKS.getDefaultState(), 70)
                        .add(Blocks.CRACKED_STONE_BRICKS.getDefaultState(), 15)
                        .add(Blocks.MOSSY_STONE_BRICKS.getDefaultState(), 10)
                        .add(Blocks.COBBLESTONE.getDefaultState(), 5)
        );

        // 塔楼墙体
        style.bind(SemanticPart.TOWER_WALL,
                new PaletteRule()
                        .add(Blocks.STONE_BRICKS.getDefaultState(), 75)
                        .add(Blocks.CRACKED_STONE_BRICKS.getDefaultState(), 12)
                        .add(Blocks.MOSSY_STONE_BRICKS.getDefaultState(), 8)
                        .add(Blocks.COBBLESTONE.getDefaultState(), 5)
        );

        // 基础/地基
        style.bind(SemanticPart.FOUNDATION,
                new PaletteRule()
                        .add(Blocks.DEEPSLATE_BRICKS.getDefaultState(), 80)
                        .add(Blocks.DEEPSLATE_TILES.getDefaultState(), 20)
        );

        // 台阶
        style.bind(SemanticPart.STAIR_STEP,
                new PaletteRule()
                        .add(Blocks.STONE_BRICK_STAIRS.getDefaultState(), 90)
                        .add(Blocks.COBBLESTONE_STAIRS.getDefaultState(), 10)
        );

        // 屋顶
        style.bind(SemanticPart.ROOF,
                new PaletteRule()
                        .add(Blocks.DARK_OAK_STAIRS.getDefaultState(), 80)
                        .add(Blocks.SPRUCE_STAIRS.getDefaultState(), 20)
        );

        // 屋顶表面
        style.bind(SemanticPart.ROOF_SURFACE,
                new PaletteRule()
                        .add(Blocks.DARK_OAK_PLANKS.getDefaultState(), 70)
                        .add(Blocks.SPRUCE_PLANKS.getDefaultState(), 30)
        );

        // 中庭地面
        style.bind(SemanticPart.COURTYARD_FLOOR,
                new PaletteRule()
                        .add(Blocks.STONE_BRICKS.getDefaultState(), 60)
                        .add(Blocks.COBBLESTONE.getDefaultState(), 30)
                        .add(Blocks.MOSSY_COBBLESTONE.getDefaultState(), 10)
        );

        // 步道地面
        style.bind(SemanticPart.WALKWAY_FLOOR,
                new PaletteRule()
                        .add(Blocks.STONE_BRICKS.getDefaultState(), 80)
                        .add(Blocks.COBBLESTONE.getDefaultState(), 20)
        );

        // 路径基础
        style.bind(SemanticPart.PATH_BASE,
                new PaletteRule()
                        .add(Blocks.GRAVEL.getDefaultState(), 40)
                        .add(Blocks.COARSE_DIRT.getDefaultState(), 35)
                        .add(Blocks.COBBLESTONE.getDefaultState(), 25)
        );

        // 光源
        style.bind(SemanticPart.LIGHT,
                new PaletteRule()
                        .add(Blocks.LANTERN.getDefaultState(), 100)
        );

        // 门洞（用于 Component AUTO：门/活板门）
        style.bind(SemanticPart.DOORWAY,
                new PaletteRule()
                        .add(Blocks.SPRUCE_DOOR.getDefaultState(), 70)
                        .add(Blocks.OAK_DOOR.getDefaultState(), 30)
        );

        // 窗（用于 Component AUTO：玻璃/玻璃板）
        style.bind(SemanticPart.WINDOW,
                new PaletteRule()
                        .add(Blocks.GLASS_PANE.getDefaultState(), 80)
                        .add(Blocks.WHITE_STAINED_GLASS_PANE.getDefaultState(), 20)
        );

        // 栏杆（用于 Component AUTO：栅栏/铁栏）
        style.bind(SemanticPart.RAILING,
                new PaletteRule()
                        .add(Blocks.IRON_BARS.getDefaultState(), 80)
                        .add(Blocks.SPRUCE_FENCE.getDefaultState(), 20)
        );

        // 柱/梁（用于 Component AUTO：木头类 rotated_pillar）
        style.bind(SemanticPart.PILLAR,
                new PaletteRule()
                        .add(Blocks.SPRUCE_LOG.getDefaultState(), 70)
                        .add(Blocks.STRIPPED_SPRUCE_LOG.getDefaultState(), 30)
        );
        style.bind(SemanticPart.BEAM,
                new PaletteRule()
                        .add(Blocks.SPRUCE_LOG.getDefaultState(), 70)
                        .add(Blocks.STRIPPED_SPRUCE_LOG.getDefaultState(), 30)
        );

        // ========== 几何修饰 ==========
        
        // 墙体：厚墙（3格）
        style.bindGeometry(SemanticPart.WALL, new ThickWallModifier(3));
        style.bindGeometry(SemanticPart.TOWER_WALL, new ThickWallModifier(3));
        
        // 屋顶：出檐（1格）
        style.bindGeometry(SemanticPart.ROOF, new OverhangModifier(1));
        style.bindGeometry(SemanticPart.ROOF_SURFACE, new OverhangModifier(1));
        
        // 塔楼：向上收分（18格高）
        style.bindGeometry(SemanticPart.TOWER_WALL, new TaperUpModifier(18));

        return style;
    }
}

