package com.formacraft.common.component.query;

import com.formacraft.common.component.ComponentCategory;
import com.formacraft.common.component.ComponentRequest;

import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 将 legacy {@code component_request} 字段（category/tags/approx_size）转换为 {@link ComponentQuery}，
 * 以便统一走 {@link ComponentRetriever} 检索链路。
 */
public final class ComponentRequestConverter {
    private ComponentRequestConverter() {}

    public static ComponentQuery fromRequest(ComponentRequest req) {
        if (req == null) {
            return null;
        }
        ComponentQuery query = new ComponentQuery();
        query.semantic = new ComponentQuery.Semantic();
        query.semantic.role = roleFromCategory(req.category);
        if (req.tags != null && !req.tags.isEmpty()) {
            query.semantic.tags = new HashSet<>(req.tags);
        }

        if (req.approxW > 0 || req.approxH > 0 || req.approxD > 0) {
            query.geometry = new ComponentQuery.Geometry();
            query.geometry.tolerance = 2;
            if (req.approxW > 0) {
                query.geometry.openingWidth = req.approxW;
            }
            if (req.approxH > 0) {
                query.geometry.openingHeight = req.approxH;
            }
            if (isOpeningCategory(req.category)) {
                query.geometry.requiresOpening = true;
            }
        }
        return query;
    }

    public static ComponentQuery fromLegacyMap(Map<String, Object> reqMap, String prefix) {
        if (reqMap == null) {
            return null;
        }
        String px = prefix == null ? "" : prefix;

        ComponentRequest req = new ComponentRequest();
        String cat = getString(reqMap, px + "category", px + "type");
        if (cat != null) {
            try {
                req.category = ComponentCategory.valueOf(cat.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ignored) {
                req.category = null;
            }
        }
        Set<String> tags = parseTags(reqMap.get(px + "tags"));
        req.tags = tags.isEmpty() ? null : tags;

        Object approx = reqMap.get(px + "approx_size");
        if (approx instanceof Map<?, ?> am) {
            req.approxW = getInt(am, -1, "w", "width");
            req.approxH = getInt(am, -1, "h", "height");
            req.approxD = getInt(am, -1, "d", "depth");
        } else {
            req.approxW = getInt(reqMap, -1, px + "approxW", px + "approx_w");
            req.approxH = getInt(reqMap, -1, px + "approxH", px + "approx_h");
            req.approxD = getInt(reqMap, -1, px + "approxD", px + "approx_d");
        }

        boolean hasLegacyHints = req.category != null
                || (req.tags != null && !req.tags.isEmpty())
                || req.approxW > 0 || req.approxH > 0 || req.approxD > 0
                || getString(reqMap, px + "semantic", px + "role") != null;

        if (!hasLegacyHints) {
            return null;
        }

        ComponentQuery query = fromRequest(req);
        if (query == null) {
            query = new ComponentQuery();
            query.semantic = new ComponentQuery.Semantic();
        }

        String role = getString(reqMap, px + "semantic", px + "role");
        if (role != null && !role.isBlank()) {
            query.semantic.role = role.trim().toLowerCase(Locale.ROOT);
        } else if (query.semantic.role == null || query.semantic.role.isBlank()) {
            query.semantic.role = inferRoleFromTags(req.tags);
        }

        String placement = getString(reqMap, px + "placement", px + "context.placement");
        if (placement != null && !placement.isBlank()) {
            if (query.context == null) {
                query.context = new ComponentQuery.Context();
            }
            query.context.placement = placement.trim().toLowerCase(Locale.ROOT);
        }

        String side = getString(reqMap, px + "side", px + "context.side");
        if (side != null && !side.isBlank()) {
            if (query.context == null) {
                query.context = new ComponentQuery.Context();
            }
            query.context.side = side.trim().toLowerCase(Locale.ROOT);
        }

        String styleProfile = getString(reqMap, px + "semantic_style_id", px + "style_id", px + "styleId");
        if (styleProfile != null && !styleProfile.isBlank()) {
            if (query.style == null) {
                query.style = new ComponentQuery.Style();
            }
            query.style.styleProfile = styleProfile.trim();
        }

        return query;
    }

    public static long stableSeed(ComponentRequest req) {
        long seed = 1125899906842597L;
        if (req == null) {
            return seed;
        }
        if (req.category != null) {
            seed = 31 * seed + req.category.name().hashCode();
        }
        if (req.tags != null) {
            seed = 31 * seed + req.tags.hashCode();
        }
        seed = 31 * seed + req.approxW;
        seed = 31 * seed + req.approxH;
        seed = 31 * seed + req.approxD;
        return seed;
    }

    private static String roleFromCategory(ComponentCategory category) {
        if (category == null) {
            return null;
        }
        return switch (category) {
            case DOOR -> "door";
            case WINDOW -> "window";
            case BALCONY -> "balcony";
            case RAILING -> "railing";
            case PANEL -> "panel";
            case COLUMN, BRACKET -> "column";
            case STAIRS -> "stairs";
            case ARCH, ORNAMENT, ROOF_DETAIL -> "ornament";
            default -> null;
        };
    }

    private static boolean isOpeningCategory(ComponentCategory category) {
        return category == ComponentCategory.DOOR || category == ComponentCategory.WINDOW;
    }

    private static String inferRoleFromTags(Set<String> tags) {
        if (tags == null || tags.isEmpty()) {
            return null;
        }
        for (String tag : tags) {
            if (tag == null) continue;
            String t = tag.toLowerCase(Locale.ROOT);
            if (t.contains("door") || t.contains("门")) return "door";
            if (t.contains("window") || t.contains("窗")) return "window";
            if (t.contains("balcony") || t.contains("阳台")) return "balcony";
            if (t.contains("railing") || t.contains("栏杆")) return "railing";
            if (t.contains("panel") || t.contains("栏板")) return "panel";
            if (t.contains("column") || t.contains("柱")) return "column";
        }
        return "decoration";
    }

    private static Set<String> parseTags(Object v) {
        Set<String> out = new HashSet<>();
        if (v == null) {
            return out;
        }
        if (v instanceof Iterable<?> it) {
            for (Object item : it) {
                if (item == null) continue;
                String s = String.valueOf(item).trim();
                if (!s.isEmpty()) out.add(s);
            }
            return out;
        }
        for (String part : String.valueOf(v).split(",")) {
            String t = part.trim();
            if (!t.isEmpty()) out.add(t);
        }
        return out;
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

    private static int getInt(Map<?, ?> m, int def, String... keys) {
        if (m == null || keys == null) return def;
        for (String k : keys) {
            if (k == null) continue;
            Object v = m.get(k);
            if (v == null) continue;
            if (v instanceof Number n) return n.intValue();
            try {
                return Integer.parseInt(String.valueOf(v).trim());
            } catch (NumberFormatException ignored) {
            }
        }
        return def;
    }
}
