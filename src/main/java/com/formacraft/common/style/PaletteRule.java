package com.formacraft.common.style;

import net.minecraft.block.BlockState;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * PaletteRule（权重随机核心）
 * 
 * 用于实现"做旧 / 自然感 / 非贴图化"的关键
 */
public class PaletteRule {

    private static class Entry {
        final BlockState state;
        final int weight;

        Entry(BlockState state, int weight) {
            this.state = state;
            this.weight = weight;
        }
    }

    private final List<Entry> entries = new ArrayList<>();
    private int totalWeight = 0;

    /**
     * 添加方块状态和权重
     */
    public PaletteRule add(BlockState state, int weight) {
        if (state != null && weight > 0) {
            entries.add(new Entry(state, weight));
            totalWeight += weight;
        }
        return this;
    }

    /**
     * 从规则中随机选择一个方块状态
     */
    public BlockState pick(Random random) {
        if (entries.isEmpty() || totalWeight <= 0) {
            return null; // 返回 null，由调用者处理 fallback
        }

        int r = random.nextInt(totalWeight);
        int acc = 0;
        for (Entry e : entries) {
            acc += e.weight;
            if (r < acc) return e.state;
        }
        return entries.get(0).state; // 兜底
    }

    /**
     * 获取条目数量
     */
    public int size() {
        return entries.size();
    }

    /**
     * 是否为空
     */
    public boolean isEmpty() {
        return entries.isEmpty();
    }
}

