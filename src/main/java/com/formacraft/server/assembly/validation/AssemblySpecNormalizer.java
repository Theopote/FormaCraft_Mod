package com.formacraft.server.assembly.validation;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * P0 "auto-fix / normalizer" for extra.assembly.
 * <p>
 * Design goals:
 * - Deep-copy input (never mutate caller maps).
 * - Canonicalize common aliases and casing.
 * - Migrate common fields (e.g., facade.pattern -> facade.surfacePattern) for LLM stability.
 * - Emit WARNING issues describing each change (path + code).
 * <p>
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

        // High-confidence spell-fix for common LLM typos (only within known schemas, and only when unambiguous).
        // Root-level: keys are fairly stable.
        if ("$".equals(path)) {
            maybeSpellFixKeys(m, path, issues, Set.of(
                    "paletteId", "entranceFacing",
                    "ops",
                    "graph", "components", "connections"
            ));
        }

        // Root-level: normalize known keys / casing
        normalizeKeyAlias(m, path, issues, "base_level", "baseLevel");
        // Common snake_case aliases for anchorage detailing (safe to apply globally)
        normalizeKeyAlias(m, path, issues, "top_bevel", "topBevel");
        normalizeKeyAlias(m, path, issues, "guard_wall_height", "guardWallHeight");
        normalizeKeyAlias(m, path, issues, "guard_wall_inset", "guardWallInset");
        normalizeKeyAlias(m, path, issues, "guard_wall_crenels", "guardWallCrenels");
        normalizeKeyAlias(m, path, issues, "cable_holes", "cableHoles");
        normalizeKeyAlias(m, path, issues, "bridge_tower", "bridgeTower");

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

        // If this map looks like a connection/op/component, spell-fix some hot keys.
        // This is intentionally conservative: only do this when the map already contains typical anchor keys.
        if (m.containsKey("op")) {
            maybeSpellFixKeys(m, path, issues, KNOWN_OP_KEYS);
        }
        if (m.containsKey("type") && (m.containsKey("from") || m.containsKey("to"))) {
            maybeSpellFixKeys(m, path, issues, KNOWN_CONNECTION_KEYS);
        }
        if (m.containsKey("id") || m.containsKey("ports") || m.containsKey("facade")) {
            maybeSpellFixKeys(m, path, issues, KNOWN_COMPONENT_KEYS);
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
        normalizeValueUpper(m, path, issues, "type");
        normalizeValueUpper(m, path, issues, "op");
        normalizeValueUpper(m, path, issues, "kind");
        // Move cableHoles -> holes (canonical) if holes absent
        if (m.get("cableHoles") instanceof List<?> l && m.get("holes") == null) {
            m.put("holes", l);
            m.remove("cableHoles");
            issues.add(warn(path + ".holes", "W_NORM_KEY_MOVE", "字段迁移: cableHoles -> holes"));
        }
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

        maybeSpellFixKeys(facade, path, issues, Set.of(
                "surfacePattern", "pattern",
                "openings", "opening",
                "facadeGrid", "FACADE_GRID", "curtainWall",
                "surfaceBands", "SURFACE_BANDS", "bands"
        ));

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
            maybeSpellFixKeys(g, path + ".facadeGrid", issues, Set.of(
                    "face", "faces",
                    "bayW", "bayH", "moduleW", "moduleH", "gridW", "gridH",
                    "mullionThickness", "mullionT",
                    "transomThickness", "transomT",
                    "borderThickness", "borderT",
                    "marginU", "marginX", "marginY",
                    "inset", "depth",
                    "frame", "fill", "material",
                    "spandrelEvery", "spandrelHeight", "spandrelOffset", "spandrelFill",
                    "spEvery", "spH", "spOffset"
            ));
            moveIfAbsent(g, path + ".facadeGrid", issues, "spEvery", "spandrelEvery");
            moveIfAbsent(g, path + ".facadeGrid", issues, "spH", "spandrelHeight");
            moveIfAbsent(g, path + ".facadeGrid", issues, "spOffset", "spandrelOffset");
        }

        Object sbo = facade.get("surfaceBands");
        if (sbo instanceof Map<?, ?> sm) {
            @SuppressWarnings("unchecked")
            Map<String, Object> bandsMap = (Map<String, Object>) sm;
            maybeSpellFixKeys(bandsMap, path + ".surfaceBands", issues, Set.of(
                    "face", "faces",
                    "horizontalBands", "hBands", "bandsH",
                    "verticalBands", "vBands", "bandsV"
            ));
        }
    }

    // ---------------- terrainAdaptation normalization ----------------

    private static void normalizeTerrainAdaptation(Map<String, Object> ta, String path, List<AssemblyValidationIssue> issues) {
        if (ta == null) return;
        maybeSpellFixKeys(ta, path, issues, Set.of(
                "mode",
                "base_level", "baseLevel", "fixedY", "fixed_y",
                "max_step_height", "maxStepHeight", "max_step", "maxStep",
                "foundation_depth", "foundationDepth",
                "anchor_max_depth", "anchorMaxDepth",
                "allow_water_edit", "allowWaterEdit",
                "allow_lava_edit", "allowLavaEdit",
                "clearHeight",
                "embedDepth",
                "floatHeight"
        ));
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

    private static void normalizeValueUpper(Map<String, Object> m, String path, List<AssemblyValidationIssue> issues, String key) {
        Object v = m.get(key);
        if (!(v instanceof String s)) return;
        String t = s.trim();
        if (t.isEmpty()) return;
        String u = t.toUpperCase(Locale.ROOT);
        if (!u.equals(s)) {
            m.put(key, u);
            issues.add(warn(path + "." + key, "W_NORM_VALUE_CANON", key + " 规范化: \"" + s + "\" -> \"" + u + "\""));
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
            if (!sb.isEmpty()) sb.append(',');
            sb.append(x);
        }
        return sb.isEmpty() ? "ALL" : sb.toString();
    }

    private static String normalizeFaceToken(String t) {
        if (t == null) return "";
        String u = t.trim().toUpperCase(Locale.ROOT);
        if (u.isEmpty()) return "";
        return switch (u) {
            case "*", "ALL" -> "ALL";
            case "N" -> "NORTH";
            case "S" -> "SOUTH";
            case "E" -> "EAST";
            case "W" -> "WEST";
            default -> u;
        };
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

    // ---------------- spell-fix ----------------

    private static final Set<String> KNOWN_CONNECTION_KEYS = mkSet(
            "type", "from", "to", "via",
            "avoid", "avoidComponents", "avoidAllComponents", "avoidMargin", "avoidExcludeComponents", "avoidIncludeComponents",
            "routing", "avoidAStar",
            "routingPad", "routingMaxArea", "routingMaxNodes",
            "routingPreferStraight", "routingPreferAxis", "routingPreferAxisWeight", "routingPreferDoorAxis",
            "routingLeadOut", "routingLeadIn", "routingAutoLead", "routingLeadSoft", "routingLeadHard",
            "routingLeadWeight", "routingLeadOutWeight", "routingLeadInWeight", "routingLeadRing", "routingLeadInStepsMaxNodes",
            "routingStyle", "routingStyleStrength", "routingQoS",
            "width", "wallHeight", "wallThickness", "foundationDepth", "maxStep",
            "material",
            "terrainAdaptation"
    );

    private static final Set<String> KNOWN_COMPONENT_KEYS = mkSet(
            "id", "type", "op",
            "at", "x", "y", "z",
            "w", "d", "h", "width", "depth", "height",
            "r", "radius",
            "r0", "r1", "radius0", "radius1",
            "points",
            // anchoring / anchorage
            "x0", "x1", "z0", "z1",
            "yBase",
            "maxDepth", "anchorDepth",
            "stopOnSolid",
            "allowWaterEdit", "allowLavaEdit",
            "solid",
            "carve",
            // anchorage detailing
            "topBevel", "bevel",
            "guardWallHeight", "parapetHeight",
            "guardWallInset",
            "guardWallCrenels", "crenels",
            "guardWallMaterial", "guardWall",
            "holes", "cableHoles",
            "profile", "profileFrame", "profileSnap", "frame", "snap",
            "profileW", "profileH",
            "profilePoints", "profileRings", "rings",
            "profileScale0", "profileScale1", "scale0", "scale1",
            "profileW0", "profileW1", "profileH0", "profileH1",
            "twistTurns", "twistPhase",
            "capEnds", "capThickness", "carveInterior",
            "connectSamples", "connectMaxStep",
            "hollow", "thickness",
            "material", "wall", "window", "floor", "roof", "slab",
            "ports",
            "facade", "facade_logic", "facadeLogic"
    );

    private static final Set<String> KNOWN_OP_KEYS = mkSet(
            "op",
            "at", "x", "y", "z", "dx", "dy", "dz",
            "x0", "x1", "y0", "y1", "z0", "z1",
            "from", "to",
            "material", "wall", "roof", "slab", "floor", "window", "fill", "frame", "accent", "air",
            "type", "kind", "face", "faces",
            "w", "d", "h", "width", "depth", "height",
            "points", "profile", "profileFrame", "profileSnap", "frame", "snap",
            "profileW", "profileH", "profilePoints", "profileRings", "rings",
            "profileScale0", "profileScale1", "scale0", "scale1",
            "profileW0", "profileW1", "profileH0", "profileH1",
            "twistTurns", "twistPhase",
            "capEnds", "capThickness", "carveInterior",
            "connectSamples", "connectMaxStep",
            "r", "radius", "r0", "r1", "radius0", "radius1",
            "hollow", "thickness", "samplesPerBlock",
            // anchoring / anchorage
            "yBase",
            "maxDepth", "anchorDepth",
            "stopOnSolid",
            "allowWaterEdit", "allowLavaEdit",
            "solid",
            "carve",
            // anchorage detailing
            "topBevel", "bevel",
            "guardWallHeight", "parapetHeight",
            "guardWallInset",
            "guardWallCrenels", "crenels",
            "guardWallMaterial", "guardWall",
            "holes", "cableHoles",
            // cable
            "sag", "droop",
            "hangersEvery", "hangerEvery",
            "hangersToY", "hangerToY",
            "hangersMaterial",
            "cableCount", "count",
            "cableSpacing", "spacing",
            "cableAxis",
            "rows", "cols", "winW", "winH", "sillY", "marginX", "marginY", "gapX", "gapY", "frameThickness", "mullionStep",
            "doorW", "doorH",
            "archType", "arch", "archThickness", "archT",
            "keystone", "keystoneOn",
            "tracery", "traceryType", "traceryMaterial", "traceryThickness", "traceryT", "traceryY", "traceryInset",
            "foilRadius", "foilCenterY", "foilCount", "foilStepY", "foilGapY",
            "traceryFoilRadius", "traceryFoilCenterY", "traceryFoilCount", "traceryFoilStepY",
            "centerY", "petals", "spokes", "ring", "phase", "phi", "spokeWidth", "spokeW", "spokeThreshold", "spokeThresh",
            "innerFill", "spokeMaterial",
            "bayW", "bayH", "moduleW", "moduleH", "gridW", "gridH",
            "mullionThickness", "mullionT", "transomThickness", "transomT", "borderThickness", "borderT",
            "marginU", "inset",
            "spandrelEvery", "spEvery", "spandrelHeight", "spH", "spandrelOffset", "spOffset", "spandrelFill",
            "horizontalBands", "hBands", "bandsH",
            "verticalBands", "vBands", "bandsV",
            "terrainAdaptation"
    );

    private static Set<String> mkSet(String... keys) {
        java.util.LinkedHashSet<String> s = new java.util.LinkedHashSet<>();
        if (keys != null) {
            for (String k : keys) {
                if (k == null || k.isBlank()) continue;
                s.add(k);
            }
        }
        return java.util.Collections.unmodifiableSet(s);
    }

    private static void maybeSpellFixKeys(Map<String, Object> m, String path, List<AssemblyValidationIssue> issues, Set<String> allowed) {
        if (m == null || m.isEmpty() || allowed == null || allowed.isEmpty()) return;

        // Work on a snapshot of keys to avoid concurrent modification issues.
        List<String> keys = new ArrayList<>(m.keySet());

        for (String key : keys) {
            if (key == null) continue;
            if (allowed.contains(key)) continue;
            if (!m.containsKey(key)) continue; // may have been renamed already

            // Skip very long or weird keys (likely user-defined IDs or block IDs)
            if (key.length() > 32) continue;
            if (key.contains(":")) continue; // minecraft:block_id style

            String best = bestKeyMatch(key, allowed);
            if (best == null) continue;
            if (m.containsKey(best)) continue; // don't overwrite

            Object v = m.get(key);
            m.put(best, v);
            m.remove(key);
            issues.add(warn(path + "." + best, "W_NORM_SPELLFIX", "spellfix: " + key + " -> " + best));
        }
    }

    private static String bestKeyMatch(String key, Set<String> allowed) {
        String nk = normKey(key);
        if (nk.isEmpty()) return null;

        String best = null;
        int bestD = Integer.MAX_VALUE;
        int bestCount = 0;
        for (String cand : allowed) {
            String nc = normKey(cand);
            if (nc.isEmpty()) continue;
            int d = editDistance(nk, nc); // cap at 2 for performance
            if (d < 0) continue;
            if (d < bestD) {
                bestD = d;
                best = cand;
                bestCount = 1;
            } else if (d == bestD) {
                bestCount++;
            }
        }

        // Only fix when unambiguous and very close.
        if (best == null) return null;
        if (bestCount != 1) return null;
        // Keep this extremely conservative to avoid bad "repairs".
        if (bestD > 2) return null;
        // Distance-2 repairs are only allowed for long keys (reduces false positives like rows->ops).
        if (bestD == 2 && nk.length() < 7) return null;
        // Additional guard: short keys must be distance-1 only.
        return best;
    }

    private static String normKey(String s) {
        if (s == null) return "";
        String t = s.trim().toLowerCase(Locale.ROOT);
        if (t.isEmpty()) return "";
        return t.replace("_", "").replace("-", "").replace(" ", "");
    }

    /**
     * Levenshtein edit distance with an early-exit cap.
     * Returns -1 if distance exceeds cap.
     */
    private static int editDistance(String a, String b) {
        if (a.equals(b)) return 0;
        int la = a.length(), lb = b.length();
        if (Math.abs(la - lb) > 2) return -1;
        if (la == 0) return lb <= 2 ? lb : -1;
        if (lb == 0) return la <= 2 ? la : -1;

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
            if (minRow > 2) return -1;
            int[] tmp = prev; prev = cur; cur = tmp;
        }
        int d = prev[lb];
        return d <= 2 ? d : -1;
    }

    private static Object deepCopy(Object v) {
        switch (v) {
            case null -> {
                return null;
            }
            case Map<?, ?> mm -> {
                Map<String, Object> out = new LinkedHashMap<>();
                for (Map.Entry<?, ?> e : mm.entrySet()) {
                    if (e.getKey() == null) continue;
                    out.put(String.valueOf(e.getKey()), deepCopy(e.getValue()));
                }
                return out;
            }
            case List<?> ll -> {
                List<Object> out = new ArrayList<>(ll.size());
                for (Object it : ll) out.add(deepCopy(it));
                return out;
            }
            default -> {
            }
        }
        // primitives: String/Number/Boolean/etc
        return v;
    }
}


