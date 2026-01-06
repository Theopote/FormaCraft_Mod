package com.formacraft.common.palette;

import java.util.List;
import java.util.Random;

/**
 * 加权随机选择器
 */
public final class WeightedPicker {
    private WeightedPicker() {}

    /**
     * 从加权列表中随机选择一个方块 ID
     * 
     * @param random 随机数生成器
     * @param list 加权方块列表
     * @param fallback 如果列表为空或无效，返回的默认值
     * @return 选中的方块 ID
     */
    public static String pick(Random random, List<WeightedBlock> list, String fallback) {
        if (list == null || list.isEmpty()) return fallback;

        int total = 0;
        for (WeightedBlock wb : list) {
            total += Math.max(0, wb.weight());
        }
        if (total <= 0) return fallback;

        int r = random.nextInt(total);
        int acc = 0;
        for (WeightedBlock wb : list) {
            acc += Math.max(0, wb.weight());
            if (r < acc) return wb.blockId();
        }
        return fallback;
    }
}

