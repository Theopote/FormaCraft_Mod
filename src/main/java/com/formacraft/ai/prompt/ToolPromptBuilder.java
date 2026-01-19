package com.formacraft.ai.prompt;

import com.formacraft.ai.context.BrushContext;
import com.formacraft.ai.context.OutlineContext;
import com.formacraft.ai.context.OutlineAttachmentContext;
import com.formacraft.ai.context.ProtectedZoneContext;
import com.formacraft.ai.context.SelectionContext;
import com.formacraft.ai.context.SemanticLabelContext;
import com.formacraft.ai.context.SymmetryContext;
import com.formacraft.client.tool.PathTool;
import com.formacraft.common.skeleton.PathSkeleton;

/**
 * 工具状态 → PromptContext 语义构建器。
 * <p>
 * 这里不负责最终字符串格式，只负责把事实/约束分类塞进 ctx。
 */
public final class ToolPromptBuilder {
    private ToolPromptBuilder() {}

    public static void buildToolContext(PromptContext ctx) {
        if (ctx == null) return;

        // 空间语义（anchor/facing/mode 等）由 PromptAssembler 统一输出，避免重复与双源。

        // PathTool → PathSkeleton（新增：路径骨架）
        if (PathTool.INSTANCE != null) {
            PathSkeleton skeleton = PathTool.INSTANCE.toSkeleton();
            if (skeleton != null && skeleton.isValid()) {
                // 从用户输入中识别路径意图
                PathSkeleton.PathIntent intent = resolvePathIntent(ctx.userMessage);
                if (intent != PathSkeleton.PathIntent.GENERIC) {
                    // 如果识别到明确意图，创建新的 PathSkeleton
                    skeleton = new PathSkeleton(
                        skeleton.nodes,
                        skeleton.corridorRadius,
                        skeleton.snapToGround,
                        intent
                    );
                }
                ctx.pathSkeleton = skeleton;
                
                // K2 新增：根据用户输入和工具状态确定 StreetProfile
                ctx.streetProfile = resolveStreetProfile(ctx.userMessage);
                
                // 如果 SymmetryTool 启用，强制对称
                if (SymmetryContext.enabled() && ctx.streetProfile != null) {
                    ctx.streetProfile = ctx.streetProfile.withSymmetric(true);
                }
                
                // K3 新增：根据用户输入确定 ZoningProfile
                ctx.zoningProfile = resolveZoningProfile(ctx.userMessage, ctx.streetProfile);
                
                // K3.1 新增：生成 PathClusterLayout 和 ZonedSlot（如果可能）
                // 注意：这里需要 ServerWorld，但 ToolPromptBuilder 是客户端代码
                // 实际生成应该在服务端进行，这里只设置必要的参数
                // clusterLayout 和 zonedSlots 将在服务端生成后设置到 ctx
            }
        }

        // 笔刷选中区域（边界约束 - 优先级低于选区，但高于默认）
        if (BrushContext.hasBrushSelection() && !SelectionContext.hasSelection()) {
            // 如果已有选区，则选区优先级更高，不处理笔刷
            // 如果没有选区，则使用笔刷区域作为约束
            addMultiline(ctx.constraints, BrushContext.toPromptBlock());
            ctx.rules.add("- 建筑应该优先生成在笔刷选中的地表区域内");
            ctx.rules.add("- 建筑的基础部分应该与笔刷选中的方块对齐");
        }

        // 选区（边界约束）
        if (SelectionContext.hasSelection()) {
            addMultiline(ctx.constraints, SelectionContext.toPromptBlock());
            ctx.rules.add("- 建筑必须完全位于选区内，不得越界");
            ctx.rules.add("- 不要在选区外放置任何方块");
        }

        // 禁区/保护区（强规则）
        if (ProtectedZoneContext.hasZones()) {
            addMultiline(ctx.rules, ProtectedZoneContext.toPromptBlock());
        }

        // 轮廓/Footprint（强约束）
        if (OutlineContext.hasOutline()) {
            addMultiline(ctx.constraints, OutlineContext.toPromptBlock());
            addMultiline(ctx.constraints, OutlineAttachmentContext.toPromptBlock());
        }

        // 对称/镜像（约束）
        if (SymmetryContext.enabled()) {
            addMultiline(ctx.constraints, SymmetryContext.toPromptBlock());
        }

        // 语义标注（annotations）
        if (SemanticLabelContext.hasLabels()) {
            addMultiline(ctx.annotations, SemanticLabelContext.toPromptBlock());
        }
    }

