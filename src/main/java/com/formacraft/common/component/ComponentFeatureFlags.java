package com.formacraft.common.component;

import java.util.Locale;
import java.util.Map;

/**
 * 解析 component_request / group_request 中的特性开关。
 */
public final class ComponentFeatureFlags {
    private ComponentFeatureFlags() {}

    /**
     * semantic_skin 默认策略：
     * - 显式 true/false 优先；
     * - 若指定 style / 建筑已有 styleProfile，则默认 true（匹配风格）；
     * - 否则默认 false（保留原始方块）。
     */
    public static boolean resolveSemanticSkin(Map<?, ?> reqMap, String fallbackStyleProfile, String... keys) {
        Boolean explicit = getBoolNullable(reqMap, keys);
        if (explicit != null) {
            return explicit;
        }
        if (hasStyleHint(reqMap, fallbackStyleProfile)) {
            return true;
        }
        return false;
    }

    private static boolean hasStyleHint(Map<?, ?> reqMap, String fallbackStyleProfile) {
        if (fallbackStyleProfile != null && !fallbackStyleProfile.isBlank()) {
            return true;
        }
        if (reqMap == null) {
            return false;
        }
        return getString(reqMap,
                "semantic_style_id", "semanticStyleId", "style_id", "styleId",
                "host_semantic_style_id", "hostSemanticStyleId",
                "mount_semantic_style_id", "mountSemanticStyleId") != null;
    }

    private static Boolean getBoolNullable(Map<?, ?> m, String... keys) {
        if (m == null || keys == null) return null;
        for (String k : keys) {
            if (k == null) continue;
            Object v = m.get(k);
            if (v == null) continue;
            if (v instanceof Boolean b) return b;
            String s = String.valueOf(v).trim().toLowerCase(Locale.ROOT);
            if (s.equals("true") || s.equals("1") || s.equals("yes")) return true;
            if (s.equals("false") || s.equals("0") || s.equals("no")) return false;
        }
        return null;
    }

    private static String getString(Map<?, ?> m, String... keys) {
        if (m == null || keys == null) return null;
        for (String k : keys) {
            if (k == null) continue;
            Object v = m.get(k);
            if (v == null) continue;
            String s = String.valueOf(v).trim();
            if (!s.isEmpty()) return s;
        }
        return null;
    }
}
