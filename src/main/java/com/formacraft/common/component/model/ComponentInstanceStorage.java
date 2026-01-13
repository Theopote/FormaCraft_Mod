package com.formacraft.common.component.model;

import com.formacraft.common.json.JsonUtil;

import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Instance（实例）存储：与世界存档绑定。
 * <p>
 * saves/&lt;world&gt;/formacraft/instances/&lt;uuid&gt;.json
 */
public final class ComponentInstanceStorage {
    private ComponentInstanceStorage() {}

    public static Path getWorldInstanceDir(Path worldDir) {
        return worldDir.resolve("formacraft").resolve("instances");
    }

    public static void saveInstance(Path worldDir, ComponentInstance inst) {
        if (worldDir == null || inst == null) return;
        if (inst.uuid == null || inst.uuid.isBlank()) return;
        try {
            Path dir = getWorldInstanceDir(worldDir);
            Files.createDirectories(dir);
            Path f = dir.resolve(inst.uuid.trim() + ".json");
            try (Writer w = Files.newBufferedWriter(f, StandardCharsets.UTF_8)) {
                JsonUtil.get().toJson(inst, w);
            }
        } catch (Throwable ignored) {}
    }

    public static ComponentInstance loadInstance(Path worldDir, String uuid) {
        if (worldDir == null || uuid == null || uuid.isBlank()) return null;
        Path f = getWorldInstanceDir(worldDir).resolve(uuid.trim() + ".json");
        if (!Files.exists(f)) return null;
        try (Reader r = Files.newBufferedReader(f, StandardCharsets.UTF_8)) {
            return JsonUtil.get().fromJson(r, ComponentInstance.class);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * v1：简单列出所有实例（仅文件名解析，best-effort）。
     */
    public static List<String> listInstanceUuids(Path worldDir) {
        List<String> out = new ArrayList<>();
        if (worldDir == null) return out;
        Path dir = getWorldInstanceDir(worldDir);
        if (!Files.exists(dir) || !Files.isDirectory(dir)) return out;
        try (var stream = Files.newDirectoryStream(dir, "*.json")) {
            for (Path p : stream) {
                if (p == null) continue;
                String fn = p.getFileName().toString();
                if (fn.endsWith(".json")) out.add(fn.substring(0, fn.length() - 5));
            }
        } catch (Throwable ignored) {}
        return out;
    }
}

