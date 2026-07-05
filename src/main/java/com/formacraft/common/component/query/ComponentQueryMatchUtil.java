package com.formacraft.common.component.query;

import java.util.Collection;
import java.util.Locale;

/**
 * ComponentQuery 匹配工具：大小写不敏感、标签子串、role 推断。
 */
public final class ComponentQueryMatchUtil {
    private ComponentQueryMatchUtil() {}

    public static boolean equalsIgnoreCase(String a, String b) {
        if (a == null || b == null) return false;
        return a.equalsIgnoreCase(b);
    }

    public static boolean tagMatches(String queryTag, Collection<String> componentTags) {
        if (queryTag == null || queryTag.isBlank() || componentTags == null) {
            return false;
        }
        String q = queryTag.trim().toLowerCase(Locale.ROOT);
        for (String tag : componentTags) {
            if (tag == null || tag.isBlank()) continue;
            String t = tag.trim().toLowerCase(Locale.ROOT);
            if (t.equals(q) || t.contains(q) || q.contains(t)) {
                return true;
            }
        }
        return false;
    }

    public static boolean roleImpliedByTags(String role, Collection<String> tags) {
        if (role == null || role.isBlank() || tags == null || tags.isEmpty()) {
            return false;
        }
        String r = role.trim().toLowerCase(Locale.ROOT);
        for (String tag : tags) {
            if (tag == null) continue;
            String t = tag.trim().toLowerCase(Locale.ROOT);
            if (t.contains(r) || r.contains(t)) {
                return true;
            }
        }
        return switch (r) {
            case "door" -> tagMatches("门", tags);
            case "window" -> tagMatches("窗", tags);
            case "balcony" -> tagMatches("阳台", tags);
            case "railing" -> tagMatches("栏杆", tags) || tagMatches("guard", tags);
            case "panel" -> tagMatches("栏板", tags);
            case "column" -> tagMatches("柱", tags) || tagMatches("pillar", tags);
            default -> false;
        };
    }

    public static boolean placementMatches(String queryPlacement, Collection<String> allowed) {
        if (queryPlacement == null || queryPlacement.isBlank()) {
            return true;
        }
        if (allowed == null || allowed.isEmpty()) {
            return false;
        }
        String q = queryPlacement.trim().toLowerCase(Locale.ROOT);
        for (String p : allowed) {
            if (p != null && p.equalsIgnoreCase(q)) {
                return true;
            }
        }
        return false;
    }
}
