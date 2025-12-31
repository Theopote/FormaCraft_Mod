package com.formacraft.server.assembly.validation;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * P0 "auto-fix / normalizer" for extra.assembly.
 *
 * Design goals:
 * - Deep-copy input (never mutate caller maps).
 * - Canonicalize common aliases and casing.
 * - Migrate common fields (e.g., facade.pattern -> facade.surfacePattern) for LLM stability.
 * - Emit WARNING issues describing each change (path + code).
 *
 * Non-goals (for now):
 * - Aggressive schema repair (inventing missing required data).
 * - Perfect normalization for every op ever.
 */
public final class AssemblySpecNormalizer {
    private AssemblySpecNormalizer() {}

    public static AssemblySpecNormalizeResult normalize(Object assemblyObj) {
        List<AssemblyValidationIssue> issues = new ArrayList<>();
        Object copied = deepCopy(assemblyObj);
        if (!(copied instanceof Map<?, ?> root)) {
            return new AssemblySpecNormalizeResult(copied, issues);
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> m = (Map<String, Object>) root;

        normalizeMap(m, "$", issues);
        return new AssemblySpecNormalizeResult(m, issues);
    }

    // ---------------- core traversal ----------------

    private static void normalizeMap(Map<String, Object> m, String path, List<AssemblyValidationIssue> issues) {
        if (m == null || m.isEmpty()) return;

        // Root-level: normalize known keys / casing
        normalizeKeyAlias(m, path, issues, "base_level", "baseLevel");

        // Facade migrations (component-side)
        Object facadeObj = m.get("facade");
        if (facadeObj instanceof Map<?, ?> fm) {
            @SuppressWarnings("unchecked")
            Map<String, Object> facade = (Map<String, Object>) fm;
            normalizeFacade(facade, path + ".facade", issues);
        }

        // Terrain adaptation normalization
        Object taObj = m.get("terrainAdaptation");
        if (taObj instanceof Map<?, ?> tm) {
            @SuppressWarnings("unchecked")
            Map<String, Object> ta = (Map<String, Object>) tm;
            normalizeTerrainAdaptation(ta, path + ".terrainAdaptation", issues);
        }

        // Normalize face/faces strings if present
        if (m.get("face") instanceof String s) {
            String norm = normalizeFacesString(s);
            if (!norm.equals(s)) {
                m.put("face", norm);
                issues.add(warn(path + ".face", "W_NORM_VALUE_CANON", "face 规范化: \"" + s + "\" -> \"" + norm + "\""));
            }
        }
        if (m.get("faces") instanceof String s) {
            String norm = normalizeFacesString(s);
            if (!norm.equals(s)) {
                m.put("faces", norm);
                issues.add(warn(path + ".faces", "W_NORM_VALUE_CANON", "faces 规范化: \"" + s + "\" -> \"" + norm + "\""));
            }
        }

        // Normalize endpoint strings "A.port"
        if (m.get("from") instanceof String s) {
            String norm = normalizeEndpointString(s);
            if (!norm.equals(s)) {
                m.put("from", norm);
                issues.add(warn(path + ".from", "W_NORM_ENDPOINT", "端点规范化: \"" + s + "\" -> \"" + norm + "\""));
            }
        }
        if (m.get("to") instanceof String s) {
            String norm = normalizeEndpointString(s);
            if (!norm.equals(s)) {
                m.put("to", norm);
                issues.add(warn(path + ".to", "W_NORM_ENDPOINT", "端点规范化: \"" + s + "\" -> \"" + norm + "\""));
            }
        }

        // Normalize connection/type/op/kind/mode-like values (uppercasing)
        normalizeValueUpper(m, path, issues, "type", "W_NORM_VALUE_CANON");
        normalizeValueUpper(m, path, issues, "op", "W_NORM_VALUE_CANON");
        normalizeValueUpper(m, path, issues, "kind", "W_NORM_VALUE_CANON");
        if (m.get("mode") instanceof String s) {
            String norm = normalizeTerrainMode(s);
            if (!norm.equals(s)) {
                m.put("mode", norm);
                issues.add(warn(path + ".mode", "W_NORM_VALUE_CANON", "mode 规范化: \"" + s + "\" -> \"" + norm + "\""));
            }
        }
        if (m.get("baseLevel") instanceof String s) {
            String norm = normalizeBaseLevel(s);
            if (!norm.equals(s)) {
                m.put("baseLevel", norm);
                issues.add(warn(path + ".baseLevel", "W_NORM_VALUE_CANON", "baseLevel 规范化: \"" + s + "\" -> \"" + norm + "\""));
            }
        }

        // Recurse into children
        for (Map.Entry<String, Object> e : new ArrayList<>(m.entrySet())) {
            String k = e.getKey();
            Object v = e.getValue();
            String p = path + "." + k;
            if (v instanceof Map<?, ?> mm) {
                @SuppressWarnings("unchecked")
                Map<String, Object> child = (Map<String, Object>) mm;
                normalizeMap(child, p, issues);
            } else if (v instanceof List<?> list) {
                normalizeList(list, p, issues);
            }
        }
    }

    private static void normalizeList(List<?> list, String path, List<AssemblyValidationIssue> issues) {
        if (list == null || list.isEmpty()) return;
        for (int i = 0; i < list.size(); i++) {
            Object v = list.get(i);
            String p = path + "[" + i + "]";
            if (v instanceof Map<?, ?> mm) {
                @SuppressWarnings("unchecked")
                Map<String, Object> child = (Map<String, Object>) mm;
                normalizeMap(child, p, issues);
            } else if (v instanceof List<?> ll) {
                normalizeList(ll, p, issues);
            }
        }
    }

    // ---------------- facade normalization ----------------

    private static void normalizeFacade(Map<String, Object> facade, String path, List<AssemblyValidationIssue> issues) {
        if (facade == null) return;

        // facade.pattern -> facade.surfacePattern (if surfacePattern absent)
        Object pat = facade.get("pattern");
        if (pat instanceof Map<?, ?> && facade.get("surfacePattern") == null) {
            facade.put("surfacePattern", pat);
            facade.remove("pattern");
            issues.add(warn(path + ".surfacePattern", "W_NORM_KEY_MOVE", "字段迁移: facade.pattern -> facade.surfacePattern"));
        }

        // facade.opening -> facade.openings
        Object opening = facade.get("opening");
        if (opening instanceof List<?> && facade.get("openings") == null) {
            facade.put("openings", opening);
            facade.remove("opening");
            issues.add(warn(path + ".openings", "W_NORM_KEY_MOVE", "字段迁移: facade.opening -> facade.openings"));
        }

        // facade.facadeGrid alias casing: keep as facadeGrid, but tolerate "FACADE_GRID"
        Object fg = facade.get("FACADE_GRID");
        if (fg instanceof Map<?, ?> && facade.get("facadeGrid") == null) {
            facade.put("facadeGrid", fg);
            facade.remove("FACADE_GRID");
            issues.add(warn(path + ".facadeGrid", "W_NORM_KEY_RENAME", "字段重命名: facade.FACADE_GRID -> facade.facadeGrid"));
        }

        // facade.surfaceBands alias casing
        Object sb = facade.get("SURFACE_BANDS");
        if (sb instanceof Map<?, ?> && facade.get("surfaceBands") == null) {
            facade.put("surfaceBands", sb);
            facade.remove("SURFACE_BANDS");
            issues.add(warn(path + ".surfaceBands", "W_NORM_KEY_RENAME", "字段重命名: facade.SURFACE_BANDS -> facade.surfaceBands"));
        }

        // facadeGrid inner aliases: spEvery/spH/spOffset -> spandrel*
        Object fgo = facade.get("facadeGrid");
        if (fgo instanceof Map<?, ?> gm) {
            @SuppressWarnings("unchecked")
            Map<String, Object> g = (Map<String, Object>) gm;
            moveIfAbsent(g, path + ".facadeGrid", issues, "spEvery", "spandrelEvery");
            moveIfAbsent(g, path + ".facadeGrid", issues, "spH", "spandrelHeight");
            moveIfAbsent(g, path + ".facadeGrid", issues, "spOffset", "spandrelOffset");
        }
    }

    // ---------------- terrainAdaptation normalization ----------------

    private static void normalizeTerrainAdaptation(Map<String, Object> ta, String path, List<AssemblyValidationIssue> issues) {
        if (ta == null) return;
        // snake_case -> camelCase (canonical)
        normalizeKeyAlias(ta, path, issues, "base_level", "baseLevel");
        normalizeKeyAlias(ta, path, issues, "fixed_y", "fixedY");
        normalizeKeyAlias(ta, path, issues, "max_step_height", "maxStepHeight");
        normalizeKeyAlias(ta, path, issues, "foundation_depth", "foundationDepth");
        normalizeKeyAlias(ta, path, issues, "anchor_max_depth", "anchorMaxDepth");
        normalizeKeyAlias(ta, path, issues, "allow_water_edit", "allowWaterEdit");
        normalizeKeyAlias(ta, path, issues, "allow_lava_edit", "allowLavaEdit");

        // value normalization
        if (ta.get("mode") instanceof String s) {
            String norm = normalizeTerrainMode(s);
            if (!norm.equals(s)) {
                ta.put("mode", norm);
                issues.add(warn(path + ".mode", "W_NORM_VALUE_CANON", "mode 规范化: \"" + s + "\" -> \"" + norm + "\""));
            }
        }
        if (ta.get("baseLevel") instanceof String s) {
            String norm = normalizeBaseLevel(s);
            if (!norm.equals(s)) {
                ta.put("baseLevel", norm);
                issues.add(warn(path + ".baseLevel", "W_NORM_VALUE_CANON", "baseLevel 规范化: \"" + s + "\" -> \"" + norm + "\""));
            }
        }
    }

    // ---------------- helpers ----------------

    private static void normalizeKeyAlias(Map<String, Object> m, String path, List<AssemblyValidationIssue> issues, String fromKey, String toKey) {
        if (m.containsKey(fromKey) && !m.containsKey(toKey)) {
            Object v = m.get(fromKey);
            m.put(toKey, v);
            m.remove(fromKey);
            issues.add(warn(path + "." + toKey, "W_NORM_KEY_RENAME", "字段重命名: " + fromKey + " -> " + toKey));
        }
    }

    private static void moveIfAbsent(Map<String, Object> m, String path, List<AssemblyValidationIssue> issues, String fromKey, String toKey) {
        if (m.containsKey(fromKey) && !m.containsKey(toKey)) {
            Object v = m.get(fromKey);
            m.put(toKey, v);
            m.remove(fromKey);
            issues.add(warn(path + "." + toKey, "W_NORM_KEY_RENAME", "字段重命名: " + fromKey + " -> " + toKey));
        }
    }

    private static void normalizeValueUpper(Map<String, Object> m, String path, List<AssemblyValidationIssue> issues, String key, String code) {
        Object v = m.get(key);
        if (!(v instanceof String s)) return;
        String t = s.trim();
        if (t.isEmpty()) return;
        String u = t.toUpperCase(Locale.ROOT);
        if (!u.equals(s)) {
            m.put(key, u);
            issues.add(warn(path + "." + key, code, key + " 规范化: \"" + s + "\" -> \"" + u + "\""));
        }
    }

    private static String normalizeEndpointString(String s) {
        String t = s == null ? "" : s.trim();
        if (t.isEmpty()) return s;
        int dot = t.indexOf('.');
        if (dot <= 0 || dot >= t.length() - 1) return s;
        String id = t.substring(0, dot).trim();
        String port = t.substring(dot + 1).trim();
        String p = normalizePortKey(port);
        if (p.equals(port)) return id + "." + port;
        return id + "." + p;
    }

    private static String normalizePortKey(String s) {
        if (s == null) return "";
        String k = s.trim().toLowerCase(Locale.ROOT);
        if (k.isEmpty()) return k;
        k = k.replace('-', '_').replace(' ', '_');
        if (k.equals("n")) k = "north";
        if (k.equals("s")) k = "south";
        if (k.equals("e")) k = "east";
        if (k.equals("w")) k = "west";
        return k;
    }

    private static String normalizeFacesString(String s) {
        String t = s == null ? "" : s.trim();
        if (t.isEmpty()) return s;
        String u = t.toUpperCase(Locale.ROOT);
        if (u.equals("*")) return "ALL";
        if (u.equals("ALL")) return "ALL";
        // allow comma-separated
        String[] parts = u.split(",");
        if (parts.length == 1) return normalizeFaceToken(u);
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            String x = normalizeFaceToken(p.trim());
            if (x.isEmpty()) continue;
            if (sb.length() > 0) sb.append(',');
            sb.append(x);
        }
        return sb.length() == 0 ? "ALL" : sb.toString();
    }

