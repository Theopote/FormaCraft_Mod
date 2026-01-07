package com.formacraft.ai.prompt;

import com.formacraft.server.memory.ProjectMemory;

import java.util.List;

/**
 * 记忆上下文：存储从 MemoryManager 检索到的相关建筑记忆
 * 用于 RAG（Retrieval-Augmented Generation）功能
 */
public class MemoryContext {
    /** 检索到的相关建筑记忆列表 */
    public final List<ProjectMemory> referenced;
    
    /** 记忆的文本摘要（用于拼接到 Prompt 中） */
    public final String summary;
    
    public MemoryContext(List<ProjectMemory> referenced, String summary) {
        this.referenced = referenced != null ? referenced : List.of();
        this.summary = summary != null ? summary : "";
    }
    
    /**
     * 检查是否有有效的记忆上下文
     */
    public boolean isEmpty() {
        return referenced.isEmpty() || summary.isEmpty();
    }
}

