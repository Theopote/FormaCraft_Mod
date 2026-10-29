package com.formacraft.common.component.query;

import java.util.Collection;
import java.util.Locale;
import java.text.Normalizer;

/**
 * Normalized tag identities for constraints; bounded phrase matches for discovery.
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
        String q = normalizeTag(queryTag);
        if (q.isEmpty()) return false;
        for (String tag : componentTags) {
            if (tag == null || tag.isBlank()) continue;
            String t = normalizeTag(tag);
            if (t.equals(q) || ("_" + t + "_").contains("_" + q + "_")
                    || q.codePoints().allMatch(c -> Character.UnicodeScript.of(c) == Character.UnicodeScript.HAN)
                    && t.contains(q)) {
                return true;
            }
        }
        return false;
    }

    /** No semantic aliases or hierarchy are invented by spelling normalization. */
    public static String normalizeTag(String tag) {
        if (tag == null) return "";
        return Normalizer.normalize(tag, Normalizer.Form.NFKC).strip().toLowerCase(Locale.ROOT)
                .replaceAll("[\\s\\p{Z}_\\p{Pd}]+", "_");
    }

    public static boolean tagIdentityMatches(String queryTag, Collection<String> tags) {
        String q = normalizeTag(queryTag);
        return !q.isEmpty() && tags != null && tags.stream().anyMatch(t -> q.equals(normalizeTag(t)));
    }

    /** Player-entered search stays forgiving; never use this for asset permissions. */
    public static boolean tagSearchMatches(String text, Collection<String> tags) {
        String q = normalizeTag(text);
        return !q.isEmpty() && tags != null && tags.stream().anyMatch(t -> normalizeTag(t).contains(q));
    }

    public static boolean roleImpliedByTags(String role, Collection<String> tags) {
        if (role == null || role.isBlank() || tags == null || tags.isEmpty()) {
            return false;
        }
        String r = normalizeTag(role);
        if (tagMatches(r, tags)) return true;
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
