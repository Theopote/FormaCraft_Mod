package com.formacraft.server.memory;

import com.formacraft.common.model.build.BuildingSpec;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Mutation 构建器：从 PatchImpact 构建 GeneMutation
 * 
 * 核心职责：将"方块级变更"转换为"基因级变更"
 */
public final class MutationBuilder {
    private MutationBuilder() {}
    
    /**
     * 从 PatchImpact 构建 GeneMutation
     * 
     * @param impact Patch 影响
     * @param memory 建筑记忆（如果存在）
     * @return 基因变更记录
     */
    public static GeneMutation build(PatchImpact impact, ProjectMemory memory) {
        if (impact == null) {
            return null;
        }
        
        UUID buildingId = impact.getTargetBuilding();
        Set<SemanticPart> affectedParts = impact.getAffectedParts();
        
        Map<String, Object> delta = new HashMap<>();
        
        // 根据影响的语义部位推断基因变更
        if (affectedParts.contains(SemanticPart.ROOF)) {
            delta.put("roof_modified", true);
            delta.put("last_roof_modification", System.currentTimeMillis());
        }
        
        if (affectedParts.contains(SemanticPart.WALL)) {
            delta.put("wall_modified", true);
        }
        
        if (affectedParts.contains(SemanticPart.PATH)) {
            delta.put("has_paths", true);
            delta.put("path_modified", true);
        }
        
        if (affectedParts.contains(SemanticPart.DOOR)) {
            delta.put("entrance_modified", true);
        }
        
        if (affectedParts.contains(SemanticPart.WINDOW)) {
            delta.put("windows_modified", true);
        }
        
        if (affectedParts.contains(SemanticPart.DECORATION)) {
            delta.put("decoration_added", true);
        }
        
        // 根据影响类型添加标记
        switch (impact.getType()) {
            case CREATE -> {
                delta.put("is_new_building", true);
                delta.put("created_via_patch", true);
            }
            case EXTEND -> {
                delta.put("extended", true);
                delta.put("last_extension", System.currentTimeMillis());
            }
            case REMOVE -> {
                delta.put("partially_removed", true);
            }
            case MODIFY -> {
                delta.put("modified", true);
                delta.put("last_modification", System.currentTimeMillis());
            }
        }
        
        // 如果有原始记忆，可以提取更多信息
        if (memory != null && memory.getGeneData() != null) {
            BuildingSpec spec = memory.getGeneData();
            
            // 记录原始风格（用于风格继承）
            if (spec.getStyle() != null) {
                delta.put("original_style", spec.getStyle().name());
            }
            
            if (spec.getType() != null) {
                delta.put("original_type", spec.getType().name());
            }
        }
        
        String reason = buildReason(impact, memory);
        
        return new GeneMutation(
            buildingId,
            reason,
            affectedParts,
            delta
        );
    }
    
    /**
     * 构建变更原因描述
     */
    private static String buildReason(PatchImpact impact, ProjectMemory memory) {
        StringBuilder sb = new StringBuilder();
        
        switch (impact.getType()) {
            case CREATE -> sb.append("Created new building via patch");
            case MODIFY -> sb.append("Modified existing building via patch");
            case EXTEND -> sb.append("Extended existing building via patch");
            case REMOVE -> sb.append("Partially removed building via patch");
        }
        
        if (!impact.getAffectedParts().isEmpty()) {
            sb.append(" (affected: ");
            sb.append(String.join(", ", 
                impact.getAffectedParts().stream()
                    .map(Enum::name)
                    .toList()));
            sb.append(")");
        }
        
        if (memory != null && memory.getName() != null) {
            sb.append(" - ").append(memory.getName());
        }
        
        return sb.toString();
    }
}