    /**
     * 从用户输入中解析路径意图
     */
    private static PathSkeleton.PathIntent resolvePathIntent(String userText) {
        if (userText == null || userText.trim().isEmpty()) {
            return PathSkeleton.PathIntent.GENERIC;
        }

        String lower = userText.toLowerCase();
        
        // 检查关键词
        if (lower.contains("长城") || lower.contains("城墙") || lower.contains("wall") || 
            lower.contains("防御") || lower.contains("要塞")) {
            return PathSkeleton.PathIntent.WALL;
        }
        
        if (lower.contains("桥") || lower.contains("bridge") || lower.contains("跨越")) {
            return PathSkeleton.PathIntent.BRIDGE;
        }
        
        if (lower.contains("路") || lower.contains("road") || lower.contains("道路") || 
            lower.contains("街道") || lower.contains("street")) {
            return PathSkeleton.PathIntent.ROAD;
        }
        
        if (lower.contains("沿街") || lower.contains("沿线") || lower.contains("沿路径") || 
            lower.contains("along") || lower.contains("building")) {
            return PathSkeleton.PathIntent.ALONG_PATH_BUILDING;
        }
        
        return PathSkeleton.PathIntent.GENERIC;
    }

    /**
     * 从用户输入解析 StreetProfile（K2 新增）
     */
    private static com.formacraft.common.cluster.StreetProfile resolveStreetProfile(String userText) {
        if (userText == null || userText.trim().isEmpty()) {
            return com.formacraft.common.cluster.StreetProfile.simple(); // 默认单排
        }

        String lower = userText.toLowerCase();
        
        // 检查关键词
        if (lower.contains("商业街") || lower.contains("主街") || lower.contains("boulevard") || 
            lower.contains("商业") || lower.contains("commercial")) {
            return com.formacraft.common.cluster.StreetProfile.boulevard(); // 双排
        }
        
        if (lower.contains("城市大道") || lower.contains("大道") || lower.contains("avenue") || 
            lower.contains("三排") || lower.contains("wide")) {
            return com.formacraft.common.cluster.StreetProfile.avenue(); // 三排
        }
        
        if (lower.contains("城墙") || lower.contains("长城") || lower.contains("wall") || 
            lower.contains("走廊") || lower.contains("corridor")) {
            return com.formacraft.common.cluster.StreetProfile.wallCorridor(); // 城墙走廊
        }
        
        if (lower.contains("中轴") || lower.contains("轴线") || lower.contains("axis") || 
            lower.contains("单侧") || lower.contains("one side")) {
            return com.formacraft.common.cluster.StreetProfile.processionalAxis(); // 中轴线
        }
        
        if (lower.contains("两排") || lower.contains("双排") || lower.contains("two lanes")) {
            return com.formacraft.common.cluster.StreetProfile.boulevard(); // 双排
        }
        
        // 默认：单排
        return com.formacraft.common.cluster.StreetProfile.simple();
    }

    /**
     * 从用户输入解析 ZoningProfile（K3 新增）
     */
    private static com.formacraft.common.cluster.zoning.ZoningProfile resolveZoningProfile(
            String userText,
            com.formacraft.common.cluster.StreetProfile streetProfile
    ) {
        if (userText == null || userText.trim().isEmpty()) {
            // 默认使用城镇街道分区
            int laneCount = (streetProfile != null) ? streetProfile.laneCount() : 1;
            return com.formacraft.common.cluster.zoning.ZoningProfile.defaultTownStreet(laneCount);
        }

        String lower = userText.toLowerCase();
        int laneCount = (streetProfile != null) ? streetProfile.laneCount() : 1;
        
        // 检查关键词
        if (lower.contains("商业街") || lower.contains("commercial") || 
            lower.contains("商店") || lower.contains("shop")) {
            return com.formacraft.common.cluster.zoning.ZoningProfile.commercialStreet(laneCount);
        }
        
        if (lower.contains("城墙") || lower.contains("长城") || lower.contains("防御") || 
            lower.contains("wall") || lower.contains("defensive")) {
            return com.formacraft.common.cluster.zoning.ZoningProfile.defensiveStreet();
        }
        
        // 默认：城镇街道分区
        return com.formacraft.common.cluster.zoning.ZoningProfile.defaultTownStreet(laneCount);
    }

    private static void addMultiline(java.util.List<String> target, String block) {
        if (target == null) return;
        if (block == null) return;
        String s = block.trim();
        if (s.isEmpty()) return;
        for (String line : s.split("\\R")) {
            String t = line.trim();
            if (!t.isEmpty()) target.add(t);
        }
    }
}


