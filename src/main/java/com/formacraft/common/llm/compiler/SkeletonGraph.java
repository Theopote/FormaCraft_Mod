package com.formacraft.common.llm.compiler;

import com.formacraft.server.skeleton.gen.ExecutableSkeletonPlan;

import java.util.*;

/**
 * SkeletonGraph（骨架关系图）
 * <p>
 * 用途：
 * - AI 后处理修正
 * - UI 高亮（外墙 / 内墙 / 庭院墙）
 * - 构件放置 bias
 * - Debug & replay
 * <p>
 * 包含：
 * - adjacency: 哪些 skeleton 是相邻的
 * - zoneMapping: skeleton 属于哪个 zone
 * - roles: skeleton 的角色（EXTERNAL / INTERNAL / COURTYARD）
 */
public class SkeletonGraph {
    /** 邻接关系：skeleton → 相邻的 skeletons */
    private final Map<ExecutableSkeletonPlan, Set<ExecutableSkeletonPlan>> adjacency;

    /** Zone 映射：skeleton → zone id */
    private final Map<ExecutableSkeletonPlan, String> zoneMapping;

    /** 角色映射：skeleton → 角色 */
    private final Map<ExecutableSkeletonPlan, SkeletonRole> roles;

    public SkeletonGraph() {
        this.adjacency = new HashMap<>();
        this.zoneMapping = new HashMap<>();
        this.roles = new HashMap<>();
    }

    public SkeletonGraph(
            Map<ExecutableSkeletonPlan, Set<ExecutableSkeletonPlan>> adjacency,
            Map<ExecutableSkeletonPlan, String> zoneMapping,
            Map<ExecutableSkeletonPlan, SkeletonRole> roles
    ) {
        this.adjacency = adjacency != null ? new HashMap<>(adjacency) : new HashMap<>();
        this.zoneMapping = zoneMapping != null ? new HashMap<>(zoneMapping) : new HashMap<>();
        this.roles = roles != null ? new HashMap<>(roles) : new HashMap<>();
    }

    /**
     * 获取 skeleton 的邻接关系
     */
    public Set<ExecutableSkeletonPlan> getAdjacent(ExecutableSkeletonPlan skeleton) {
        return adjacency.getOrDefault(skeleton, Collections.emptySet());
    }

    /**
     * 获取 skeleton 所属的 zone
     */
    public String getZone(ExecutableSkeletonPlan skeleton) {
        return zoneMapping.get(skeleton);
    }

    /**
     * 获取 skeleton 的角色
     */
    public SkeletonRole getRole(ExecutableSkeletonPlan skeleton) {
        return roles.getOrDefault(skeleton, SkeletonRole.UNKNOWN);
    }

    /**
     * 添加邻接关系
     */
    public void addAdjacency(ExecutableSkeletonPlan a, ExecutableSkeletonPlan b) {
        adjacency.computeIfAbsent(a, k -> new HashSet<>()).add(b);
        adjacency.computeIfAbsent(b, k -> new HashSet<>()).add(a);
    }

    /**
     * 设置 zone 映射
     */
    public void setZone(ExecutableSkeletonPlan skeleton, String zoneId) {
        if (skeleton != null && zoneId != null) {
            zoneMapping.put(skeleton, zoneId);
        }
    }

    /**
     * 设置角色
     */
    public void setRole(ExecutableSkeletonPlan skeleton, SkeletonRole role) {
        if (skeleton != null && role != null) {
            roles.put(skeleton, role);
        }
    }

    /**
     * 获取所有 skeletons
     */
    public Set<ExecutableSkeletonPlan> getAllSkeletons() {
        Set<ExecutableSkeletonPlan> all = new HashSet<>();
        all.addAll(adjacency.keySet());
        all.addAll(zoneMapping.keySet());
        all.addAll(roles.keySet());
        return all;
    }

    /**
     * Skeleton 角色枚举
     */
    public enum SkeletonRole {
        EXTERNAL,      // 外墙
        INTERNAL,      // 内墙
        COURTYARD,     // 庭院墙
        FLOOR,         // 地面
        ROOF,          // 屋顶
        UNKNOWN        // 未知
    }
}
