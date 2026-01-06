package com.formacraft.common.skeleton;

import java.util.*;

/**
 * SkeletonType 语义说明（Prompt 专用）
 * 
 * 这是 FormaCraft 的"AI 可理解的空间语言（Spatial Grammar）"的核心。
 * 
 * 设计理念：
 * - SkeletonType 是 AI 在"动手之前"的思考方式，而不是画方块的方式
 * - 让 LLM 在"规划阶段"就选对空间组织方式，而不是靠生成时瞎试
 * 
 * 使用方式：
 * - 作为 System Prompt 的固定附录
 * - 或作为 "--- Available Skeleton Types ---" 区块
 * - 或用于 Few-shot / 微调数据的 schema 描述
 */
public class SkeletonSemantics {
    
    /**
     * 单个骨架类型的语义说明
     */
    public static class SemanticDescription {
        private final SkeletonType type;
        private final String semantic;
        private final String spatialMeaning;
        private final List<String> useCases;
        private final List<String> constraints;
        private final String promptPhrase;
        
        public SemanticDescription(
                SkeletonType type,
                String semantic,
                String spatialMeaning,
                List<String> useCases,
                List<String> constraints,
                String promptPhrase) {
            this.type = type;
            this.semantic = semantic;
            this.spatialMeaning = spatialMeaning;
            this.useCases = useCases != null ? useCases : new ArrayList<>();
            this.constraints = constraints != null ? constraints : new ArrayList<>();
            this.promptPhrase = promptPhrase;
        }
        
        public SkeletonType getType() { return type; }
        public String getSemantic() { return semantic; }
        public String getSpatialMeaning() { return spatialMeaning; }
        public List<String> getUseCases() { return useCases; }
        public List<String> getConstraints() { return constraints; }
        public String getPromptPhrase() { return promptPhrase; }
        
        /**
         * 生成用于 Prompt 的完整描述
         */
        public String toPromptDescription() {
            StringBuilder sb = new StringBuilder();
            sb.append(type.name()).append("\n");
            sb.append("语义：").append(semantic).append("\n");
            sb.append("空间含义：").append(spatialMeaning).append("\n");
            sb.append("适用场景：").append(String.join("、", useCases)).append("\n");
            sb.append("约束特征：").append(String.join("、", constraints)).append("\n");
            sb.append("Prompt 描述用语：").append(promptPhrase).append("\n");
            return sb.toString();
        }
        
        /**
         * 生成英文 Prompt 描述（用于 LLM）
         */
        public String toEnglishPromptDescription() {
            StringBuilder sb = new StringBuilder();
            sb.append(type.name()).append("\n");
            sb.append("Semantic: ").append(semantic).append("\n");
            sb.append("Spatial meaning: ").append(spatialMeaning).append("\n");
            sb.append("Use cases: ").append(String.join(", ", useCases)).append("\n");
            sb.append("Constraints: ").append(String.join(", ", constraints)).append("\n");
            sb.append("Prompt phrase: ").append(promptPhrase).append("\n");
            return sb.toString();
        }
    }
    
    private static final Map<SkeletonType, SemanticDescription> DESCRIPTIONS = new HashMap<>();
    
