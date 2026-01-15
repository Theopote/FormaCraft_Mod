package com.formacraft.common.assembly;

import com.formacraft.common.component.socket.SocketType;

import java.util.*;

/**
 * SkeletonComponentRules（骨架上的"构件长出来规则"）。
 * <p>
 * 核心思想：
 * - 这层解决"城墙上默认长什么""塔楼上默认长什么""道路边放什么"
 * - 这不是 AI 选构件，而是给 AI 一个"默认装配语境"
 * - 让 LLM 能更稳定地产生结构化 Program，并且系统也能在缺省情况下补齐细节
 */
public final class SkeletonComponentRules {
    /**
     * 一个规则：在某类 Socket 上，优先生成哪些 ComponentQuery
     */
    public static final class Rule {
        public SocketType socketType;
        public String role;                  // door/window/railing/ornament
        public Set<String> tags = new HashSet<>();
        public double weight = 1.0;         // 在多规则下抽样权重
        public boolean required = false;    // 是否必须
        public int minCount = 0;
        public int maxCount = 999;

        public Rule socket(SocketType t) { this.socketType = t; return this; }
        public Rule role(String r) { this.role = r; return this; }
        public Rule tag(String t) { this.tags.add(t); return this; }
        public Rule weight(double w) { this.weight = w; return this; }
        public Rule required(boolean v) { this.required = v; return this; }
        public Rule range(int min, int max) { this.minCount = min; this.maxCount = max; return this; }
    }

    /** skeletonKind -> rules */
    private final Map<String, List<Rule>> map = new HashMap<>();

    /**
     * 添加规则
     */
    public void put(String skeletonKind, Rule r) {
        map.computeIfAbsent(skeletonKind, k -> new ArrayList<>()).add(r);
    }

    /**
     * 获取规则列表
     */
    public List<Rule> get(String skeletonKind) {
        return map.getOrDefault(skeletonKind, List.of());
    }

    /**
     * v1：提供一些默认规则集（中世纪风格）
     */
    public static SkeletonComponentRules defaultMedieval() {
        SkeletonComponentRules rules = new SkeletonComponentRules();

        // 城墙：外边缘栏杆/垛口 + 墙面射孔/小窗
        rules.put("WALL", new Rule()
                .socket(SocketType.EDGE_OUTER)
                .role("railing")
                .tag("battlement")
                .tag("medieval")
                .weight(1.0).required(true).range(1, 999));

        rules.put("WALL", new Rule()
                .socket(SocketType.WALL_OPENING)
                .role("window")
                .tag("arrow_slit")
                .tag("stone")
                .weight(0.9).required(false).range(0, 999));

        // 塔楼：小窗 + 顶部装饰（旗帜/尖顶等）
        rules.put("TOWER", new Rule()
                .socket(SocketType.WALL_OPENING)
                .role("window")
                .tag("small")
                .tag("gothic")
                .weight(1.0).range(1, 24));

        rules.put("TOWER", new Rule()
                .socket(SocketType.ROOF_RIDGE)
                .role("ornament")
                .tag("flag")
                .tag("medieval")
                .weight(0.6).range(0, 2));

        // 道路：边缘路灯（可选）
        rules.put("ROAD", new Rule()
                .socket(SocketType.EDGE_OUTER)
                .role("ornament")
                .tag("lamp")
                .tag("street")
                .weight(0.5).range(0, 999));

        return rules;
    }
}
