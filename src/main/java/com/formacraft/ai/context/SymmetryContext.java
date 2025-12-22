package com.formacraft.ai.context;

import com.formacraft.client.tool.SelectionTool;
import com.formacraft.client.tool.SymmetryMode;
import com.formacraft.client.tool.SymmetryTool;
import net.minecraft.util.math.BlockPos;

/**
 * 对称/镜像约束语义层：把 SymmetryTool 的约束拼接进 Prompt。
 */
public final class SymmetryContext {
    private SymmetryContext() {}

    public static SymmetryMode mode() {
        return SymmetryTool.INSTANCE.getMode();
    }

    public static boolean enabled() {
        return mode() != SymmetryMode.NONE;
    }

    public static String toPromptBlock() {
        if (!enabled()) return "";

        SymmetryMode m = mode();
        StringBuilder sb = new StringBuilder();
        sb.append("对称/镜像约束：\n");

        switch (m) {
            case MIRROR_X -> {
                sb.append("- 模式：沿 X 方向镜像（关于平面 x = 常量 对称）\n");
                appendDefaultAxisHint(sb);
            }
            case MIRROR_Z -> {
                sb.append("- 模式：沿 Z 方向镜像（关于平面 z = 常量 对称）\n");
                appendDefaultAxisHint(sb);
            }
            case BOTH -> {
                sb.append("- 模式：双向镜像（同时关于 x=常量 与 z=常量 对称）\n");
                appendDefaultAxisHint(sb);
            }
            case CUSTOM_AXIS -> {
                sb.append("- 模式：自定义轴线（XZ 平面）\n");
                BlockPos a = SymmetryTool.INSTANCE.getAxisA();
                BlockPos b = SymmetryTool.INSTANCE.getAxisB();
                if (a != null && b != null) {
                    sb.append("- 轴线：From (").append(a.getX()).append(",").append(a.getZ())
                            .append(") to (").append(b.getX()).append(",").append(b.getZ()).append(")\n");
                } else {
                    sb.append("- 轴线：未指定（请在世界中用两点标注）\n");
                }
            }
            default -> {
                // NONE already handled
            }
        }

        sb.append("- 所有主要体量与关键元素应遵守对称关系\n");
        return sb.toString();
    }

    private static void appendDefaultAxisHint(StringBuilder sb) {
        // 以选区中心做默认基准（如果没有选区则只给语义提示）
        if (SelectionTool.INSTANCE.hasSelection()) {
            BlockPos min = SelectionTool.INSTANCE.getMin();
            BlockPos max = SelectionTool.INSTANCE.getMax();
            if (min != null && max != null) {
                double cx = (min.getX() + max.getX() + 1) / 2.0;
                double cz = (min.getZ() + max.getZ() + 1) / 2.0;
                sb.append("- 默认基准：选区中心 (x=").append(cx).append(", z=").append(cz).append(")\n");
                return;
            }
        }
        sb.append("- 默认基准：以整体布局中心作为对称基准\n");
    }
}