    static {
        // ===== Linear / Path =====
        register(new SemanticDescription(
            SkeletonType.LINEAR_PATH,
            "线性主轴骨架",
            "建筑或构筑物沿一条明确的直线展开，强烈的方向性和前后关系",
            Arrays.asList("道路", "长城直线段", "长廊", "轴线型建筑", "防御墙体"),
            Arrays.asList("单一主方向", "宽度相对固定", "长度远大于宽度"),
            "a linear structure extending along a straight axis"
        ));
        
        register(new SemanticDescription(
            SkeletonType.PATH_POLYLINE,
            "折线主轴骨架",
            "沿多段方向变化的路径展开，通常用于顺应复杂地形",
            Arrays.asList("山路", "折线城墙", "曲折街道"),
            Arrays.asList("多个拐点", "连续但非直线", "通常贴合地形"),
            "a polyline path following terrain turns"
        ));
        
        register(new SemanticDescription(
            SkeletonType.CONTOUR_FOLLOW,
            "等高线跟随骨架",
            "沿地形等高线展开，尽量减少垂直起伏",
            Arrays.asList("山地道路", "长城山段", "山坡建筑群连接线"),
            Arrays.asList("强依赖地形采样", "高度变化平缓", "横向延展明显"),
            "following terrain contour lines with minimal vertical changes"
        ));
        
        // ===== Radial / Center =====
        register(new SemanticDescription(
            SkeletonType.RADIAL_RING,
            "中心闭合环形骨架",
            "围绕中心点形成完整闭环，强烈的向心性与围合感",
            Arrays.asList("福建土楼", "圆形要塞", "环形竞技场"),
            Arrays.asList("封闭几何", "中心空间明确", "对称性极强"),
            "a closed radial ring surrounding a central courtyard"
        ));
        
        register(new SemanticDescription(
            SkeletonType.RADIAL_SPOKE,
            "中心辐射骨架",
            "从中心向外发散多条轴线，中心地位极其重要",
            Arrays.asList("天坛", "中央广场", "放射型城市结构"),
            Arrays.asList("中心锚点", "多方向连接", "常结合对称"),
            "radial spokes extending outward from a central anchor"
        ));
        
        // ===== Vertical =====
        register(new SemanticDescription(
            SkeletonType.VERTICAL_STACK,
            "垂直堆叠骨架",
            "多层向上叠加，明确楼层逻辑",
            Arrays.asList("住宅楼", "塔楼", "办公楼"),
            Arrays.asList("层级清晰", "高度主导", "底面积相对稳定"),
            "vertically stacked layers forming multiple floors"
        ));
        
        register(new SemanticDescription(
            SkeletonType.VERTICAL_TAPER,
            "向上收缩骨架",
            "上部逐渐变窄，强烈的纪念性或象征性",
            Arrays.asList("宝塔", "尖塔", "纪念碑"),
            Arrays.asList("高度主导", "逐层缩小", "常伴随对称"),
            "a vertically tapering structure narrowing toward the top"
        ));
        
        // ===== Area / Enclosure =====
        register(new SemanticDescription(
            SkeletonType.GRID,
            "规则网格骨架",
            "正交、模块化布局，高度理性、秩序化",
            Arrays.asList("城市街区", "办公园区", "现代住宅群"),
            Arrays.asList("X/Z 正交", "模块重复", "易扩展"),
            "an orthogonal grid-based layout"
        ));
        
        register(new SemanticDescription(
            SkeletonType.COURTYARD,
            "中庭围合骨架",
            "建筑围绕一个中心空庭展开，但不一定是圆形",
            Arrays.asList("四合院", "修道院", "办公中庭"),
            Arrays.asList("内向空间", "中央留空", "四周环绕"),
            "buildings enclosing a central courtyard"
        ));
        
        register(new SemanticDescription(
            SkeletonType.PERIMETER_LOOP,
            "轮廓闭环骨架",
            "沿指定轮廓形成闭合结构，形状可不规则",
            Arrays.asList("城墙", "园区围栏", "自定义轮廓建筑"),
            Arrays.asList("闭合", "跟随用户绘制轮廓", "强约束区域边界"),
            "a closed loop following a predefined perimeter outline"
        ));
        
        register(new SemanticDescription(
            SkeletonType.ENCLOSURE,
            "不规则围合骨架",
            "围合空间，但允许缺口或非完整闭合，更自由、更自然",
            Arrays.asList("山地村落", "防御聚落", "半围合园区"),
            Arrays.asList("非规则", "局部围合", "强地形依赖"),
            "an irregular enclosing structure adapting to terrain"
        ));
        
        // ===== Span / Structure =====
        register(new SemanticDescription(
            SkeletonType.SPAN_SUSPENSION,
            "跨越/悬索骨架",
            "跨越障碍物，中间无直接支撑或支撑最小化",
            Arrays.asList("桥梁", "悬索结构", "高架通道"),
            Arrays.asList("起点/终点明确", "中段悬空", "结构感强"),
            "a spanning structure bridging two distant points"
        ));
        
        // ===== Terrain =====
        register(new SemanticDescription(
            SkeletonType.TERRACED,
            "台地式骨架",
            "多个高度平台逐级展开，与地形高度强耦合",
            Arrays.asList("山城", "梯田建筑", "山地寺庙"),
            Arrays.asList("多高度层级", "局部平整", "顺势而建"),
            "terraced platforms stepping along the terrain"
        ));
        
        // ===== Composite =====
        register(new SemanticDescription(
            SkeletonType.HIERARCHICAL_TREE,
            "主从层级骨架",
            "明确的\"核心 + 附属\"关系，空间权重不均等",
            Arrays.asList("寺庙群", "校园", "园区规划"),
            Arrays.asList("主体突出", "子体围绕", "结构清晰"),
            "a hierarchical layout with a dominant central structure and secondary branches"
        ));
        
        register(new SemanticDescription(
            SkeletonType.COMPOUND,
            "组合骨架（兜底）",
            "多种 Skeleton 组合，用于复杂或混合型建筑",
            Arrays.asList("城市", "超大型建筑群", "AI 自由规划"),
            Arrays.asList("包含多个子 Skeleton", "需要进一步拆解"),
            "a compound structure combining multiple spatial skeletons"
        ));
    }
    
