package com.formacraft.common.component.query;

import java.util.HashSet;
import java.util.Set;

/**
 * ComponentQuery
 * <p>
 * 描述"我想要一个什么样的构件"
 * —— 由 PromptAssembler / LLM 生成
 * —— 由 ComponentRetriever / Ranker 消费
 * <p>
 * 设计目标：
 * - 完全数据驱动
 * - 可 JSON 映射（Gson / Jackson）
 * - 不包含任何"选择逻辑"
 * - 能被 LLM 明确输出
 */
public class ComponentQuery {
    /** 我是什么（门 / 窗 / 柱 / 装饰等） */
    public Semantic semantic = new Semantic();

    /** 放在哪里 */
    public Context context = new Context();

    /** 尺寸 / 形态约束 */
    public Geometry geometry = new Geometry();

    /** 风格一致性 */
    public Style style = new Style();

    /** 强约束 */
    public Constraints constraints = new Constraints();

    /** 排序辅助（主次、可见度） */
    public UsageHint usageHint = new UsageHint();

    /* ----------------------------
     * 子结构定义
     * ---------------------------- */

    public static class Semantic {
        /** 构件语义角色：door / window / column / balcony / railing ... */
        public String role;

        /** 语义标签：gothic / arched / heavy / modern */
        public Set<String> tags = new HashSet<>();

        /**
         * 重要性提示：
         * role / placement / style / geometry
         * 用于 Ranker 调整权重
         */
        public Set<String> importance = new HashSet<>();
    }

    public static class Context {
        /** wall / roof / edge / ground / interior */
        public String placement;

        /** exterior / interior / both */
        public String side;

        /** ground / mid / roof */
        public String heightLevel;

        /** flat / corner / convex / concave */
        public String edgeCondition;
    }

    public static class Geometry {
        /** 是否需要洞口（门窗） */
        public boolean requiresOpening = false;

        /** 期望洞口尺寸（可选） */
        public Integer openingWidth;
        public Integer openingHeight;

        /** 尺寸允许误差（± block） */
        public int tolerance = 0;

        /** 是否允许缩放 */
        public boolean scalable = true;
    }

    public static class Style {
        /** StyleProfile ID */
        public String styleProfile;

        /** 材质色调（dark_stone / light_wood / red_brick） */
        public String materialTone;
    }

    public static class Constraints {
        /** 必须包含的标签 */
        public Set<String> mustHave = new HashSet<>();

        /** 禁止出现的标签 */
        public Set<String> forbiddenTags = new HashSet<>();
    }

    public static class UsageHint {
        /** primary / secondary / decorative */
        public String frequency;

        /** high / medium / low */
        public String visibility;
    }

}
