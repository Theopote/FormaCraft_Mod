package com.formacraft.common.llm.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * PlanSkeleton（2D 平面骨架）v1 - 架构校准后
 * <p>
 * 🎯 核心定位（架构校准 2026-01-14）：
 * PlanSkeleton 不是"建筑的真实几何平面"，而是一个"建筑活动允许发生的空间域（Domain）"。
 * <p>
 * 它的作用只有三个：
 * 1. 限制范围：建筑不越界，AI 不乱飞
 * 2. 提供参考：朝向、主体位置、与环境的关系
 * 3. 作为体量组合的"舞台"：几何体可以进、出、穿插、悬挑，但总体不脱离这个 domain
 * <p>
 * ⚠️ 重要说明：
 * - PlanSkeleton 不是"真实楼板"或"真实外墙轮廓"
 * - 它只是一个"Site Boundary / Build Domain"
 * - 真正的建筑结构来自"体量组合（Building Mass Assembly）"后的实例化
 * <p>
 * 原始职责（保留，但理解已更新）：
 * 1. 把 zones + adjacency 转成空间域约束
 * 2. 提供 "边 / 面 / 内部 / 外部" 等语义（作为约束参考）
 * 3. 为后续 Building Mass Assembly 提供 Domain 输入
 * <p>
 * 在整个流水线中的位置（校准后）：
 * <pre>
 * LLM
 *  ↓
 * PlanProgram.json        ← AI 擅长（功能关系）
 *  ↓
 * PlanSkeleton.json       ← Site Boundary / Domain（空间域约束）
 *  ↓
 * Building Mass Assembly  ← 体量组合（缺失的核心层，未来实现）
 *  ↓
 * StructuralSkeleton      ← 从体量组合派生（候选结构）
 *  ↓
 * Skeleton (3D)           ← 3D 骨架
 *  ↓
 * SocketProvider          ← Socket 生成
 *  ↓
 * Component / Assembly    ← 构件装配（方块）
 * </pre>
 * <p>
 * PlanSkeleton 是人类 & AI 都可以理解和修改的一层。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PlanSkeleton(
        @JsonProperty("schema") String schema,
        @JsonProperty("outline") Outline outline,
        @JsonProperty("zones") List<PlanZone> zones,
        @JsonProperty("edges") List<Edge> edges,
        @JsonProperty("courtyards") List<Courtyard> courtyards,
        @JsonProperty("axes") List<Axis> axes
) {
    /**
     * Outline：平面"从哪来"
     * <p>
     * 这个字段现在看似没用，但极其重要。
     * 它解决的是未来这件事：
     * - 这是 AI 生成的？
     * - 用户画的？
     * - 从已有建筑扫描的？
     * <p>
     * 后续 "用户平面绘制工具"会直接写这里。
     */
    public record Outline(
            @JsonProperty("source") String source,
            @JsonProperty("shape") String shape
    ) {
        public enum Source {
            generated,   // AI 完全生成
            user_drawn,  // 用户轮廓工具
            imported     // 未来：从世界扫描
        }

        public enum Shape {
            polygon,     // 多边形
            rectilinear, // 直线型（矩形组合）
            freeform     // 自由曲线
        }
    }

    /**
     * PlanZone：与 PlanProgram 的 zones 对应，但角色不同
     * <p>
     * 区别：
     * | 层级           | zones 的含义           |
     * | ------------ | ------------------- |
     * | PlanProgram  | "我要哪些空间？"           |
     * | PlanSkeleton | "这些空间在平面中处于什么位置关系？" |
     * <p>
     * 新增字段说明：
     * | 字段             | 作用           |
     * | -------------- | ------------ |
     * | `boundary`     | 决定是否生成外墙     |
     * | `access`       | 为门、动线、窗提供语义  |
     * | `connected_to` | 变成**真实的连接边** |
     * <p>
     * 这一步开始出现"墙"的概念，但还没有几何。
     */
    public record PlanZone(
            @JsonProperty("id") String id,
            @JsonProperty("boundary") String boundary,
            @JsonProperty("access") String access,
            @JsonProperty("connected_to") List<String> connectedTo
    ) {
        public enum Boundary {
            external,  // 外部边界（有外墙）
            internal,  // 内部区域（被包裹）
            mixed      // 混合（部分外部）
        }

        public enum Access {
            public_,      // 公共访问
            semi_private, // 半私密
            private_      // 私密
        }
    }

    /**
     * Edge：SocketProvider 的黄金输入
     * <p>
     * 这一步非常关键，SocketProvider v1 几乎可以直接吃这个结构。
     * <p>
     * Edge 的职责：
     * - 定义哪里是：外墙、内墙、共墙
     * - 为后续生成 WALL_SURFACE、EDGE_OUTER、WALL_OPENING 提供语义来源
     * <p>
     * 推荐 type 枚举（v1）：
     * - external_wall - 外墙
     * - shared_wall - 共墙（两个 zone 共享）
     * - courtyard_wall - 庭院墙（内向立面）
     * - boundary_edge - 边界边（非墙，如栏杆）
     */
    public record Edge(
            @JsonProperty("id") String id,
            @JsonProperty("type") String type,
            @JsonProperty("zones") List<String> zones,
            @JsonProperty("exterior") Boolean exterior
    ) {
        public enum Type {
            external_wall,   // 外墙
            shared_wall,     // 共墙
            courtyard_wall,  // 庭院墙
            boundary_edge    // 边界边
        }
    }

    /**
     * Courtyard：非矩形平面的核心来源
     * <p>
     * 为什么单独列出来？
     * - 庭院 = 体量减法
     * - 它会生成：内向立面、特殊采光、不同 Socket 密度
     * <p>
     * 这是 回字形 / 组合平面 / 中式院落 的根源。
     */
    public record Courtyard(
            @JsonProperty("id") String id,
            @JsonProperty("adjacent_zones") List<String> adjacentZones
    ) {}

    /**
     * Axis：平面"像建筑师画的"的关键
     * <p>
     * 轴线在系统中的作用：
     * - 影响对称性
     * - 影响主入口
     * - 影响门窗密度
     * - 但**不强制几何对称**
     * <p>
     * 这是非常"建筑学"的一层抽象。
     */
    public record Axis(
            @JsonProperty("id") String id,
            @JsonProperty("role") String role,
            @JsonProperty("zones") List<String> zones
    ) {
        public enum Role {
            primary,    // 主轴线
            secondary,  // 次轴线
            symmetry    // 对称轴
        }
    }
}
