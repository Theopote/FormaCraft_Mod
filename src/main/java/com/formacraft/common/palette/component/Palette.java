package com.formacraft.common.palette.component;

import com.formacraft.common.semantic.SemanticPart;

import java.util.*;

/**
 * Palette（一个完整风格的"基因表达"）
 * 
 * 核心功能：根据 SemanticPart 权重随机选择方块
 * 
 * 设计原则：
 * - Generator 只画几何
 * - Palette 决定"用什么块 + 概率"
 * - StyleProfile → Palette → Semantic Part
 */
public class Palette {

    private final Map<SemanticPart, List<PaletteBlock>> table = new EnumMap<>(SemanticPart.class);
    private final Random random = new Random();

    /**
     * 添加方块到指定 SemanticPart
     */
    public void add(SemanticPart part, String blockId, int weight) {
        if (part == null || blockId == null || blockId.isBlank() || weight <= 0) {
            return;
        }
        table.computeIfAbsent(part, k -> new ArrayList<>())
             .add(new PaletteBlock(blockId, weight));
    }

    /**
     * 从指定 SemanticPart 中权重随机选择一个方块 ID
     * 
     * @param part 语义部位
     * @return 方块 ID（如果 part 不存在，返回 "minecraft:stone"）
     */
    public String pick(SemanticPart part) {
        List<PaletteBlock> list = table.get(part);
        if (list == null || list.isEmpty()) {
            return "minecraft:stone"; // fallback
        }

        // 计算总权重
        int total = 0;
        for (PaletteBlock b : list) {
            total += b.weight();
        }

        if (total <= 0) {
            return list.get(0).blockId(); // 兜底
        }

        // 权重随机选择
        int r = random.nextInt(total);
        int acc = 0;
        for (PaletteBlock b : list) {
            acc += b.weight();
            if (r < acc) {
                return b.blockId();
            }
        }
        
        return list.get(0).blockId(); // 兜底
    }

    /**
     * 使用指定的 Random 实例进行选择（用于可复现的随机）
     */
    public String pick(SemanticPart part, Random random) {
        List<PaletteBlock> list = table.get(part);
        if (list == null || list.isEmpty()) {
            return "minecraft:stone"; // fallback
        }

        int total = 0;
        for (PaletteBlock b : list) {
            total += b.weight();
        }

        if (total <= 0) {
            return list.get(0).blockId(); // 兜底
        }

        int r = random.nextInt(total);
        int acc = 0;
        for (PaletteBlock b : list) {
            acc += b.weight();
            if (r < acc) {
                return b.blockId();
            }
        }
        
        return list.get(0).blockId(); // 兜底
    }

    /**
     * 检查是否有指定 SemanticPart 的映射
     */
    public boolean has(SemanticPart part) {
        List<PaletteBlock> list = table.get(part);
        return list != null && !list.isEmpty();
    }
}

