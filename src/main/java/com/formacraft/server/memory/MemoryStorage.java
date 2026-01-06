package com.formacraft.server.memory;

import com.formacraft.common.json.JsonUtil;
import com.formacraft.FormacraftMod;
import net.minecraft.server.MinecraftServer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 记忆存储管理器
 * 负责将 ProjectMemory 保存到世界存档目录
 * 路径：saves/WorldName/formacraft/memory/
 */
public class MemoryStorage {
    
    /**
     * 获取记忆目录路径
     * 使用运行目录（简化实现，避免版本兼容性问题）
     * 记忆将保存在运行目录下的 formacraft/memory 文件夹
     */
    private static Path getMemoryDir(MinecraftServer server) {
        // 使用运行目录（与 BlueprintStorage 保持一致）
        return java.nio.file.Paths.get(".").resolve("formacraft/memory");
    }
    
    /**
     * 确保记忆目录存在
     */
    public static void ensureDir(MinecraftServer server) throws IOException {
        Path dir = getMemoryDir(server);
        if (!Files.exists(dir)) {
            Files.createDirectories(dir);
            FormacraftMod.LOGGER.info("Created memory directory: {}", dir);
        }
    }
    
    /**
     * 保存记忆到文件
     * 文件名格式：project_{uuid}.json
     */
    public static void saveMemory(MinecraftServer server, ProjectMemory memory) throws IOException {
        ensureDir(server);
        
        if (memory == null || memory.getUuid() == null) {
            throw new IllegalArgumentException("Invalid memory: UUID is required");
        }
        
        String fileName = "project_" + sanitizeUuid(memory.getUuid()) + ".json";
        Path path = getMemoryDir(server).resolve(fileName);
        
        String json = JsonUtil.toJson(memory);
        Files.writeString(path, json, StandardCharsets.UTF_8);
        
        FormacraftMod.LOGGER.info("Saved memory: {} ({}) to {}", memory.getName(), memory.getUuid(), path);
    }
    
    /**
     * 从文件加载记忆
     */
    public static ProjectMemory loadMemory(MinecraftServer server, String uuid) throws IOException {
        ensureDir(server);
        
        String fileName = "project_" + sanitizeUuid(uuid) + ".json";
        Path path = getMemoryDir(server).resolve(fileName);
        
        if (!Files.exists(path)) {
            return null;
        }
        
        String json = Files.readString(path, StandardCharsets.UTF_8);
        ProjectMemory memory = JsonUtil.fromJson(json, ProjectMemory.class);
        
        FormacraftMod.LOGGER.info("Loaded memory: {} from {}", uuid, path);
        return memory;
    }
    
    /**
     * 列出所有记忆的 UUID
     */
    public static List<String> listMemories(MinecraftServer server) throws IOException {
        ensureDir(server);
        Path dir = getMemoryDir(server);
        
        List<String> result = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "project_*.json")) {
            for (Path path : stream) {
                String fileName = path.getFileName().toString();
                // 提取 UUID：project_{uuid}.json -> {uuid}
                if (fileName.startsWith("project_") && fileName.endsWith(".json")) {
                    String uuid = fileName.substring(8, fileName.length() - 5);
                    result.add(uuid);
                }
            }
        }
        
        FormacraftMod.LOGGER.info("Listed {} memories", result.size());
        return result;
    }
    
    /**
     * 删除记忆
     */
    public static boolean deleteMemory(MinecraftServer server, String uuid) throws IOException {
        ensureDir(server);
        
        String fileName = "project_" + sanitizeUuid(uuid) + ".json";
        Path path = getMemoryDir(server).resolve(fileName);
        
        if (!Files.exists(path)) {
            return false;
        }
        
        Files.delete(path);
        FormacraftMod.LOGGER.info("Deleted memory: {}", uuid);
        return true;
    }
    
    /**
     * 清理 UUID，防止路径遍历攻击
     */
    private static String sanitizeUuid(String uuid) {
        if (uuid == null) {
            throw new IllegalArgumentException("UUID cannot be null");
        }
        // 移除所有非字母数字、连字符的字符
        String sanitized = uuid.replaceAll("[^a-zA-Z0-9-]", "");
        // 验证 UUID 格式（基本检查）
        if (sanitized.length() != 36 || !sanitized.matches("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")) {
            throw new IllegalArgumentException("Invalid UUID format: " + uuid);
        }
        return sanitized;
    }
    
    /**
     * 加载所有记忆到内存（用于索引构建）
     */
    public static List<ProjectMemory> loadAllMemories(MinecraftServer server) throws IOException {
        List<String> uuids = listMemories(server);
        List<ProjectMemory> memories = new ArrayList<>();
        
        for (String uuid : uuids) {
            try {
                ProjectMemory memory = loadMemory(server, uuid);
                if (memory != null) {
                    memories.add(memory);
                }
            } catch (Exception e) {
                FormacraftMod.LOGGER.warn("Failed to load memory {}: {}", uuid, e.getMessage());
            }
        }
        
        return memories;
    }
}

