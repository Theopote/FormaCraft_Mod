package com.formacraft.server.build;

import com.formacraft.client.tools.ToolLayoutConstraints;
import com.formacraft.client.tools.ToolSnapshot;
import com.formacraft.common.layout.LayoutSite;
import com.formacraft.common.layout.LayoutConstraints;
import com.formacraft.common.layout.PathClusterLayoutPlanner;
import com.formacraft.common.model.request.FormaRequest;
import com.formacraft.common.terrain.TerrainStrategySampler;
import com.formacraft.FormacraftMod;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.List;

/**
 * PathLayoutService（路径布局服务）
 * 
 * 核心功能：从 FormaRequest 中提取 PathTool 数据，生成 LayoutSite 列表
 * 
 * 集成点：
 * - 在 BuildRequestHandler 中调用
 * - 生成站点后发送预览到客户端
 */
public final class PathLayoutService {
    
    private PathLayoutService() {}
    
    /**
     * 从 FormaRequest 生成 LayoutSite 列表
     * 
     * @param req 构建请求
     * @param world 世界
     * @param origin 原点
     * @return LayoutSite 列表（如果无法生成则返回空列表）
     */
    public static List<LayoutSite> generateLayoutSites(
            FormaRequest req,
            ServerWorld world,
            BlockPos origin
    ) {
        List<LayoutSite> sites = new ArrayList<>();
        
        try {
            // 1. 尝试从 requestText 中提取 PathSkeleton 信息
            // 注意：PathTool 数据应该在客户端已经通过 PromptAssembler 包含在 requestText 中
            // 这里我们需要从 requestText 解析，或者从 extra 字段获取
            
            // 临时方案：检查是否有路径相关的提示词
            String requestText = req.getRequestText();
            if (requestText == null || !containsPathKeywords(requestText)) {
                return sites; // 没有路径相关请求，返回空列表
            }
            
            // 2. 创建工具快照（从 FormaRequest 中提取工具状态）
            ToolSnapshot snapshot = createToolSnapshot(req);
            
            // 3. 创建布局约束
            LayoutConstraints constraints = new ToolLayoutConstraints(snapshot);
            
            // 4. 创建规划器
            TerrainStrategySampler terrain = new TerrainStrategySampler();
            PathClusterLayoutPlanner planner = new PathClusterLayoutPlanner(terrain);
            
            // 5. 配置选项
            PathClusterLayoutPlanner.Options opt = new PathClusterLayoutPlanner.Options();
            opt.spacing = 12; // 默认间距
            opt.footprintW = 9;
            opt.footprintD = 11;
            opt.clearance = 2;
            opt.lateralOffset = 5; // 沿路右侧布置
            
            // 6. 获取路径点（从 FormaRequest 中提取，或从 PathTool 状态）
            // 注意：这里需要从客户端传递路径点，或者从 requestText 解析
            // 临时方案：如果无法获取路径点，返回空列表
            List<BlockPos> pathPoints = extractPathPoints(req);
            if (pathPoints == null || pathPoints.size() < 2) {
                FormacraftMod.LOGGER.debug("PathLayoutService: no valid path points found");
                return sites;
            }
            
            // 7. 生成站点
            sites = planner.plan(world, pathPoints, origin, opt, constraints);
            
            // 8. 应用语义标签（如果存在）
            if (snapshot != null && !sites.isEmpty()) {
                ToolLayoutConstraints toolConstraints = new ToolLayoutConstraints(snapshot);
                List<LayoutSite> taggedSites = new ArrayList<>();
                for (LayoutSite site : sites) {
                    String tag = toolConstraints.resolveSemanticTag(site.anchor, site.tag);
                    taggedSites.add(site.withTag(tag));
                }
                sites = taggedSites;
            }
            
            FormacraftMod.LOGGER.info("PathLayoutService: generated {} layout sites", sites.size());
            
        } catch (Exception e) {
            FormacraftMod.LOGGER.error("PathLayoutService: error generating layout sites", e);
        }
        
        return sites;
    }
    
    /**
     * 检查请求文本是否包含路径相关关键词
     */
    private static boolean containsPathKeywords(String text) {
        if (text == null) return false;
        String lower = text.toLowerCase();
        return lower.contains("路径") || lower.contains("path") ||
               lower.contains("沿路") || lower.contains("along") ||
               lower.contains("街道") || lower.contains("street") ||
               lower.contains("道路") || lower.contains("road") ||
               lower.contains("建筑群") || lower.contains("cluster");
    }
    
    /**
     * 从 FormaRequest 创建 ToolSnapshot
     */
    private static ToolSnapshot createToolSnapshot(FormaRequest req) {
        ToolSnapshot snapshot = new ToolSnapshot();
        
        // 从 FormaRequest 中提取工具状态
        if (req.getSelectionMin() != null && req.getSelectionMax() != null) {
            snapshot.hasSelection = true;
            snapshot.selMin = req.getSelectionMin();
            snapshot.selMax = req.getSelectionMax();
        }
        
        // Outline 和 ProtectedZones 已经在 FormaRequest 中
        // 但需要转换为 ToolSnapshot 格式
        // 这里简化处理，实际应该从 req.getOutline() 和 req.getProtectedZones() 转换
        
        return snapshot;
    }
    
    /**
     * 从 FormaRequest 提取路径点
     * 
     * 注意：这是一个临时实现，实际应该从 PathTool 状态中获取
     * 或者从 requestText 中解析路径信息
     */
    private static List<BlockPos> extractPathPoints(FormaRequest req) {
        // TODO: 实际实现应该从 PathTool 状态中获取
        // 或者从 requestText 中解析路径信息
        // 或者从 FormaRequest 的 extra 字段中获取
        
        // 临时方案：返回 null，表示无法获取路径点
        // 实际使用时，客户端应该将路径点包含在请求中
        return null;
    }
}

