package com.formacraft.common.component;

import java.nio.file.Path;
import java.util.regex.Pattern;

/**
 * 构件 ID 白名单与路径边界检查（单源真相）。
 * <p>
 * 禁止路径分隔符与 {@code .}，避免 {@code ..} 等目录逃逸。
 */
public final class ComponentIds {
    private ComponentIds() {}

    /** 保存/新建强制：小写字母数字开头，最长 64。 */
    public static final Pattern SAFE_ID = Pattern.compile("[a-z0-9][a-z0-9_-]{0,63}");

    /**
     * 加载时宽松：允许历史 ID 中的点号，但仍拒绝路径分隔与空段。
     * 不含 {@code ..} 字面量（点号单独出现时仍可能非法）。
     */
    private static final Pattern LEGACY_LOAD_ID = Pattern.compile("[a-z0-9][a-z0-9_.-]{0,63}");

    public static final int MAX_JSON_CHARS = 2 * 1024 * 1024;
    public static final int MAX_BLOCKS = 10_000;

    public static boolean isSafeId(String id) {
        return id != null && SAFE_ID.matcher(id).matches();
    }

    /**
     * 校验并规范化构件 ID（保存路径强制）。
     *
     * @throws IllegalArgumentException 非法 ID
     */
    public static String validateComponentId(String id) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("Invalid component ID: blank");
        }
        String trimmed = id.trim();
        if (!SAFE_ID.matcher(trimmed).matches()) {
            throw new IllegalArgumentException(
                    "Invalid component ID. Use [a-z0-9][a-z0-9_-]{0,63}. Got: " + trimmed);
        }
        return trimmed;
    }

    /**
     * 加载路径：允许含点号的历史 ID，但仍拒绝路径逃逸特征。
     *
     * @return 规范化 id，非法时返回 null
     */
    public static String tryNormalizeLoadId(String id) {
        if (id == null || id.isBlank()) return null;
        String trimmed = id.trim();
        if (trimmed.contains("/") || trimmed.contains("\\") || trimmed.contains("..")) {
            return null;
        }
        if (!LEGACY_LOAD_ID.matcher(trimmed).matches()) {
            return null;
        }
        return trimmed;
    }

    /**
     * 在 baseDir 下解析 {@code id + extension}，并保证结果落在 baseDir 内。
     *
     * @throws IllegalArgumentException ID 非法
     * @throws SecurityException 路径逃逸
     */
    public static Path resolveComponentFile(Path baseDir, String id, String extension) {
        String safeId = validateComponentId(id);
        return resolveUnderBase(baseDir, safeId + normalizeExt(extension));
    }

    /**
     * 加载用：ID 宽松校验后解析路径。
     *
     * @return 规范化路径，非法时返回 null
     */
    public static Path tryResolveComponentFile(Path baseDir, String id, String extension) {
        String loadId = tryNormalizeLoadId(id);
        if (loadId == null || baseDir == null) return null;
        try {
            return resolveUnderBase(baseDir, loadId + normalizeExt(extension));
        } catch (SecurityException e) {
            return null;
        }
    }

    private static String normalizeExt(String extension) {
        if (extension == null || extension.isBlank()) return ".json";
        return extension.startsWith(".") ? extension : "." + extension;
    }

    private static Path resolveUnderBase(Path baseDir, String fileName) {
        if (baseDir == null) {
            throw new IllegalArgumentException("baseDir is null");
        }
        if (fileName == null || fileName.isBlank()
                || fileName.contains("/") || fileName.contains("\\") || fileName.contains("..")) {
            throw new SecurityException("Invalid component file name: " + fileName);
        }
        Path base = baseDir.toAbsolutePath().normalize();
        Path target = base.resolve(fileName).normalize();
        if (!target.startsWith(base)) {
            throw new SecurityException("Invalid component path (escapes base): " + fileName);
        }
        return target;
    }
}
