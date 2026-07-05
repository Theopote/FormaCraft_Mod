package com.formacraft.common.archetype;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 地标别名严格匹配：避免「大教堂」「城堡」等宽泛子串误触发 MODULE 强制路由。
 */
public final class LandmarkAliasMatcher {

    /**
     * 单独出现时不应把更具体的建筑名（如「圣家族大教堂」）路由到通用地标的宽泛别名。
     */
    private static final Set<String> BROAD_ALIASES = Set.of(
            "大教堂",
            "城堡",
            "水镇",
            "神社",
            "茶室",
            "飞艇",
            "树屋",
            "神庙",
            "佛塔",
            "埃菲尔",
            "eiffel",
            "cathedral",
            "castle",
            "shrine",
            "pagoda",
            "stadium",
            "arena",
            "体育馆",
            "体育场",
            "airship",
            "tea house"
    );

    private LandmarkAliasMatcher() {}

    public record Match(String moduleId, String matchedAlias, boolean explicit) {}

    /**
     * 在用户意图中查找与模块别名列表的最长匹配；宽泛别名在存在更具体措辞时不算显式匹配。
     */
    public static Match matchIntent(String intent, String moduleId, List<String> aliases) {
        if (intent == null || intent.isBlank() || moduleId == null || moduleId.isBlank()) {
            return null;
        }
        String lower = intent.toLowerCase(Locale.ROOT).trim();
        String id = moduleId.trim().toLowerCase(Locale.ROOT);
        if (lower.contains(id)) {
            return new Match(id, id, true);
        }
        if (aliases == null || aliases.isEmpty()) {
            return null;
        }

        String bestAlias = null;
        for (String alias : aliases) {
            if (alias == null || alias.isBlank()) continue;
            String a = alias.toLowerCase(Locale.ROOT).trim();
            if (a.length() < 2) continue;
            if (!lower.contains(a)) continue;
            if (bestAlias == null || a.length() > bestAlias.length()) {
                bestAlias = a;
            }
        }
        if (bestAlias == null) {
            return null;
        }

        boolean explicit = isExplicitAliasMatch(lower, bestAlias);
        return explicit ? new Match(id, bestAlias, true) : null;
    }

    /**
     * 与 {@link #matchIntent} 相同逻辑，但用于 metrics：宽泛命中时返回 {@code explicit=false} 而非 {@code null}。
     */
    public static Match matchIntentLenient(String intent, String moduleId, List<String> aliases) {
        if (intent == null || intent.isBlank() || moduleId == null || moduleId.isBlank()) {
            return null;
        }
        String lower = intent.toLowerCase(Locale.ROOT).trim();
        String id = moduleId.trim().toLowerCase(Locale.ROOT);
        if (lower.contains(id)) {
            return new Match(id, id, true);
        }
        if (aliases == null || aliases.isEmpty()) {
            return null;
        }

        String bestAlias = aliases.stream()
                .filter(a -> a != null && !a.isBlank())
                .map(a -> a.toLowerCase(Locale.ROOT).trim())
                .filter(a -> a.length() >= 2 && lower.contains(a))
                .max(Comparator.comparingInt(String::length))
                .orElse(null);
        if (bestAlias == null) {
            return null;
        }
        return new Match(id, bestAlias, isExplicitAliasMatch(lower, bestAlias));
    }

    public static boolean isExplicitAliasMatch(String intentLower, String matchedAlias) {
        if (intentLower == null || matchedAlias == null || matchedAlias.isBlank()) {
            return false;
        }
        if (!isBroadAlias(matchedAlias)) {
            return true;
        }
        return !hasSpecificRemainder(intentLower, matchedAlias);
    }

    public static boolean isBroadAlias(String alias) {
        if (alias == null || alias.isBlank()) return false;
        return BROAD_ALIASES.contains(alias.toLowerCase(Locale.ROOT).trim());
    }

    /**
     * 去掉命中别名与常见动词后，是否仍有 ≥2 字的建筑专属名（如「圣家族」）。
     */
    public static boolean hasSpecificRemainder(String intentLower, String matchedAlias) {
        if (intentLower == null || matchedAlias == null) return false;
        String remainder = intentLower.replace(matchedAlias, " ").trim();
        remainder = remainder.replaceAll(
                "[的了一座栋座座生成建造帮我请做来个在锚点位置\\s\\-_,，。！？'\"]+",
                ""
        ).trim();
        return remainder.length() >= 2;
    }
}
