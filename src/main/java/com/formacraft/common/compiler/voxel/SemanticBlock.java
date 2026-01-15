package com.formacraft.common.compiler.voxel;

/**
 * SemanticBlock（语义块）：不是具体方块，而是语义描述。
 * <p>
 * 核心思想：
 * - 绝不出现 minecraft:block_id
 * - 使用语义部位（semanticPart）和调色板槽位（paletteSlot）
 * - 由 PaletteResolver 在最后阶段解析为实际 BlockState
 * <p>
 * 示例：
 * - semanticPart = "FRAME", paletteSlot = "wall.primary"
 * - semanticPart = "PANEL", paletteSlot = "wall.secondary"
 * - semanticPart = "DETAIL", paletteSlot = "decoration.accent"
 */
public record SemanticBlock(
        /**
         * 语义部位（例如 "FRAME", "PANEL", "DETAIL", "STRUCTURE", "DECORATION"）
         * 用于描述块在组件中的功能角色
         */
        String semanticPart,
        
        /**
         * 调色板槽位（例如 "wall.primary", "wall.secondary", "roof.primary"）
         * 用于从 StylePalette 中查找对应的方块列表
         */
        String paletteSlot
) {
    /**
     * 创建语义块
     */
    public static SemanticBlock of(String semanticPart, String paletteSlot) {
        return new SemanticBlock(semanticPart, paletteSlot);
    }

    /**
     * 从 ComponentDefinition.BlockEntry 创建语义块
     * 如果 BlockEntry 有 semantic，使用它；否则从 block 字符串推断
     */
    public static SemanticBlock fromBlockEntry(com.formacraft.common.component.ComponentDefinition.BlockEntry entry) {
        if (entry == null) {
            return new SemanticBlock("UNKNOWN", "default");
        }

        // 优先使用显式的 semantic
        if (entry.semantic != null) {
            String semanticName = entry.semantic.name();
            // 从 semantic 推断 paletteSlot
            String paletteSlot = inferPaletteSlot(semanticName);
            return new SemanticBlock(semanticName, paletteSlot);
        }

        // 如果没有 semantic，从 block 字符串推断
        if (entry.block != null && !entry.block.isBlank()) {
            String blockId = entry.block.split("\\[")[0]; // 提取 block ID（忽略状态）
            String semanticPart = inferSemanticPart(blockId);
            String paletteSlot = inferPaletteSlot(semanticPart);
            return new SemanticBlock(semanticPart, paletteSlot);
        }

        // 默认值
        return new SemanticBlock("UNKNOWN", "default");
    }

    /**
     * 从 BlockEntry 创建语义块，并保存原始 block 字符串（用于回退）
     * <p>
     * 注意：这个方法返回的 SemanticBlock 不包含 block 字符串信息
     * 如果需要回退到 block 字符串，需要在调用方保存 entry.block
     */
    public static SemanticBlockWithFallback fromBlockEntryWithFallback(
            com.formacraft.common.component.ComponentDefinition.BlockEntry entry
    ) {
        SemanticBlock semantic = fromBlockEntry(entry);
        String fallbackBlock = entry != null ? entry.block : null;
        return new SemanticBlockWithFallback(semantic, fallbackBlock);
    }

    /**
     * 带回退信息的语义块
     */
    public record SemanticBlockWithFallback(
            SemanticBlock semantic,
            String fallbackBlock
    ) {}

    /**
     * 从 block ID 推断语义部位
     */
    private static String inferSemanticPart(String blockId) {
        if (blockId == null || blockId.isBlank()) {
            return "UNKNOWN";
        }
        String lower = blockId.toLowerCase();
        
        // 根据常见方块类型推断
        if (lower.contains("door") || lower.contains("trapdoor")) {
            return "DOOR";
        }
        if (lower.contains("window") || lower.contains("glass")) {
            return "WINDOW";
        }
        if (lower.contains("stairs") || lower.contains("slab")) {
            return "STRUCTURE";
        }
        if (lower.contains("fence") || lower.contains("wall")) {
            return "FRAME";
        }
        if (lower.contains("button") || lower.contains("lever") || lower.contains("torch")) {
            return "DETAIL";
        }
        
        // 默认推断为结构块
        return "STRUCTURE";
    }

    /**
     * 从语义部位推断调色板槽位
     */
    private static String inferPaletteSlot(String semanticPart) {
        if (semanticPart == null || semanticPart.isBlank()) {
            return "default";
        }
        String upper = semanticPart.toUpperCase();
        
        // 根据语义部位推断调色板槽位
        if (upper.contains("FRAME") || upper.contains("STRUCTURE")) {
            return "wall.primary";
        }
        if (upper.contains("PANEL") || upper.contains("FILL")) {
            return "wall.secondary";
        }
        if (upper.contains("ROOF")) {
            return "roof.primary";
        }
        if (upper.contains("DECORATION") || upper.contains("DETAIL")) {
            return "decoration.accent";
        }
        
        return "default";
    }
}
