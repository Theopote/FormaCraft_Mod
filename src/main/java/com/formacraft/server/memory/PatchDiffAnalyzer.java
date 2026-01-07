package com.formacraft.server.memory;

import com.formacraft.common.patch.BlockPatch;
import com.formacraft.FormacraftMod;
import net.minecraft.util.math.BlockPos;

import java.util.*;

/**
 * Patch 差异分析器：分析 Patch 操作对建筑记忆的影响
 * 
 * 核心功能：
 * - 识别 Patch 影响的建筑
 * - 判断影响类型（新建/修改/扩建/拆除）
 * - 识别受影响的语义部位
 */
public class PatchDiffAnalyzer {
    private final MemoryManager memoryManager;
    
    public PatchDiffAnalyzer(MemoryManager memoryManager) {
        this.memoryManager = memoryManager;
    }
    
    /**
     * 分析 Patch 操作的影响
     * 
     * @param origin Patch 原点
     * @param patches Patch 列表
     * @return 影响列表（每个受影响的建筑一个 PatchImpact）
     */
    public List<PatchImpact> analyze(BlockPos origin, List<BlockPatch> patches) {
        if (origin == null || patches == null || patches.isEmpty()) {
            return List.of();
        }
        
        // 按建筑 UUID 分组
        Map<UUID, PatchImpact> impacts = new HashMap<>();
        
        for (BlockPatch patch : patches) {
            if (patch == null) continue;
            
            BlockPos worldPos = origin.add(patch.dx(), patch.dy(), patch.dz());
            
            // 查找这个位置属于哪个建筑
            List<ProjectMemory> memories = memoryManager.findAtPosition(worldPos);
            ProjectMemory memory = memories.isEmpty() ? null : memories.get(0);
            
            UUID buildingId = (memory != null) ? UUID.fromString(memory.getUuid()) : null;
            
            // 获取或创建 PatchImpact
            PatchImpact impact = impacts.computeIfAbsent(
                buildingId,
                k -> {
                    PatchImpact imp = new PatchImpact();
                    imp.setTargetBuilding(buildingId);
                    // 默认类型：有建筑 = MODIFY，无建筑 = CREATE
                    imp.setType(buildingId == null 
                        ? PatchImpact.ImpactType.CREATE 
                        : PatchImpact.ImpactType.MODIFY);
                    return imp;
                }
            );
            
            // 扩展边界
            impact.expandBounds(worldPos);
            
            // 分类语义部位
            SemanticPart part = SemanticClassifier.classify(worldPos, patch);
            impact.addAffectedPart(part);
            
            // 如果是移除操作，标记为 REMOVE
            if (BlockPatch.REMOVE.equals(patch.action()) && buildingId != null) {
                impact.setType(PatchImpact.ImpactType.REMOVE);
            }
        }
        
        // 判断是否为扩建（修改已有建筑但超出原边界）
        for (PatchImpact impact : impacts.values()) {
            if (impact.getTargetBuilding() != null && 
                impact.getType() == PatchImpact.ImpactType.MODIFY) {
                
                ProjectMemory memory = memoryManager.getMemory(impact.getTargetBuilding().toString());
                if (memory != null && memory.getBounds() != null) {
                    BlockPos memMin = memory.getBounds().getMinPos();
                    BlockPos memMax = memory.getBounds().getMaxPos();
                    
                    if (memMin != null && memMax != null && 
                        impact.getMinPos() != null && impact.getMaxPos() != null) {
                        
                        // 检查是否超出原边界
                        if (impact.getMinPos().getX() < memMin.getX() ||
                            impact.getMinPos().getY() < memMin.getY() ||
                            impact.getMinPos().getZ() < memMin.getZ() ||
                            impact.getMaxPos().getX() > memMax.getX() ||
                            impact.getMaxPos().getY() > memMax.getY() ||
                            impact.getMaxPos().getZ() > memMax.getZ()) {
                            
                            impact.setType(PatchImpact.ImpactType.EXTEND);
                        }
                    }
                }
            }
        }
        
        List<PatchImpact> result = new ArrayList<>(impacts.values());
        FormacraftMod.LOGGER.debug("PatchDiffAnalyzer: analyzed {} patches, found {} impacts", 
            patches.size(), result.size());
        
        return result;
    }
}

