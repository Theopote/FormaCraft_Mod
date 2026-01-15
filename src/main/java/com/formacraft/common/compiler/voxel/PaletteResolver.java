package com.formacraft.common.compiler.voxel;

import com.formacraft.common.semantic.SemanticPart;
import com.formacraft.common.style.PaletteRule;
import com.formacraft.common.style.SemanticStyleProfile;
import com.formacraft.common.style.SemanticStyleProfileRegistry;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;

import java.util.Random;

/**
 * PaletteResolver：将语义块（SemanticBlock）解析为实际 BlockState。
 * <p>
 * 核心思想：
 * - 使用 StylePalette（SemanticStyleProfile）进行语义到方块的映射
 * - 如果找不到调色板，回退到直接解析 block 字符串
 * - 支持随机选择（从 PaletteRule 的权重列表中）
 */
public final class PaletteResolver {
    private PaletteResolver() {}

    /**
     * 解析语义块为 BlockState
     * 
     * @param semanticBlock 语义块
     * @param styleProfileId 风格配置 ID（可选，如果为 null 则使用默认或回退）
     * @param rng 随机数生成器
     * @return BlockState（如果解析失败，返回 STONE）
     */
    public static BlockState resolve(
            SemanticBlock semanticBlock,
            String styleProfileId,
            Random rng
    ) {
        if (semanticBlock == null) {
            return Blocks.STONE.getDefaultState();
        }

        // 尝试使用 SemanticStyleProfile（如果提供了 styleProfileId）
        if (styleProfileId != null && !styleProfileId.isBlank()) {
            SemanticStyleProfile profile = SemanticStyleProfileRegistry.getOrDefault(styleProfileId);
            if (profile != null) {
                // 尝试将 semanticPart 转换为 SemanticPart 枚举
                SemanticPart part = tryParseSemanticPart(semanticBlock.semanticPart());
                if (part != null) {
                    PaletteRule rule = profile.getRule(part);
                    if (rule != null && !rule.isEmpty()) {
                        BlockState state = rule.pick(rng);
                        if (state != null) {
                            return state;
                        }
                    }
                }
            }
        }

        // 回退：如果语义块是从 BlockEntry 创建的，尝试解析 block 字符串
        // 注意：这里我们需要知道原始的 block 字符串，但 SemanticBlock 没有保存
        // 所以这个回退逻辑需要在调用方处理
        // 如果调用方提供了 fallbackBlock，可以使用 resolveFromBlockString

        // 最终回退：返回默认方块
        return Blocks.STONE.getDefaultState();
    }

    /**
     * 解析语义块为 BlockState（带回退 block 字符串）
     */
    public static BlockState resolve(
            SemanticBlock semanticBlock,
            String styleProfileId,
            String fallbackBlock,
            Random rng
    ) {
        // 先尝试使用调色板系统
        BlockState result = resolve(semanticBlock, styleProfileId, rng);
        
        // 如果结果是默认方块（STONE），且提供了 fallbackBlock，尝试解析
        if (result == Blocks.STONE.getDefaultState() && fallbackBlock != null && !fallbackBlock.isBlank()) {
            BlockState fallback = resolveFromBlockString(fallbackBlock);
            if (fallback != Blocks.STONE.getDefaultState()) {
                return fallback;
            }
        }
        
        return result;
    }

    /**
     * 解析语义块为 BlockState（使用默认风格配置）
     */
    public static BlockState resolve(SemanticBlock semanticBlock, Random rng) {
        return resolve(semanticBlock, null, rng);
    }

    /**
     * 从 block 字符串解析 BlockState（回退方法）
     * <p>
     * 当调色板系统无法解析时，直接解析 block 字符串
     * 例如：minecraft:spruce_door[facing=south,half=lower]
     */
    public static BlockState resolveFromBlockString(String blockString) {
        if (blockString == null || blockString.isBlank()) {
            return Blocks.STONE.getDefaultState();
        }

        try {
            // 分离 block ID 和状态
            String[] parts = blockString.split("\\[", 2);
            String blockId = parts[0].trim();
            
            // 解析 Block
            Identifier id = Identifier.tryParse(blockId);
            if (id == null) {
                return Blocks.STONE.getDefaultState();
            }

            var block = Registries.BLOCK.get(id);
            if (block == null || block == Blocks.AIR) {
                return Blocks.STONE.getDefaultState();
            }

            // 如果有状态字符串，尝试解析
            if (parts.length > 1 && parts[1].endsWith("]")) {
                String stateStr = parts[1].substring(0, parts[1].length() - 1);
                // 这里可以添加更复杂的状态解析，但 v1 简化处理
                // 直接返回默认状态
            }

            return block.getDefaultState();
        } catch (Exception e) {
            return Blocks.STONE.getDefaultState();
        }
    }

    /**
     * 尝试将字符串转换为 SemanticPart 枚举
     */
    private static SemanticPart tryParseSemanticPart(String semanticPart) {
        if (semanticPart == null || semanticPart.isBlank()) {
            return null;
        }

        try {
            // 尝试直接匹配枚举名称
            return SemanticPart.valueOf(semanticPart.toUpperCase());
        } catch (IllegalArgumentException e) {
            // 尝试模糊匹配
            String upper = semanticPart.toUpperCase();
            for (SemanticPart part : SemanticPart.values()) {
                if (part.name().equals(upper) || part.name().contains(upper) || upper.contains(part.name())) {
                    return part;
                }
            }
            return null;
        }
    }
}
