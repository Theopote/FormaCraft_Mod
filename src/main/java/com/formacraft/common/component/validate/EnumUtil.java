package com.formacraft.common.component.validate;

/**
 * 枚举解析工具（安全解析，避免 NPE）。
 */
public final class EnumUtil {
    private EnumUtil() {}

    /**
     * 安全解析枚举（忽略大小写，trim）
     */
    public static <E extends Enum<E>> E parseEnum(Class<E> type, String raw) {
        if (raw == null) return null;
        try {
            return Enum.valueOf(type, raw.trim().toUpperCase());
        } catch (Exception ignored) {
            return null;
        }
    }

    /**
     * 检查字符串是否为空（null 或空白）
     */
    public static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    /**
     * 安全转换为大写（null 安全）
     */
    public static String safeUpper(String s) {
        return s == null ? null : s.trim().toUpperCase();
    }
}
