package com.formacraft.server.memory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 语义索引（Semantic Index）
 * 基于倒排索引的关键词检索
 * 解决"它是什么？"的问题
 */
public class SemanticIndex {
    // 关键词 -> 包含该关键词的项目 UUID 集合
    private final Map<String, Set<String>> invertedIndex = new ConcurrentHashMap<>();
    
    // UUID -> ProjectMemory 的快速查找
    private final Map<String, ProjectMemory> memoryCache = new ConcurrentHashMap<>();
    
    /**
     * 添加记忆到索引
     */
    public void addMemory(ProjectMemory memory) {
        if (memory == null || memory.getUuid() == null) {
            return;
        }
        
        String uuid = memory.getUuid();
        memoryCache.put(uuid, memory);
        
        // 从名称中提取关键词
        if (memory.getName() != null) {
            addKeywords(uuid, extractKeywords(memory.getName()));
        }
        
        // 从描述中提取关键词
        if (memory.getDescription() != null) {
            addKeywords(uuid, extractKeywords(memory.getDescription()));
        }
        
        // 从标签中提取关键词
        if (memory.getTags() != null) {
            for (String tag : memory.getTags()) {
                addKeyword(uuid, tag.toLowerCase());
            }
        }
        
        // 从 BuildingSpec 中提取关键词
        if (memory.getGeneData() != null) {
            if (memory.getGeneData().getType() != null) {
                addKeyword(uuid, memory.getGeneData().getType().name().toLowerCase());
            }
            if (memory.getGeneData().getStyle() != null) {
                addKeyword(uuid, memory.getGeneData().getStyle().name().toLowerCase());
            }
        }
    }
    
    /**
     * 从索引中移除记忆
     */
    public void removeMemory(String uuid) {
        if (uuid == null) {
            return;
        }
        
        ProjectMemory memory = memoryCache.remove(uuid);
        if (memory == null) {
            return;
        }
        
        // 从所有关键词的索引中移除
        List<String> keywordsToRemove = new ArrayList<>();
        for (Map.Entry<String, Set<String>> entry : invertedIndex.entrySet()) {
            entry.getValue().remove(uuid);
            if (entry.getValue().isEmpty()) {
                keywordsToRemove.add(entry.getKey());
            }
        }
        
        for (String keyword : keywordsToRemove) {
            invertedIndex.remove(keyword);
        }
    }
    
    /**
     * 添加单个关键词
     */
    private void addKeyword(String uuid, String keyword) {
        if (keyword == null || keyword.isEmpty()) {
            return;
        }
        
        keyword = keyword.toLowerCase().trim();
        if (keyword.isEmpty()) {
            return;
        }
        
        invertedIndex.computeIfAbsent(keyword, k -> ConcurrentHashMap.newKeySet()).add(uuid);
    }
    
    /**
     * 添加多个关键词
     */
    private void addKeywords(String uuid, List<String> keywords) {
        for (String keyword : keywords) {
            addKeyword(uuid, keyword);
        }
    }
    
    /**
     * 从文本中提取关键词
     */
    private List<String> extractKeywords(String text) {
        if (text == null || text.isEmpty()) {
            return Collections.emptyList();
        }
        
        // 简单的关键词提取：按空格和标点分割
        String[] words = text.toLowerCase()
                .replaceAll("[^\\p{L}\\p{N}\\s]", " ")
                .split("\\s+");
        
        List<String> keywords = new ArrayList<>();
        for (String word : words) {
            word = word.trim();
            // 过滤掉太短的词（小于2个字符）和常见停用词
            if (word.length() >= 2 && !isStopWord(word)) {
                keywords.add(word);
            }
        }
        
        return keywords;
    }
    
    /**
     * 检查是否为停用词
     */
    private boolean isStopWord(String word) {
        // 简单的停用词列表（可以扩展）
        Set<String> stopWords = Set.of(
            "的", "是", "在", "有", "和", "与", "或", "但", "而", "这", "那",
            "the", "a", "an", "and", "or", "but", "in", "on", "at", "to", "for",
            "of", "with", "by", "from", "as", "is", "was", "are", "were"
        );
        return stopWords.contains(word.toLowerCase());
    }
    
    /**
     * 根据关键词搜索（AND 逻辑：所有关键词都必须匹配）
     */
    public List<ProjectMemory> searchAnd(String... keywords) {
        if (keywords == null || keywords.length == 0) {
            return Collections.emptyList();
        }
        
        List<Set<String>> uuidSets = new ArrayList<>();
        for (String keyword : keywords) {
            if (keyword == null || keyword.isEmpty()) {
                continue;
            }
            
            String normalized = keyword.toLowerCase().trim();
            Set<String> uuids = invertedIndex.get(normalized);
            if (uuids == null || uuids.isEmpty()) {
                // 如果任何一个关键词没有匹配，返回空结果
                return Collections.emptyList();
            }
            uuidSets.add(uuids);
        }
        
        if (uuidSets.isEmpty()) {
            return Collections.emptyList();
        }
        
        // 取交集
        Set<String> intersection = new HashSet<>(uuidSets.get(0));
        for (int i = 1; i < uuidSets.size(); i++) {
            intersection.retainAll(uuidSets.get(i));
        }
        
        return intersection.stream()
                .map(memoryCache::get)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }
    
    /**
     * 根据关键词搜索（OR 逻辑：任一关键词匹配即可）
     */
    public List<ProjectMemory> searchOr(String... keywords) {
        if (keywords == null || keywords.length == 0) {
            return Collections.emptyList();
        }
        
        Set<String> union = new HashSet<>();
        for (String keyword : keywords) {
            if (keyword == null || keyword.isEmpty()) {
                continue;
            }
            
            String normalized = keyword.toLowerCase().trim();
            Set<String> uuids = invertedIndex.get(normalized);
            if (uuids != null) {
                union.addAll(uuids);
            }
        }
        
        return union.stream()
                .map(memoryCache::get)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }
    
    /**
     * 模糊搜索：包含关键词的项目
     */
    public List<ProjectMemory> searchContains(String query) {
        if (query == null || query.isEmpty()) {
            return Collections.emptyList();
        }
        
        String normalized = query.toLowerCase().trim();
        Set<String> results = new HashSet<>();
        
        // 检查所有索引关键词，看是否包含查询字符串
        for (String keyword : invertedIndex.keySet()) {
            if (keyword.contains(normalized) || normalized.contains(keyword)) {
                results.addAll(invertedIndex.get(keyword));
            }
        }
        
        return results.stream()
                .map(memoryCache::get)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }
    
    /**
     * 根据 UUID 获取记忆
     */
    public ProjectMemory getMemory(String uuid) {
        return memoryCache.get(uuid);
    }
    
    /**
     * 清空索引
     */
    public void clear() {
        invertedIndex.clear();
        memoryCache.clear();
    }
    
    /**
     * 获取索引中的记忆数量
     */
    public int size() {
        return memoryCache.size();
    }
}

