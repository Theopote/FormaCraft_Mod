package com.formacraft.server.assembly.validation;

import com.formacraft.common.logging.FcaLog;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Shared validation helpers for assembly schema checks. */
final class AssemblyValidationSupport {
    static final FcaLog LOG = FcaLog.of("AssemblyValidationSupport");

    private AssemblyValidationSupport() {}

    static Double doubleOrNull(Object v) {
        try {
            if (v instanceof Number n) return n.doubleValue();
            if (v != null) return Double.parseDouble(String.valueOf(v).trim());
        } catch (Exception e) {
            LOG.debug("doubleOrNull failed value={}", v);
        }
        return null;
    }

    static void validateRange01(List<AssemblyValidationIssue> out, String path, Object v0, Object v1, Object v2) {
        Object v = (v0 != null) ? v0 : (v1 != null ? v1 : v2);
        if (v == null) return;
        if (!(v instanceof List<?> list) || list.size() < 2) {
            out.add(err(path, "E_RANGE_TYPE", "range 必须是数组 [t0,t1]"));
            return;
        }
        Double a = doubleOrNull(list.get(0));
        Double b = doubleOrNull(list.get(1));
        if (a == null || b == null) {
            out.add(err(path, "E_RANGE_NUM", "range 必须是数字 [t0,t1]"));
            return;
        }
        if (a < 0.0 || a > 1.0 || b < 0.0 || b > 1.0) {
            out.add(warn(path, "W_RANGE_01", "range 建议在 0..1（当前=" + a + "," + b + "）"));
        }
        if (Math.abs(a - b) < 1e-9) {
            out.add(warn(path, "W_RANGE_ZERO", "range 长度为 0，可能无法形成有效拼缝"));
        }
    }

    static void validateEndpointRef(List<AssemblyValidationIssue> out, String path, Object v, Set<String> ids) {
        switch (v) {
            case null -> {
                out.add(err(path, "E_ENDPOINT_MISSING", "缺少端点"));
                return;
            }
            case String s -> {
                String t = s.trim();
                if (t.isEmpty()) {
                    out.add(err(path, "E_ENDPOINT_EMPTY", "端点字符串不能为空"));
                    return;
                }
                int dot = t.indexOf('.');
                if (dot > 0) {
                    String id = t.substring(0, dot).trim();
                    if (!id.isEmpty() && !ids.isEmpty() && !ids.contains(id)) {
                        out.add(warn(path, "W_ENDPOINT_UNKNOWN_COMPONENT", "引用了不存在的组件 id: " + id + "（示例/训练建议修正）"));
                    }
                }
                return;
            }
            case Map<?, ?> m -> {
                Object cid = m.get("component");
                if (cid == null) cid = m.get("id");
                if (cid == null) {
                    // could be raw {x,y,z}; accept
                    return;
                }
                String id = str(cid, "").trim();
                if (!id.isEmpty() && !ids.isEmpty() && !ids.contains(id)) {
                    out.add(warn(path, "W_ENDPOINT_UNKNOWN_COMPONENT", "引用了不存在的组件 id: " + id + "（示例/训练建议修正）"));
                }
                return;
            }
            default -> {
            }
        }
        out.add(err(path, "E_ENDPOINT_BAD_TYPE", "端点必须是字符串 \"A.port\" 或对象 {component/id,port,...} 或坐标 {x,y,z}"));
    }

    // ---------------- helpers ----------------

    static void validatePoints(List<AssemblyValidationIssue> out, String path, List<?> pts) {
        for (int i = 0; i < pts.size(); i++) {
            Object it = pts.get(i);
            String p = path + "[" + i + "]";
            if (!(it instanceof Map<?, ?> m)) {
                out.add(err(p, "E_POINT_NOT_OBJECT", "point 必须是 {x,y,z} 对象"));
                continue;
            }
            if (m.get("x") == null || m.get("y") == null || m.get("z") == null) {
                out.add(err(p, "E_POINT_MISSING_XYZ", "point 必须包含 x/y/z"));
            }
        }
    }

