package com.formacraft.common.assembly;

import com.formacraft.common.component.query.ComponentQuery;
import com.formacraft.common.component.socket.SocketType;

import java.util.ArrayList;
import java.util.List;

/**
 * AssemblyPlanner（装配规划器）：把规则翻译成 ComponentQuery（给 Ranker 用）。
 * <p>
 * 核心思想：
 * - 多数情况下 AI 会自动选构件
 * - 但当 LLM 没说清时，系统可用规则补齐
 * - 或反过来：LLM 输出 Program，系统用规则做"纠偏"
 */
public final class AssemblyPlanner {
    private AssemblyPlanner() {}

    /**
     * 将规则转换为 ComponentQuery 列表
     * 
     * @param rules 规则列表
     * @param socketType Socket 类型
     * @param styleProfile 风格配置
     * @param materialTone 材质色调
     * @return ComponentQuery 列表
     */
    public static List<ComponentQuery> toQueries(
            List<SkeletonComponentRules.Rule> rules,
            SocketType socketType,
            String styleProfile,
            String materialTone
    ) {
        List<ComponentQuery> out = new ArrayList<>();

        for (var r : rules) {
            if (r.socketType != socketType) continue;

            ComponentQuery q = new ComponentQuery();
            q.semantic.role = r.role;
            q.semantic.tags.addAll(r.tags);
            q.semantic.importance.add("role");
            q.semantic.importance.add("placement");

            // context 由 socketType 推导
            q.context.placement = placementFromSocket(socketType);
            q.context.side = "exterior";
            q.context.heightLevel = "any";
            q.context.edgeCondition = "any";

            // geometry: opening socket 强制 requiresOpening
            q.geometry.requiresOpening = (socketType == SocketType.WALL_OPENING);
            q.geometry.scalable = true;
            q.geometry.tolerance = 1;

            // style
            q.style.styleProfile = styleProfile;
            q.style.materialTone = materialTone;

            // constraints
            q.constraints.mustHave.addAll(r.tags);
            q.constraints.forbiddenTags.add("modern");

            // usage
            q.usageHint.frequency = r.required ? "primary" : "secondary";
            q.usageHint.visibility = "medium";

            out.add(q);
        }

        return out;
    }

    /**
     * 从 SocketType 推导 placement 字符串
     */
    private static String placementFromSocket(SocketType s) {
        return switch (s) {
            case WALL_SURFACE, WALL_OPENING -> "wall";
            case EDGE_OUTER -> "edge";
            case ROOF_SLOPE, ROOF_RIDGE -> "roof";
            case FLOOR_SURFACE -> "ground";
            default -> "interior";
        };
    }
}
