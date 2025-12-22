package com.formacraft.ai.context;

import com.formacraft.client.tool.ProtectedZoneTool;
import com.formacraft.common.model.constraint.ProtectedZone;

import java.util.List;

/**
 * 禁区/保护区语义层：把 ProtectedZoneTool 的区域拼接进 Prompt。
 */
public final class ProtectedZoneContext {
    private ProtectedZoneContext() {}

    public static List<ProtectedZone> zones() {
        return ProtectedZoneTool.INSTANCE.getZones();
    }

    public static boolean hasZones() {
        return ProtectedZoneTool.INSTANCE.hasZones();
    }

    public static String toPromptBlock() {
        if (!hasZones()) return "";
        StringBuilder sb = new StringBuilder();
        sb.append("禁区/保护区（强约束）：\n");
        sb.append("- 以下区域内的方块绝对不能被修改（即使你认为需要）\n");
        int i = 1;
        for (ProtectedZone z : zones()) {
            if (z == null || z.min() == null || z.max() == null) continue;
            ProtectedZone n = z.normalized();
            sb.append("  Zone ").append(i++).append(": from (")
                    .append(n.min().getX()).append(",").append(n.min().getY()).append(",").append(n.min().getZ())
                    .append(") to (")
                    .append(n.max().getX()).append(",").append(n.max().getY()).append(",").append(n.max().getZ())
                    .append(")\n");
        }
        return sb.toString();
    }
}