    static void validatePointsXZ(List<AssemblyValidationIssue> out, String path, List<?> pts) {
        for (int i = 0; i < pts.size(); i++) {
            Object it = pts.get(i);
            String p = path + "[" + i + "]";
            if (!(it instanceof Map<?, ?> m)) {
                out.add(err(p, "E_POINT_NOT_OBJECT", "point 必须是 {x,z} 对象"));
                continue;
            }
            if (m.get("x") == null || m.get("z") == null) {
                out.add(err(p, "E_POINT_MISSING_XZ", "point 必须包含 x/z"));
            }
        }
    }

    static void validatePointRefXYZ(List<AssemblyValidationIssue> out, String path, Object v, String missingCode) {
        if (v == null) {
            out.add(err(path, missingCode, "缺少坐标点 {x,y,z}"));
            return;
        }
        if (!(v instanceof Map<?, ?> m)) {
            out.add(err(path, "E_POINT_REF_TYPE", "必须是 {x,y,z} 对象"));
            return;
        }
        if (m.get("x") == null || m.get("y") == null || m.get("z") == null) {
            out.add(err(path, "E_POINT_MISSING_XYZ", "必须包含 x/y/z"));
        }
    }

    static void requireIntIfPresent(List<AssemblyValidationIssue> out, String base, Map<?, ?> m, String key) {
        if (m.get(key) == null) return;
        if (intOrNull(m.get(key)) == null) out.add(err(base + "." + key, "E_INT_TYPE", key + " 必须是整数"));
    }

