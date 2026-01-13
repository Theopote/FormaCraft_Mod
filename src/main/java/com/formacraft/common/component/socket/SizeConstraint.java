package com.formacraft.common.component.socket;

/**
 * SizeConstraint（尺寸约束）v1：定义 Socket 的尺寸范围。
 * <p>
 * 作用：
 * - 快速过滤不兼容尺寸（门太大塞不进洞口）
 * - 支持一维/二维约束（LINE=长度，RECT=宽×高）
 * <p>
 * 约定：
 * - min[0] / max[0]：宽度或长度（X 轴）
 * - min[1] / max[1]：高度（Y 轴，仅 RECT 有效）
 * - POINT 和 RING 不使用此约束
 */
public final class SizeConstraint {
    public final int[] min; // [w,h] 或 [len]
    public final int[] max; // [w,h] 或 [len]

    public SizeConstraint(int[] min, int[] max) {
        this.min = min != null ? min : new int[]{0, 0};
        this.max = max != null ? max : new int[]{Integer.MAX_VALUE, Integer.MAX_VALUE};
    }

    /**
     * 检查两个约束是否兼容（有交集）。
     * <p>
     * 策略：
     * - Provider 的 [min, max] 必须与 Consumer 的 [min, max] 有重叠
     * - 例如：Provider=[2,4], Consumer=[3,5] → 兼容（交集=[3,4]）
     * - 例如：Provider=[2,3], Consumer=[4,5] → 不兼容（无交集）
     */
    public boolean compatibleWith(SizeConstraint other) {
        if (other == null) return true;

        // 检查宽度/长度（第一维）
        if (!rangeOverlap(this.min[0], this.max[0], other.min[0], other.max[0])) {
            return false;
        }

        // 检查高度（第二维，仅 RECT 有效）
        if (this.min.length > 1 && other.min.length > 1) {
            if (!rangeOverlap(this.min[1], this.max[1], other.min[1], other.max[1])) {
                return false;
            }
        }

        return true;
    }

    /**
     * 检查两个范围是否有重叠。
     */
    private boolean rangeOverlap(int min1, int max1, int min2, int max2) {
        return min1 <= max2 && min2 <= max1;
    }

    /**
     * 创建一维约束（LINE / RING）。
     */
    public static SizeConstraint line(int min, int max) {
        return new SizeConstraint(new int[]{min}, new int[]{max});
    }

    /**
     * 创建二维约束（RECT）。
     */
    public static SizeConstraint rect(int minW, int minH, int maxW, int maxH) {
        return new SizeConstraint(new int[]{minW, minH}, new int[]{maxW, maxH});
    }

    /**
     * 创建无约束（POINT）。
     */
    public static SizeConstraint none() {
        return new SizeConstraint(new int[]{0}, new int[]{Integer.MAX_VALUE});
    }

    @Override
    public String toString() {
        if (min.length == 1) {
            return String.format("[%d-%d]", min[0], max[0]);
        } else {
            return String.format("[%d-%d × %d-%d]", min[0], max[0], min[1], max[1]);
        }
    }
}
