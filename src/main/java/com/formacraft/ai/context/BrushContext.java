package com.formacraft.ai.context;

import com.formacraft.client.tool.BrushTool;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.List;

/**
 * 笔刷工具语义层：把 BrushTool 的选中区域变成 AI 可理解的上下文。
 */
public final class BrushContext {
    private BrushContext() {}

    public static boolean hasBrushSelection() {
        return BrushTool.INSTANCE.getSelectedCount() > 0;
    }

    public static int getSelectedCount() {
        return BrushTool.INSTANCE.getSelectedCount();
    }

    /**
     * 获取笔刷选中的边界（AABB）
     * 
     * @return [minX, minY, minZ, maxX, maxY, maxZ]，如果没有选中则返回 null
     */
    public static int[] getBounds() {
        if (!hasBrushSelection()) {
            return null;
        }

        LongOpenHashSet selected = BrushTool.INSTANCE.getSelectedSet();
        if (selected == null || selected.isEmpty()) {
            return null;
        }

        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxY = Integer.MIN_VALUE;
        int maxZ = Integer.MIN_VALUE;

        boolean hasAny = false;
        for (long packed : selected) {
            BlockPos pos = BlockPos.fromLong(packed);
            if (pos == null) continue;
            
            minX = Math.min(minX, pos.getX());
            minY = Math.min(minY, pos.getY());
            minZ = Math.min(minZ, pos.getZ());
            maxX = Math.max(maxX, pos.getX());
            maxY = Math.max(maxY, pos.getY());
            maxZ = Math.max(maxZ, pos.getZ());
            hasAny = true;
        }

        if (!hasAny) {
            return null;
        }

        return new int[]{minX, minY, minZ, maxX, maxY, maxZ};
    }

    /**
     * 获取所有选中的方块位置（用于更精确的约束）
     */
    public static List<BlockPos> getSelectedPositions() {
        List<BlockPos> result = new ArrayList<>();
        if (!hasBrushSelection()) {
            return result;
        }

        LongOpenHashSet selected = BrushTool.INSTANCE.getSelectedSet();
        if (selected == null || selected.isEmpty()) {
            return result;
        }

        for (long packed : selected) {
            BlockPos pos = BlockPos.fromLong(packed);
            if (pos != null) {
                result.add(pos);
            }
        }

        return result;
    }

    /**
     * 生成"笔刷选中区域约束"Prompt 块；没有选中则返回空字符串。
     */
    public static String toPromptBlock() {
        if (!hasBrushSelection()) {
            return "";
        }

        int[] bounds = getBounds();
        if (bounds == null) {
            return "";
        }

        int minX = bounds[0];
        int minY = bounds[1];
        int minZ = bounds[2];
        int maxX = bounds[3];
        int maxY = bounds[4];
        int maxZ = bounds[5];

        int sizeX = maxX - minX + 1;
        int sizeZ = maxZ - minZ + 1;
        int count = getSelectedCount();

        return """
                笔刷选中区域约束：
                - 已选中的地表方块数量: %d
                - 区域边界（AABB）:
                  * 最小坐标: (%d, %d, %d)
                  * 最大坐标: (%d, %d, %d)
                - 区域尺寸（X×Z）: %d × %d
                - 建筑应该生成在笔刷选中的区域内，优先考虑与选中方块的重叠
                - 笔刷选中的是地表一层方块，建筑应该基于这些位置生成
                """.formatted(
                count,
                minX, minY, minZ,
                maxX, maxY, maxZ,
                sizeX, sizeZ
        );
    }
}