    static void validateTerrainAdaptation(List<AssemblyValidationIssue> out, String path, Object v, String context) {
        if (!(v instanceof Map<?, ?> ta)) {
            out.add(err(path, "E_TA_TYPE", "terrainAdaptation 必须是对象（map）"));
            return;
        }

        warnUnknownKeys(out, path, ta, java.util.Set.of(
                "mode",
                "base_level", "baseLevel", "fixedY",
                "max_step_height", "maxStepHeight", "max_step", "maxStep",
                "foundation_depth", "foundationDepth",
                "anchorMaxDepth",
                "clearHeight",
                "allowWaterEdit", "allowLavaEdit",
                "foundationMaterial", "anchorMaterial",
                "embedDepth",
                "floatHeight"
        ));

        Object mv = ta.get("mode");
        String mode = (mv == null) ? "" : String.valueOf(mv).trim().toUpperCase(Locale.ROOT);
        if (mode.isEmpty()) {
            out.add(err(path + ".mode", "E_TA_MODE_MISSING", "terrainAdaptation.mode 缺失"));
            return;
        }
        // Normalize common synonyms
        if (mode.equals("CUT_AND_FILL")) mode = "FLATTEN";
        if (mode.equals("HANG") || mode.equals("HANGING")) mode = "ANCHOR";

        boolean ok = mode.equals("FLATTEN") || mode.equals("DRAPE") || mode.equals("ANCHOR") || mode.equals("EMBED") || mode.equals("FLOAT");
        if (!ok) {
            out.add(err(path + ".mode", "E_TA_MODE_VALUE", "terrainAdaptation.mode 取值非法: " + mode + "（允许 FLATTEN/DRAPE/ANCHOR/EMBED/FLOAT）"));
            return;
        }

        // base level strategy (optional): AVERAGE / MEDIAN / MODE / LOWEST / HIGHEST / FIXED
        Object bl = ta.get("baseLevel");
        if (bl == null) bl = ta.get("base_level");
        if (bl != null) {
            if (bl instanceof Number) {
                // LLM sometimes puts a number here; suggest fixedY instead.
                out.add(warn(path + ".baseLevel", "W_TA_BASELEVEL_NUM", "baseLevel/base_level 不应为数字；请使用 baseLevel=FIXED + fixedY"));
            } else {
                String s = String.valueOf(bl).trim();
                if (s.isEmpty()) {
                    out.add(err(path + ".baseLevel", "E_TA_BASELEVEL_EMPTY", "baseLevel/base_level 不能为空"));
                } else {
                    String u = getString(s);
                    boolean bok = u.equals("AVERAGE") || u.equals("MEDIAN") || u.equals("MODE") || u.equals("LOWEST") || u.equals("HIGHEST") || u.equals("FIXED");
                    if (!bok) {
                        out.add(err(path + ".baseLevel", "E_TA_BASELEVEL_VALUE",
                                "baseLevel/base_level 取值非法: " + s + "（允许 AVERAGE/MEDIAN/MODE/LOWEST/HIGHEST/FIXED）"));
                    }
                    if (u.equals("FIXED")) {
                        Object fy = ta.get("fixedY");
                        if (fy == null || intOrNull(fy) == null) {
                            out.add(err(path + ".fixedY", "E_TA_FIXEDY_REQUIRED", "baseLevel=FIXED 时必须提供 fixedY（整数）"));
                        }
                    }
                }
            }
        }

        // Shared numeric sanity (only validate if provided)
        // DRAPE
        if (mode.equals("DRAPE")) {
            Object ms = ta.get("max_step_height");
            if (ms == null) ms = ta.get("maxStepHeight");
            if (ms == null) ms = ta.get("max_step");
            if (ms == null) ms = ta.get("maxStep");
            if (ms != null) {
                Integer x = intOrNull(ms);
                if (x == null) out.add(err(path + ".max_step_height", "E_INT_TYPE", "max_step_height 必须是整数"));
                else if (x < 0 || x > 8) out.add(err(path + ".max_step_height", "E_INT_RANGE", "max_step_height 建议范围 0..8"));
            }
            Object fd = ta.get("foundation_depth");
            if (fd == null) fd = ta.get("foundationDepth");
            if (fd != null) {
                Integer x = intOrNull(fd);
                if (x == null) out.add(err(path + ".foundation_depth", "E_INT_TYPE", "foundation_depth 必须是整数"));
                else if (x < 0 || x > 32) out.add(err(path + ".foundation_depth", "E_INT_RANGE", "foundation_depth 建议范围 0..32"));
            }
            Object ch = ta.get("clearHeight");
            if (ch != null) {
                Integer x = intOrNull(ch);
                if (x == null) out.add(err(path + ".clearHeight", "E_INT_TYPE", "clearHeight 必须是整数"));
                else if (x < 0 || x > 16) out.add(err(path + ".clearHeight", "E_INT_RANGE", "clearHeight 建议范围 0..16"));
            }
        }

        // ANCHOR
        if (mode.equals("ANCHOR")) {
            Object ad = ta.get("anchorMaxDepth");
            if (ad != null) {
                Integer x = intOrNull(ad);
                if (x == null) out.add(err(path + ".anchorMaxDepth", "E_INT_TYPE", "anchorMaxDepth 必须是整数"));
                else if (x < 1 || x > 256) out.add(err(path + ".anchorMaxDepth", "E_INT_RANGE", "anchorMaxDepth 建议范围 1..256"));
            }
            Object fy = ta.get("fixedY");
            if (fy != null) {
                Integer x = intOrNull(fy);
                if (x == null) out.add(err(path + ".fixedY", "E_INT_TYPE", "fixedY 必须是整数"));
            }
        }

        // EMBED / FLOAT (very light checks)
        if (mode.equals("EMBED")) {
            Object ed = ta.get("embedDepth");
            if (ed != null) {
                Integer x = intOrNull(ed);
                if (x == null) out.add(err(path + ".embedDepth", "E_INT_TYPE", "embedDepth 必须是整数"));
                else if (x < 0 || x > 128) out.add(err(path + ".embedDepth", "E_INT_RANGE", "embedDepth 建议范围 0..128"));
            }
        }
        if (mode.equals("FLOAT")) {
            Object fh = ta.get("floatHeight");
            if (fh != null) {
                Integer x = intOrNull(fh);
                if (x == null) out.add(err(path + ".floatHeight", "E_INT_TYPE", "floatHeight 必须是整数"));
            }
        }

        // Extra context hint (warning only): usage mismatch
        if (context != null) {
            String c = context.toUpperCase(Locale.ROOT);
            if (c.contains("CONNECTION:BRIDGE") && mode.equals("DRAPE")) {
                out.add(warn(path + ".mode", "W_TA_MODE_MISMATCH", "BRIDGE 连接通常建议使用 ANCHOR（当前为 DRAPE）"));
            }
            if ((c.contains("CONNECTION:PATH") || c.contains("CONNECTION:WALL")) && mode.equals("ANCHOR")) {
                out.add(warn(path + ".mode", "W_TA_MODE_MISMATCH", "PATH/WALL 连接通常建议使用 DRAPE（当前为 ANCHOR）"));
            }
        }
    }

