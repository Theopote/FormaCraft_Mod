package com.formacraft.common.llm.compiler;

import com.formacraft.common.llm.dto.StructuralSkeleton;
import com.formacraft.common.skeleton.ExecutableSkeletonPlan;

import java.util.*;

/**
 * SkeletonGraphBuilder（骨架关系图构建器）
 * <p>
 * 从 skeletons 和 structural 构建 SkeletonGraph
 */
public final class SkeletonGraphBuilder {

    private SkeletonGraphBuilder() {}

    /**
     * 构建 SkeletonGraph
     * 
     * @param skeletons ExecutableSkeletonPlan 列表
     * @param structural StructuralSkeleton（可选，用于获取 zone 和角色信息）
     * @return SkeletonGraph
     */
    public static SkeletonGraph build(
            List<ExecutableSkeletonPlan> skeletons,
            StructuralSkeleton structural
    ) {
        SkeletonGraph graph = new SkeletonGraph();

        if (skeletons == null || skeletons.isEmpty()) {
            return graph;
        }

        // 构建索引：wall_kind → skeletons
        Map<String, List<ExecutableSkeletonPlan>> skeletonsByKind = new HashMap<>();
        for (ExecutableSkeletonPlan skeleton : skeletons) {
            String wallKind = skeleton.get("wall_kind", "UNKNOWN");
            skeletonsByKind.computeIfAbsent(wallKind, k -> new ArrayList<>()).add(skeleton);
        }

        // 设置角色
        for (ExecutableSkeletonPlan skeleton : skeletons) {
            String wallKind = skeleton.get("wall_kind", "UNKNOWN");
            SkeletonGraph.SkeletonRole role = determineRole(wallKind);
            graph.setRole(skeleton, role);
        }

        // 设置 zone 映射
        if (structural != null && structural.walls != null) {
            // v1：从 skeletons 的 wall_zones 参数提取 zone 信息
            // 未来：可以更精确地从 wall.id 和 wall.zoneIds 匹配
            for (ExecutableSkeletonPlan skeleton : skeletons) {
                Object wallZonesObj = skeleton.get("wall_zones", List.of());
                List<String> wallZones = extractStringList(wallZonesObj);
                if (wallZones != null && !wallZones.isEmpty()) {
                    // 使用第一个 zone 作为主要 zone
                    graph.setZone(skeleton, wallZones.getFirst());
                }
            }
        }

        // 构建邻接关系（基于 zone 关系）
        // v1 简化：如果两个 skeleton 有共同的 zone，则认为它们是邻接的
        for (int i = 0; i < skeletons.size(); i++) {
            ExecutableSkeletonPlan s1 = skeletons.get(i);
            Object zones1Obj = s1.get("wall_zones", List.of());
            List<String> zones1 = extractStringList(zones1Obj);

            for (int j = i + 1; j < skeletons.size(); j++) {
                ExecutableSkeletonPlan s2 = skeletons.get(j);
                Object zones2Obj = s2.get("wall_zones", List.of());
                List<String> zones2 = extractStringList(zones2Obj);

                // 如果有共同的 zone，则添加邻接关系
                if (zones1 != null && zones2 != null) {
                    for (String zone : zones1) {
                        if (zones2.contains(zone)) {
                            graph.addAdjacency(s1, s2);
                            break;
                        }
                    }
                }
            }
        }

        return graph;
    }

    /**
     * 提取字符串列表（安全类型转换）
     */
    @SuppressWarnings("unchecked")
    private static List<String> extractStringList(Object obj) {
        if (obj instanceof List<?> list) {
            if (!list.isEmpty() && list.getFirst() instanceof String) {
                return (List<String>) list;
            }
        }
        return null;
    }

    /**
     * 根据 wall_kind 决定角色
     */
    private static SkeletonGraph.SkeletonRole determineRole(String wallKind) {
        if (wallKind == null) {
            return SkeletonGraph.SkeletonRole.UNKNOWN;
        }

        return switch (wallKind.toUpperCase()) {
            case "EXTERNAL" -> SkeletonGraph.SkeletonRole.EXTERNAL;
            case "INTERNAL" -> SkeletonGraph.SkeletonRole.INTERNAL;
            case "COURTYARD" -> SkeletonGraph.SkeletonRole.COURTYARD;
            default -> SkeletonGraph.SkeletonRole.UNKNOWN;
        };
    }
}