    private static void register(SemanticDescription desc) {
        DESCRIPTIONS.put(desc.getType(), desc);
    }
    
    /**
     * 获取指定骨架类型的语义说明
     */
    public static SemanticDescription getDescription(SkeletonType type) {
        return DESCRIPTIONS.get(type);
    }
    
    /**
     * 获取所有骨架类型的语义说明
     */
    public static Collection<SemanticDescription> getAllDescriptions() {
        return DESCRIPTIONS.values();
    }
    
    /**
     * 生成完整的 Prompt 区块（中文）
     */
    public static String generatePromptBlock() {
        StringBuilder sb = new StringBuilder();
        sb.append("--- Available Skeleton Types ---\n");
        sb.append("SkeletonType 是 AI 在\"动手之前\"的思考方式，而不是画方块的方式。\n");
        sb.append("每个骨架类型代表一种空间组织方式，用于规划阶段的决策。\n\n");
        
        for (SkeletonType type : SkeletonType.values()) {
            SemanticDescription desc = DESCRIPTIONS.get(type);
            if (desc != null) {
                sb.append(desc.toPromptDescription()).append("\n");
            }
        }
        
        return sb.toString();
    }
    
    /**
     * 生成完整的 Prompt 区块（英文，用于 LLM）
     */
    public static String generateEnglishPromptBlock() {
        StringBuilder sb = new StringBuilder();
        sb.append("--- Available Skeleton Types ---\n");
        sb.append("SkeletonType represents spatial organization patterns for AI planning, not block placement methods.\n");
        sb.append("Each skeleton type represents a way of organizing space, used for decision-making in the planning phase.\n\n");
        
        for (SkeletonType type : SkeletonType.values()) {
            SemanticDescription desc = DESCRIPTIONS.get(type);
            if (desc != null) {
                sb.append(desc.toEnglishPromptDescription()).append("\n");
            }
        }
        
        return sb.toString();
    }
    
    /**
     * 生成简化的 Prompt 区块（仅类型和描述用语）
     */
    public static String generateCompactPromptBlock() {
        StringBuilder sb = new StringBuilder();
        sb.append("--- Available Skeleton Types ---\n");
        
        for (SkeletonType type : SkeletonType.values()) {
            SemanticDescription desc = DESCRIPTIONS.get(type);
            if (desc != null) {
                sb.append(type.name())
                  .append(": ")
                  .append(desc.getPromptPhrase())
                  .append("\n");
            }
        }
        
        return sb.toString();
    }
}