    static @NotNull String getString(String s) {
        String u = s.toUpperCase(Locale.ROOT);
        // Chinese & common aliases
        if (u.contains("平均") || u.contains("AVG") || u.contains("AVER")) u = "AVERAGE";
        if (u.contains("中位") || u.contains("MED")) u = "MEDIAN";
        if (u.contains("众数") || u.contains("MODE")) u = "MODE";
        if (u.contains("最低") || u.contains("LOW")) u = "LOWEST";
        if (u.contains("最高") || u.contains("HIGH")) u = "HIGHEST";
        if (u.contains("固定") || u.contains("FIX")) u = "FIXED";
        return u;
    }

    static void requireFace(List<AssemblyValidationIssue> out, String base, Map<?, ?> m) {
        if (m.get("face") == null && m.get("faces") == null) {
            out.add(err(base + ".face", "E_FACE_MISSING", "缺少 face/faces"));
        } else {
            requireFacesString(out, base, m);
        }
    }

    static void requireFacesString(List<AssemblyValidationIssue> out, String base, Map<?, ?> m) {
        Object f = m.get("face");
        if (f == null) f = m.get("faces");
        if (f == null) return;
        if (!(f instanceof String)) {
            out.add(err(base + ".face", "E_FACE_TYPE", "face/faces 必须是字符串（如 ALL 或 NORTH,EAST）"));
            return;
        }
        // validate enum-ish tokens
        String raw = String.valueOf(f).trim();
        if (raw.isEmpty()) {
            out.add(err(base + ".face", "E_FACE_EMPTY", "face/faces 不能为空"));
            return;
        }
        for (String tok : raw.split(",")) {
            String t = tok.trim().toUpperCase(Locale.ROOT);
            if (t.isEmpty()) continue;
            if (t.equals("ALL") || t.equals("*")) continue;
            if (!(t.equals("NORTH") || t.equals("SOUTH") || t.equals("EAST") || t.equals("WEST"))) {
                out.add(err(base + ".face", "E_FACE_VALUE", "face/faces 取值非法: " + tok + "（允许 ALL 或 NORTH/SOUTH/EAST/WEST 或逗号分隔）"));
                break;
            }
        }
    }

    static void requireIntMin(List<AssemblyValidationIssue> out, String base, Map<?, ?> m, String key, int min) {
        Object v = m.get(key);
        if (v == null) return; // allow defaulting
        Integer x = intOrNull(v);
        if (x == null) {
            out.add(err(base + "." + key, "E_INT_TYPE", "必须是整数"));
            return;
        }
        if (x < min) out.add(err(base + "." + key, "E_INT_RANGE", "必须 >= " + min));
    }

