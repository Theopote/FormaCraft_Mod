package com.formacraft.common.llm.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * PlanProgram（平面程序）v1
 * <p>
 * 核心思想：PlanProgram = 功能拓扑 + 体量拆解策略 + 约束，而不是形状
 * <p>
 * 这个 schema 的目标：
 * - LLM 易生成（低歧义、低数学、低坐标）
 * - 不直接包含几何细节
 * - 支持复杂平面（非矩形 / 非单体）
 * - 可被系统逐步"几何化"
 * - 可被用户工具覆盖 / 修正
 * <p>
 * 设计原则：
 * 1. 平面不是形状，是「功能块关系」
 * 2. 平面复杂度 ≠ 随机，而是「体量消解规则」
 * 3. 平面形态应当由「约束」推导，而不是"灵感"
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PlanProgram(
        @JsonProperty("schema") String schema,
        @JsonProperty("intent") PlanIntent intent,
        @JsonProperty("zones") List<PlanZone> zones,
        @JsonProperty("adjacency") List<List<String>> adjacency,
        @JsonProperty("massing") PlanMassing massing,
        @JsonProperty("circulation") PlanCirculation circulation,
        @JsonProperty("constraints") PlanConstraints constraints
) {
    /**
     * PlanIntent：AI 的"建筑动机层"
     * <p>
     * 作用：
     * - 给 LLM 一个高层语境
     * - 给系统一个默认行为集合
     * <p>
     * 为什么不写更多？
     * - 风格细节 → 交给 Skeleton / Component
     * - 这里只影响平面逻辑倾向
     * <p>
     * 例如：
     * - residential → 更可能分翼
     * - temple → 更可能轴线对称
     * - market → 更可能多入口 / 多块
     */
    public record PlanIntent(
            @JsonProperty("building_type") String buildingType,
            @JsonProperty("style_hint") String styleHint,
            @JsonProperty("scale") String scale
    ) {}

    /**
     * PlanZone：平面生成的核心
     * <p>
     * 这是整个 schema 最重要的部分。
     * <p>
     * zones 解决了什么？
     * - 把"一个建筑"拆成多个功能块
     * - 平面复杂度 = zone 数量 + 关系
     * - 十字形 / L 形 / 锯齿形 → 都是 zone 组合的"几何结果"
     * <p>
     * 关键字段说明：
     * - id: 稳定引用（给 adjacency / circulation 用）
     * - role: 语义角色（AI 非常擅长）
     * - importance: 决定体量中心性
     * - area_ratio: 不给绝对值，避免 LLM 数学崩溃
     */
    public record PlanZone(
            @JsonProperty("id") String id,
            @JsonProperty("role") String role,
            @JsonProperty("importance") String importance,
            @JsonProperty("area_ratio") Double areaRatio
    ) {
        public enum Importance {
            primary,    // 主要功能块（核心、主体）
            secondary,  // 次要功能块（翼楼、附属）
            support     // 支撑功能块（服务、后勤）
        }
    }

    /**
     * PlanMassing：体量消解机制
     * <p>
     * 这正是"面积大的时候来消解体量"的显式策略。
     * <p>
     * 为什么一定要显式写出来？
     * - 否则 AI 会默认：一个大盒子
     * - 而不是：多个相关盒子
     * <p>
     * 推荐的 operation 枚举（v1）：
     * - add_wings
     * - subtract_courtyard
     * - offset_volumes
     * - split_along_axis
     * - wrap_around_courtyard
     * <p>
     * 这些都不是几何，而是建筑语言。
     */
    public record PlanMassing(
            @JsonProperty("strategy") String strategy,
            @JsonProperty("rules") MassingRules rules
    ) {
        public enum Strategy {
            decompose,  // 拆解（默认）
            monolithic, // 单体（不拆解）
            cluster     // 集群（独立体量）
        }

        public record MassingRules(
                @JsonProperty("max_zone_area") Integer maxZoneArea,
                @JsonProperty("preferred_operations") List<String> preferredOperations
        ) {}
    }

    /**
     * PlanCirculation：平面"像不像人设计的"关键因素
     * <p>
     * 为什么 MVP 也要有 circulation？
     * - 平面如果没有"动线逻辑"，再复杂也会显得"随机"
     * - 即使 v1 只支持 direct / ring / branch，也已经足够让平面有秩序感
     */
    public record PlanCirculation(
            @JsonProperty("primary_axis") String primaryAxis,
            @JsonProperty("connection_style") String connectionStyle
    ) {
        public enum ConnectionStyle {
            direct,     // 直接连接（最简单）
            ring,       // 环形（环绕庭院）
            branch      // 分支（树状）
        }
    }

    /**
     * PlanConstraints：平面不是自由创作，是受限推导
     * <p>
     * 这是"根据地形、建筑风格……"的入口。
     * <p>
     * 在这里：
     * - AI 不画
     * - AI 决策
     * - 系统执行
     * <p>
     * 这也是未来支持：
     * - 用户平面轮廓
     * - 法线限制
     * - 红线范围
     * 的入口。
     */
    public record PlanConstraints(
            @JsonProperty("terrain") TerrainConstraints terrain,
            @JsonProperty("geometry") GeometryConstraints geometry
    ) {
        public record TerrainConstraints(
                @JsonProperty("respect_slope") Boolean respectSlope,
                @JsonProperty("avoid") List<String> avoid
        ) {}

        public record GeometryConstraints(
                @JsonProperty("avoid_shapes") List<String> avoidShapes,
                @JsonProperty("prefer_symmetry") String preferSymmetry
        ) {
            public enum Symmetry {
                none,       // 无对称
                axial,      // 轴线对称
                radial,     // 径向对称
                bilateral   // 双轴对称
            }
        }
    }
}
