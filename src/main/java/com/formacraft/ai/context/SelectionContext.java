package com.formacraft.ai.context;

import com.formacraft.client.tool.SelectionTool;
import net.minecraft.util.math.BlockPos;

/**
 * 选区语义层：把 SelectionTool 的 BlockPos 变成 AI 可理解的上下文。
 */
public final class SelectionContext {
    private SelectionContext() {}

    public static boolean hasSelection() {
        return SelectionTool.INSTANCE.hasSelection();
    }

    public static BlockPos min() {
        return SelectionTool.INSTANCE.getMin();
    }

    public static BlockPos max() {
        return SelectionTool.INSTANCE.getMax();
    }

    public static int sizeX() {
        BlockPos min = min();
        BlockPos max = max();
        if (min == null || max == null) return 0;
        return max.getX() - min.getX() + 1;
    }

    public static int sizeY() {
        BlockPos min = min();
        BlockPos max = max();
        if (min == null || max == null) return 0;
        return max.getY() - min.getY() + 1;
    }

    public static int sizeZ() {
        BlockPos min = min();
        BlockPos max = max();
        if (min == null || max == null) return 0;
        return max.getZ() - min.getZ() + 1;
    }

    /**
     * 生成“选区约束”Prompt 块；没有选区则返回空字符串。
     */
    public static String toPromptBlock() {
        if (!hasSelection()) return "";
        BlockPos min = min();
        BlockPos max = max();
        if (min == null || max == null) return "";

        return """
                选区约束：
                - 区域最小坐标: (%d, %d, %d)
                - 区域最大坐标: (%d, %d, %d)
                - 区域尺寸: %d × %d × %d
                - 建筑必须完全位于该区域内，不得越界
                """.formatted(
                min.getX(), min.getY(), min.getZ(),
                max.getX(), max.getY(), max.getZ(),
                sizeX(), sizeY(), sizeZ()
        );
    }
}