    static void warnUnknownKeys(List<AssemblyValidationIssue> out, String base, Map<?, ?> m, Set<String> allowed) {
        if (m == null || allowed == null || allowed.isEmpty()) return;
        for (Object k : m.keySet()) {
            if (k == null) continue;
            String key = String.valueOf(k);
            if (allowed.contains(key)) continue;
            // tolerate some common aliases by ignoring case/underscore differences
            String norm = key.replace("-", "_");
            if (allowed.contains(norm)) continue;
            out.add(warn(base + "." + key, "W_UNKNOWN_KEY", "未知字段（可能是拼写错误/幻觉字段）: " + key));

            // Suggest a likely intended key (no auto-fix here; suggestion only).
            String sug = suggestKey(key, allowed);
            if (sug != null && !sug.equals(key)) {
                out.add(warn(base + "." + key, "W_SUGGEST_KEY", "你是不是想写: " + sug));
            }
        }
    }

    static String suggestKey(String key, Set<String> allowed) {
        if (key == null || allowed == null || allowed.isEmpty()) return null;
        String k = key.trim();
        if (k.isEmpty()) return null;
        // Avoid suggesting for block ids or very long keys.
        if (k.length() > 32) return null;
        if (k.contains(":")) return null;

        String nk = normKey(k);
        if (nk.isEmpty()) return null;

        String best = null;
        int bestD = Integer.MAX_VALUE;
        int bestCount = 0;
        for (String cand : allowed) {
            if (cand == null) continue;
            int d = editDistance(nk, normKey(cand), 2);
            if (d < 0) continue;
            if (d < bestD) {
                bestD = d;
                best = cand;
                bestCount = 1;
            } else if (d == bestD) {
                bestCount++;
            }
        }
        if (best == null) return null;
        if (bestCount != 1) return null;
        if (bestD > 2) return null;
        // Keep conservative: distance-2 only for longer keys.
        if (bestD == 2 && nk.length() < 7) return null;
        return best;
    }

    static String normKey(String s) {
        if (s == null) return "";
        String t = s.trim().toLowerCase(Locale.ROOT);
        if (t.isEmpty()) return "";
        return t.replace("_", "").replace("-", "").replace(" ", "");
    }

    /**
     * Levenshtein edit distance with an early-exit cap.
     * Returns -1 if distance exceeds cap.
     */
    static int editDistance(String a, String b, int cap) {
        if (a == null || b == null) return -1;
        if (a.equals(b)) return 0;
        int la = a.length(), lb = b.length();
        if (Math.abs(la - lb) > cap) return -1;
        if (la == 0) return lb <= cap ? lb : -1;
        if (lb == 0) return la <= cap ? la : -1;

        int[] prev = new int[lb + 1];
        int[] cur = new int[lb + 1];
        for (int j = 0; j <= lb; j++) prev[j] = j;

        for (int i = 1; i <= la; i++) {
            cur[0] = i;
            int minRow = cur[0];
            char ca = a.charAt(i - 1);
            for (int j = 1; j <= lb; j++) {
                int cost = (ca == b.charAt(j - 1)) ? 0 : 1;
                int v = Math.min(Math.min(prev[j] + 1, cur[j - 1] + 1), prev[j - 1] + cost);
                cur[j] = v;
                if (v < minRow) minRow = v;
            }
            if (minRow > cap) return -1;
            int[] tmp = prev; prev = cur; cur = tmp;
        }
        int d = prev[lb];
        return d <= cap ? d : -1;
    }

    static Integer intOrNull(Object v) {
        try {
            if (v instanceof Number n) return n.intValue();
            if (v instanceof String s) return Integer.parseInt(s.trim());
        } catch (Exception e) {
            LOG.debug("intOrNull failed value={}", v);
        }
        return null;
    }

    static String str(Object v, String def) {
        if (v == null) return def;
        String s = String.valueOf(v).trim();
        return s.isEmpty() ? def : s;
    }

    static AssemblyValidationIssue err(String path, String code, String msg) {
        return new AssemblyValidationIssue(path, AssemblyValidationIssue.Severity.ERROR, code, msg);
    }

    static AssemblyValidationIssue warn(String path, String code, String msg) {
        return new AssemblyValidationIssue(path, AssemblyValidationIssue.Severity.WARNING, code, msg);
    }
}


}
