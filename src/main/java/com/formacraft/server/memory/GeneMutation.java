package com.formacraft.server.memory;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 基因变更记录：记录建筑"基因"级别的语义变更
 * 
 * 注意：这不是 BlockPatch，而是语义级的变更描述
 */
public record GeneMutation(
    /** 目标建筑 UUID（null 表示新建筑） */
    UUID buildingId,
    
    /** 变更原因/描述 */
    String reason,
    
    /** 受影响的语义部位 */
    Set<SemanticPart> affectedParts,
    
    /** 基因变更的增量（key-value 对） */
    Map<String, Object> geneDelta
) {
    public GeneMutation {
        // 确保不可变集合
        affectedParts = affectedParts != null ? Set.copyOf(affectedParts) : Set.of();
        geneDelta = geneDelta != null ? Map.copyOf(geneDelta) : Map.of();
    }
}

