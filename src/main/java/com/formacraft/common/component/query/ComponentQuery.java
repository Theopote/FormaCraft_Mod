package com.formacraft.common.component.query;

import com.formacraft.common.component.archetype.ContextType;
import com.formacraft.common.component.archetype.SurfaceSide;

import java.util.List;
import java.util.Set;

/**
 * ComponentQuery（构件查询）：AI 选择构件时的查询模型。
 * <p>
 * 核心思想：
 * - AI 说"我要一个门"，系统如何从 ComponentLibrary 里选"最合适的门"？
 * - 这不是玩家交互，而是 AI 的决策单位
 * - AI 永远不知道构件 ID，只描述"我想要什么样的构件"
 * <p>
 * 这是 PromptAssembler 输出的一部分，也是后端的第一输入。
 */
public class ComponentQuery {
    /**
     * 语义信息（我是什么）
     */
    public Semantic semantic;

    /**
     * 上下文信息（我要放在哪里）
     */
    public Context context;

    /**
     * 几何信息（我大概多大）
     */
    public Geometry geometry;

    /**
     * 风格信息（风格一致性）
     */
    public Style style;

    /**
     * 约束条件（硬性约束）
     */
    public Constraints constraints;

    /**
     * 使用提示（排序辅助）
     */
    public UsageHint usageHint;

    /**
     * 语义信息
     */
    public static class Semantic {
        /**
         * 角色（例如："door", "window", "column", "railing", "decoration"）
         */
        public String role;

        /**
         * 标签（例如：["gothic", "arched", "heavy"]）
         */
        public List<String> tags;

        /**
         * 重要性字段（用于调权重，例如：["role", "placement"]）
         */
        public List<String> importance;
    }

    /**
     * 上下文信息（Attachment / Context / FacingPolicy 的消费端）
     */
    public static class Context {
        /**
         * 放置上下文（例如："wall", "roof", "edge", "ground", "interior"）
         */
        public String placement;

        /**
         * 表面侧（例如："interior", "exterior", "both"）
         */
        public String side;

        /**
         * 高度层级（例如："ground", "mid", "roof"）
         */
        public String heightLevel;

        /**
         * 边缘条件（例如："corner", "flat", "convex"）
         */
        public String edgeCondition;
    }

    /**
     * 几何信息
     */
    public static class Geometry {
        /**
         * 开口信息（洞口类构件）
         */
        public Opening opening;

        /**
         * 是否允许变体缩放
         */
        public Boolean scalable;

        /**
         * 容差（给 AI 留余地，很重要）
         */
        public Integer tolerance;
    }

    /**
     * 开口信息
     */
    public static class Opening {
        /**
         * 宽度
         */
        public Integer width;

        /**
         * 高度
         */
        public Integer height;

        /**
         * 容差
         */
        public Integer tolerance;
    }

    /**
     * 风格信息
     */
    public static class Style {
        /**
         * 风格配置（来自 StyleProfileCatalog，例如："Medieval_Castle"）
         */
        public String styleProfile;

        /**
         * 材质色调（例如："dark_stone", "light_wood"）
         */
        public String materialTone;
    }

    /**
     * 约束条件（硬性约束）
     */
    public static class Constraints {
        /**
         * 禁止的标签（明确不要）
         */
        public List<String> forbiddenTags;

        /**
         * 必须有的标签（必须具备）
         */
        public List<String> mustHave;
    }

    /**
     * 使用提示（排序辅助）
     */
    public static class UsageHint {
        /**
         * 频率（例如："primary", "secondary", "decorative"）
         */
        public String frequency;

        /**
         * 可见性（例如："high", "low"）
         */
        public String visibility;
    }

    /**
     * 创建基础查询（仅语义角色）
     */
    public static ComponentQuery role(String role) {
        ComponentQuery query = new ComponentQuery();
        query.semantic = new Semantic();
        query.semantic.role = role;
        return query;
    }

    /**
     * 创建基础查询（仅语义标签）
     */
    public static ComponentQuery semantic(String... tags) {
        ComponentQuery query = new ComponentQuery();
        query.semantic = new Semantic();
        query.semantic.tags = List.of(tags);
        return query;
    }

    /**
     * 创建完整查询
     */
    public static ComponentQuery create(
            Semantic semantic,
            Context context,
            Geometry geometry,
            Style style,
            Constraints constraints,
            UsageHint usageHint
    ) {
        ComponentQuery query = new ComponentQuery();
        query.semantic = semantic;
        query.context = context;
        query.geometry = geometry;
        query.style = style;
        query.constraints = constraints;
        query.usageHint = usageHint;
        return query;
    }
}
