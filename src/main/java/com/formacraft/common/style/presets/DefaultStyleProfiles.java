package com.formacraft.common.style.presets;

import com.formacraft.common.semantic.SemanticPart;
import com.formacraft.common.style.PaletteRule;
import com.formacraft.common.style.SemanticStyleProfile;
import com.formacraft.common.style.SemanticStyleProfileRegistry;
import net.minecraft.block.Blocks;

/**
 * 默认风格配置
 * <p>
 * 初始化所有预设风格
 */
public final class DefaultStyleProfiles {

    private DefaultStyleProfiles() {}

    /**
     * 初始化默认风格配置
     * <p>
     * 在 mod 初始化时调用
     */
    public static void bootstrap() {
        // 注册中世纪城堡风格
        SemanticStyleProfileRegistry.register(MedievalCastleProfile.create());

        // 创建并注册默认风格（作为 fallback）
        SemanticStyleProfile defaultProfile = createDefaultProfile();
        SemanticStyleProfileRegistry.register(defaultProfile);
    }

    /**
     * 创建默认风格配置（作为 fallback）
     */
    private static SemanticStyleProfile createDefaultProfile() {
        SemanticStyleProfile style = new SemanticStyleProfile("DEFAULT");

        // 使用 DefaultPalettes 中的基础映射作为默认风格
        // 这里提供一个基础版本，实际使用时可以从配置文件加载
        // 注意：默认风格不包含几何修饰器，只提供基本的材质映射
        // 如果需要几何修饰，应该使用具体的风格配置（如 MEDIEVAL_CASTLE）

        // ===== 最小可用的“语义换皮”映射（避免 AUTO 换皮后全变石头）=====
        style.bind(SemanticPart.FOUNDATION, new PaletteRule()
                .add(Blocks.STONE_BRICKS.getDefaultState(), 70)
                .add(Blocks.COBBLESTONE.getDefaultState(), 30)
        );
        style.bind(SemanticPart.WALL, new PaletteRule()
                .add(Blocks.STONE_BRICKS.getDefaultState(), 60)
                .add(Blocks.SMOOTH_STONE.getDefaultState(), 25)
                .add(Blocks.COBBLESTONE.getDefaultState(), 15)
        );
        style.bind(SemanticPart.PILLAR, new PaletteRule()
                .add(Blocks.OAK_LOG.getDefaultState(), 60)
                .add(Blocks.SPRUCE_LOG.getDefaultState(), 40)
        );
        style.bind(SemanticPart.BEAM, new PaletteRule()
                .add(Blocks.OAK_LOG.getDefaultState(), 60)
                .add(Blocks.SPRUCE_LOG.getDefaultState(), 40)
        );
        style.bind(SemanticPart.FLOOR, new PaletteRule()
                .add(Blocks.OAK_PLANKS.getDefaultState(), 60)
                .add(Blocks.SPRUCE_PLANKS.getDefaultState(), 40)
        );
        style.bind(SemanticPart.ROOF, new PaletteRule()
                .add(Blocks.SPRUCE_STAIRS.getDefaultState(), 60)
                .add(Blocks.DARK_OAK_STAIRS.getDefaultState(), 40)
        );
        style.bind(SemanticPart.ROOF_SURFACE, new PaletteRule()
                .add(Blocks.SPRUCE_PLANKS.getDefaultState(), 60)
                .add(Blocks.DARK_OAK_PLANKS.getDefaultState(), 40)
        );
        style.bind(SemanticPart.WINDOW, new PaletteRule()
                .add(Blocks.GLASS_PANE.getDefaultState(), 85)
                .add(Blocks.WHITE_STAINED_GLASS_PANE.getDefaultState(), 15)
        );
        style.bind(SemanticPart.DOORWAY, new PaletteRule()
                .add(Blocks.OAK_DOOR.getDefaultState(), 60)
                .add(Blocks.SPRUCE_DOOR.getDefaultState(), 40)
        );
        style.bind(SemanticPart.RAILING, new PaletteRule()
                .add(Blocks.IRON_BARS.getDefaultState(), 70)
                .add(Blocks.OAK_FENCE.getDefaultState(), 30)
        );
        style.bind(SemanticPart.LIGHT, new PaletteRule()
                .add(Blocks.LANTERN.getDefaultState(), 100)
        );
        style.bind(SemanticPart.STAIR_STEP, new PaletteRule()
                .add(Blocks.OAK_STAIRS.getDefaultState(), 50)
                .add(Blocks.STONE_BRICK_STAIRS.getDefaultState(), 50)
        );

        return style;
    }
}