    private static String normalizeFaceToken(String t) {
        if (t == null) return "";
        String u = t.trim().toUpperCase(Locale.ROOT);
        if (u.isEmpty()) return "";
        if (u.equals("*") || u.equals("ALL")) return "ALL";
        if (u.equals("N")) return "NORTH";
        if (u.equals("S")) return "SOUTH";
        if (u.equals("E")) return "EAST";
        if (u.equals("W")) return "WEST";
        return u;
    }

    private static String normalizeTerrainMode(String s) {
        String u = (s == null ? "" : s.trim()).toUpperCase(Locale.ROOT);
        if (u.isEmpty()) return s;
        if (u.equals("CUT_AND_FILL")) return "FLATTEN";
        if (u.contains("FLAT") || u.contains("CUT")) return "FLATTEN";
        if (u.contains("DRAPE") || u.contains("FOLLOW_GROUND")) return "DRAPE";
        if (u.contains("ANCHOR") || u.contains("PILE") || u.contains("FOUNDATION")) return "ANCHOR";
        if (u.contains("EMBED") || u.contains("BURY")) return "EMBED";
        if (u.contains("FLOAT") || u.contains("SKY")) return "FLOAT";
        return u;
    }

    private static String normalizeBaseLevel(String s) {
        String t = (s == null ? "" : s.trim());
        if (t.isEmpty()) return s;
        String u = t.toUpperCase(Locale.ROOT);
        if (u.contains("平均") || u.contains("AVG") || u.contains("AVER")) return "AVERAGE";
        if (u.contains("中位") || u.contains("MED")) return "MEDIAN";
        if (u.contains("众数") || u.contains("MODE")) return "MODE";
        if (u.contains("最低") || u.contains("LOW")) return "LOWEST";
        if (u.contains("最高") || u.contains("HIGH")) return "HIGHEST";
        if (u.contains("固定") || u.contains("FIX")) return "FIXED";
        return u;
    }

    private static AssemblyValidationIssue warn(String path, String code, String msg) {
        return new AssemblyValidationIssue(path, AssemblyValidationIssue.Severity.WARNING, code, msg);
    }

    private static Object deepCopy(Object v) {
        if (v == null) return null;
        if (v instanceof Map<?, ?> mm) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : mm.entrySet()) {
                if (e.getKey() == null) continue;
                out.put(String.valueOf(e.getKey()), (Object) deepCopy(e.getValue()));
            }
            return out;
        }
        if (v instanceof List<?> ll) {
            List<Object> out = new ArrayList<>(ll.size());
            for (Object it : ll) out.add(deepCopy(it));
            return out;
        }
        // primitives: String/Number/Boolean/etc
        return v;
    }
}


