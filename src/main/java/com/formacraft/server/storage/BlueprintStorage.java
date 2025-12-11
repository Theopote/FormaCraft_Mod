package com.formacraft.server.storage;

import com.formacraft.common.model.blueprint.Blueprint;
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
 * 蓝图存储系统
 * 负责蓝图的保存、加载和列表操作
 */
public class BlueprintStorage {

    /**
     * 获取蓝图目录路径
     */
    private static Path getDir(MinecraftServer server) {
        // 使用服务器的世界保存路径
        // 回退方案：使用运行目录（简化实现，避免版本兼容性问题）
        // 蓝图将保存在运行目录下的 formacraft/blueprints 文件夹
        Path fallbackPath = java.nio.file.Paths.get(".").resolve("formacraft/blueprints");
        return fallbackPath;
    }

    /**
     * 确保蓝图目录存在
     */
    public static void ensureDir(MinecraftServer server) throws IOException {
        Path dir = getDir(server);
        if (!Files.exists(dir)) {
            Files.createDirectories(dir);
            FormacraftMod.LOGGER.info("Created blueprint directory: {}", dir);
        }
    }

    /**
     * 保存蓝图到文件
     */
    public static void saveBlueprint(MinecraftServer server, Blueprint bp) throws IOException {
        ensureDir(server);
        
        // 验证蓝图名称（防止路径遍历攻击）
        String name = sanitizeName(bp.getName());
        if (name == null || name.isEmpty()) {
            throw new IllegalArgumentException("Invalid blueprint name");
        }
        
        Path path = getDir(server).resolve(name + ".json");
        String json = JsonUtil.toJson(bp);
        
        Files.writeString(path, json, StandardCharsets.UTF_8);
        FormacraftMod.LOGGER.info("Saved blueprint: {} to {}", name, path);
    }

    /**
     * 从文件加载蓝图
     */
    public static Blueprint loadBlueprint(MinecraftServer server, String name) throws IOException {
        ensureDir(server);
        
        // 验证蓝图名称
        String sanitized = sanitizeName(name);
        if (sanitized == null || sanitized.isEmpty()) {
            throw new IllegalArgumentException("Invalid blueprint name");
        }
        
        Path path = getDir(server).resolve(sanitized + ".json");
        if (!Files.exists(path)) {
            return null;
        }

        String json = Files.readString(path, StandardCharsets.UTF_8);
        Blueprint bp = JsonUtil.fromJson(json, Blueprint.class);
        FormacraftMod.LOGGER.info("Loaded blueprint: {} from {}", name, path);
        return bp;
    }

    /**
     * 列出所有可用的蓝图
     */
    public static List<String> listBlueprints(MinecraftServer server) throws IOException {
        ensureDir(server);
        Path dir = getDir(server);

        List<String> result = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*.json")) {
            for (Path path : stream) {
                String fileName = path.getFileName().toString();
                // 移除 .json 扩展名
                String name = fileName.substring(0, fileName.length() - 5);
                result.add(name);
            }
        }
        
        FormacraftMod.LOGGER.info("Listed {} blueprints", result.size());
        return result;
    }

    /**
     * 删除蓝图
     */
    public static boolean deleteBlueprint(MinecraftServer server, String name) throws IOException {
        ensureDir(server);
        
        String sanitized = sanitizeName(name);
        if (sanitized == null || sanitized.isEmpty()) {
            return false;
        }
        
        Path path = getDir(server).resolve(sanitized + ".json");
        if (!Files.exists(path)) {
            return false;
        }
        
        Files.delete(path);
        FormacraftMod.LOGGER.info("Deleted blueprint: {}", name);
        return true;
    }

    /**
     * 清理蓝图名称，防止路径遍历攻击
     */
    private static String sanitizeName(String name) {
        if (name == null) {
            return null;
        }
        // 移除所有非字母数字、下划线、连字符的字符
        String sanitized = name.replaceAll("[^a-zA-Z0-9_-]", "");
        // 限制长度
        if (sanitized.length() > 64) {
            sanitized = sanitized.substring(0, 64);
        }
        return sanitized;
    }
}

