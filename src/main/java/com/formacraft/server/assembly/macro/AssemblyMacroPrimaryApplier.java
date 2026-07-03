package com.formacraft.server.assembly.macro;

import com.formacraft.server.assembly.validation.AssemblyValidationIssue;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

final class AssemblyMacroPrimaryApplier {
    private AssemblyMacroPrimaryApplier() {}

    @SuppressWarnings("unchecked")
    static Map<String, Object> applyVerticalProfile(Map<String, Object> root,
                                                    Map<String, Object> graph,
                                                    Object compsObj,
                                                    Map<String, Object> primary,
                                                    Map<String, Object> macro,
                                                    List<AssemblyValidationIssue> issues) {
        Object vpObj = macro.get("verticalProfile");
        if (vpObj == null) vpObj = macro.get("vertical_profile");
        if (vpObj == null) return primary;
        if (!(vpObj instanceof List<?> vpList)) return primary;
        if (vpList.isEmpty()) return primary;

        String pType = AssemblyMacroSupport.str(primary.get("type"), AssemblyMacroSupport.str(primary.get("op"), "")).trim().toUpperCase(Locale.ROOT);
        if (!(pType.contains("SHELL_BOX") || pType.contains("BOX_SHELL"))) {
            issues.add(AssemblyMacroSupport.warn("$.macro.verticalProfile", "W_MACRO_VERTICAL_PROFILE_UNSUPPORTED", "verticalProfile 当前仅支持 SHELL_BOX（当前=" + pType + "）"));
            return primary;
        }

        Integer pw = AssemblyMacroSupport.intOrNull(primary.get("w"));
        Integer pd = AssemblyMacroSupport.intOrNull(primary.get("d"));
        Integer ph = AssemblyMacroSupport.intOrNull(primary.get("h"));
        if (pw == null || pd == null || ph == null) {
            issues.add(AssemblyMacroSupport.warn("$.macro.verticalProfile", "W_MACRO_VERTICAL_PROFILE_DIM", "verticalProfile 需要主组件含 w/d/h"));
            return primary;
        }

        Map<String, Object> g = graph;
        if (g == null) {
            g = new java.util.LinkedHashMap<>();
            root.put("graph", g);
        }
        Object co = g.get("components");
        List<Object> comps;
        if (co instanceof List<?> list) comps = (List<Object>) list;
        else {
            comps = new java.util.ArrayList<>();
            g.put("components", comps);
        }

        java.util.HashSet<String> used = new java.util.HashSet<>();
        int primaryIndex = -1;
        String primaryId = AssemblyMacroSupport.str(primary.get("id"), "").trim();
        for (int i = 0; i < comps.size(); i++) {
            Object it = comps.get(i);
            if (it instanceof Map<?, ?> cm) {
                String id = AssemblyMacroSupport.str(cm.get("id"), "").trim();
                if (!id.isEmpty()) used.add(id);
                if (id.equals(primaryId)) primaryIndex = i;
            }
        }

        if (primaryIndex < 0) {
            issues.add(AssemblyMacroSupport.warn("$.macro.verticalProfile", "W_MACRO_VERTICAL_PROFILE_NOT_FOUND", "verticalProfile: 未在主组件列表中找到主组件 id=" + primaryId));
            return primary;
        }

        List<Segment> segments = new java.util.ArrayList<>();
        double baseW = pw;
        double baseD = pd;
        int totalHeight = 0;
        for (Object segObj : vpList) {
            if (!(segObj instanceof Map<?, ?> segMap)) continue;
            Map<String, Object> seg = (Map<String, Object>) segMap;
            Integer segH = AssemblyMacroSupport.intOrNull(seg.get("height"));
            if (segH == null || segH <= 0) continue;
            double scaleTop = AssemblyMacroSupport.d(seg.get("scaleTop"), AssemblyMacroSupport.d(seg.get("scale_top"), 1.0));
            if (scaleTop < 0.1) scaleTop = 0.1;
            if (scaleTop > 3.0) scaleTop = 3.0;
            segments.add(new Segment(segH, scaleTop));
            totalHeight += segH;
        }

        if (segments.isEmpty()) return primary;

        if (Math.abs(totalHeight - ph) > 2) {
            issues.add(AssemblyMacroSupport.warn("$.macro.verticalProfile", "W_MACRO_VERTICAL_PROFILE_HEIGHT_MISMATCH",
                "verticalProfile 总高度 (" + totalHeight + ") 与主组件高度 (" + ph + ") 不匹配，将使用分段高度"));
        }

        Object atObj = primary.get("at");
        int baseX = 0, baseY = 0, baseZ = 0;
        if (atObj instanceof Map<?, ?> atMap) {
            baseX = AssemblyMacroSupport.intOrNull(atMap.get("x")) != null ? AssemblyMacroSupport.intOrNull(atMap.get("x")) : 0;
            baseY = AssemblyMacroSupport.intOrNull(atMap.get("y")) != null ? AssemblyMacroSupport.intOrNull(atMap.get("y")) : 0;
            baseZ = AssemblyMacroSupport.intOrNull(atMap.get("z")) != null ? AssemblyMacroSupport.intOrNull(atMap.get("z")) : 0;
        }

        java.util.Map<String, Object> baseProps = new java.util.LinkedHashMap<>();
        for (java.util.Map.Entry<String, Object> e : primary.entrySet()) {
            String k = e.getKey();
            if (!k.equals("id") && !k.equals("type") && !k.equals("op") && !k.equals("w") && !k.equals("d") && !k.equals("h") && !k.equals("at")) {
                baseProps.put(k, e.getValue());
            }
        }

        List<Object> segmentComps = new java.util.ArrayList<>();
        int currentY = baseY;
        double currentW = baseW;
        double currentD = baseD;

        for (int i = 0; i < segments.size(); i++) {
            Segment seg = segments.get(i);
            int segH = seg.height;
            double nextScale = seg.scaleTop;

            int segW = Math.max(3, (int) Math.round(currentW));
            int segD = Math.max(3, (int) Math.round(currentD));
            double nextW = baseW * nextScale;
            double nextD = baseD * nextScale;

            String segId = (i == 0) ? primaryId : AssemblyMacroSupport.uniqueId(primaryId + "_Seg" + (i + 1), used);
            used.add(segId);

            Map<String, Object> segComp = new java.util.LinkedHashMap<>();
            segComp.put("id", segId);
            segComp.put("type", "SHELL_BOX");
            segComp.put("at", java.util.Map.of("x", baseX, "y", currentY, "z", baseZ));
            segComp.put("w", segW);
            segComp.put("d", segD);
            segComp.put("h", segH);
            segComp.putAll(baseProps);

            segmentComps.add(segComp);
            currentY += segH;
            currentW = nextW;
            currentD = nextD;
        }

        comps.set(primaryIndex, segmentComps.get(0));
        for (int i = 1; i < segmentComps.size(); i++) {
            comps.add(primaryIndex + i, segmentComps.get(i));
        }

        Map<String, Object> newPrimary = (Map<String, Object>) segmentComps.get(0);

        issues.add(AssemblyMacroSupport.warn("$.macro.verticalProfile", "W_MACRO_VERTICAL_PROFILE",
            "verticalProfile 将主组件分解为 " + segments.size() + " 个分段 SHELL_BOX"));

        return newPrimary;
    }

    private static class Segment {
        final int height;
        final double scaleTop;

        Segment(int height, double scaleTop) {
            this.height = height;
            this.scaleTop = scaleTop;
        }
    }

    static void applyShapeType(Map<String, Object> primary, Map<String, Object> macro, List<AssemblyValidationIssue> issues) {
        String st = AssemblyMacroSupport.str(macro.get("shapeType"), AssemblyMacroSupport.str(macro.get("shape"), null));
        if (st == null || st.isBlank()) return;
        String shape = st.trim().toUpperCase(Locale.ROOT);
        if (shape.equals("RECT") || shape.equals("RECTANGLE") || shape.equals("BOX")) shape = "RECTANGLE";
        if (shape.equals("CIRCLE") || shape.equals("CYL") || shape.equals("CYLINDER")) shape = "CIRCLE";
        if (shape.equals("HEX")) shape = "HEXAGON";

        String type = AssemblyMacroSupport.str(primary.get("type"), AssemblyMacroSupport.str(primary.get("op"), "")).trim().toUpperCase(Locale.ROOT);
        if (type.isBlank()) return;

        switch (shape) {
            case "CIRCLE" -> {
                if (type.contains("SHELL_BOX") || type.contains("BOX_SHELL")) {
                    Integer w = AssemblyMacroSupport.intOrNull(primary.get("w"));
                    Integer d = AssemblyMacroSupport.intOrNull(primary.get("d"));
                    int r = 6;
                    if (w != null || d != null) {
                        int ww = w != null ? w : d;
                        int dd = d != null ? d : w;
                        r = Math.max(2, Math.round((ww + dd) / 4.0f));
                    }
                    Integer h = AssemblyMacroSupport.intOrNull(primary.get("h"));
                    primary.put("type", "CYLINDER");
                    primary.remove("w");
                    primary.remove("d");
                    primary.put("r", r);
                    if (h != null) primary.put("h", h);
                    issues.add(AssemblyMacroSupport.warn("$.macro.shapeType", "W_MACRO_SHAPE", "shapeType=CIRCLE: converted main component from SHELL_BOX to CYLINDER (r=" + r + ")"));
                }
            }
            case "RECTANGLE" -> {
                if (type.contains("CYLINDER")) {
                    Integer r = AssemblyMacroSupport.intOrNull(primary.get("r"));
                    if (r == null) r = AssemblyMacroSupport.intOrNull(primary.get("radius"));
                    int w = (r != null) ? (r * 2 + 1) : 15;
                    Integer h = AssemblyMacroSupport.intOrNull(primary.get("h"));
                    primary.put("type", "SHELL_BOX");
                    primary.remove("r");
                    primary.remove("radius");
                    primary.put("w", w);
                    primary.put("d", w);
                    if (h != null) primary.put("h", h);
                    issues.add(AssemblyMacroSupport.warn("$.macro.shapeType", "W_MACRO_SHAPE", "shapeType=RECTANGLE: converted main component from CYLINDER to SHELL_BOX (w=d=" + w + ")"));
                }
            }
            case "HEXAGON", "HEXAGONAL" -> {
                if (type.contains("SHELL_BOX") || type.contains("BOX_SHELL")) {
                    Integer w = AssemblyMacroSupport.intOrNull(primary.get("w"));
                    Integer d = AssemblyMacroSupport.intOrNull(primary.get("d"));
                    Integer h = AssemblyMacroSupport.intOrNull(primary.get("h"));
                    if (w == null || d == null || h == null) return;

                    int r = Math.max(3, Math.round((w + d) / 6.0f));
                    List<Map<String, Object>> points = new ArrayList<>();
                    for (int i = 0; i < 6; i++) {
                        double ang = (Math.PI * 2.0) * (i / 6.0);
                        int px = (int) Math.round(Math.cos(ang) * r);
                        int pz = (int) Math.round(Math.sin(ang) * r);
                        points.add(Map.of("x", px, "z", pz));
                    }

                    primary.put("type", "EXTRUDE_POLYGON");
                    primary.put("shape", "POINTS");
                    primary.put("points", points);
                    primary.put("h", h);
                    primary.remove("w");
                    primary.remove("d");
                    issues.add(AssemblyMacroSupport.warn("$.macro.shapeType", "W_MACRO_SHAPE", "shapeType=HEXAGON: converted main component from SHELL_BOX to EXTRUDE_POLYGON (r≈" + r + ")"));
                } else {
                    issues.add(AssemblyMacroSupport.warn("$.macro.shapeType", "W_MACRO_SHAPE_UNSUPPORTED", "shapeType=HEXAGON currently supports only SHELL_BOX-like primary components"));
                }
            }
            default ->
                    issues.add(AssemblyMacroSupport.warn("$.macro.shapeType", "W_MACRO_SHAPE_UNSUPPORTED", "shapeType unsupported: " + st));
        }
    }

    static void applyHeightScale(Map<String, Object> primary, Map<String, Object> macro, List<AssemblyValidationIssue> issues) {
        Object hs = macro.get("heightScale");
        if (hs == null) hs = macro.get("height_scale");
        if (hs == null) return;

        double scale = Double.NaN;
        if (hs instanceof Number n) scale = n.doubleValue();
        else {
            String s = String.valueOf(hs).trim().toUpperCase(Locale.ROOT);
            if (s.isEmpty()) return;
            switch (s) {
                case "LOW", "SHORT" -> scale = 0.7;
                case "MEDIUM", "MID" -> scale = 1.0;
                case "HIGH", "TALL" -> scale = 1.6;
                default -> {
                    try {
                        scale = Double.parseDouble(s);
                    } catch (Exception e) {
                        AssemblyMacroSupport.LOG.debug("parse heightScale failed value={}", s);
                    }
                }
            }
        }
        if (Double.isNaN(scale) || scale <= 0.0) return;
        if (scale < 0.2) scale = 0.2;
        if (scale > 4.0) scale = 4.0;

        Integer h = AssemblyMacroSupport.intOrNull(primary.get("h"));
        if (h == null) h = AssemblyMacroSupport.intOrNull(primary.get("height"));
        if (h == null) return;
        int nh = Math.max(2, (int) Math.round(h * scale));
        primary.put("h", nh);
        issues.add(AssemblyMacroSupport.warn("$.macro.heightScale", "W_MACRO_HEIGHT", "heightScale applied to primary.h: " + h + " -> " + nh));
    }

    static void applyRoofMacro(Map<String, Object> root,
                               Map<String, Object> graph,
                               Object compsObj,
                               Map<String, Object> primary,
                               Map<String, Object> macro,
                               List<AssemblyValidationIssue> issues) {
        String roofType = AssemblyMacroSupport.str(macro.get("roofType"), AssemblyMacroSupport.str(macro.get("roof_type"), null));
        if (roofType == null || roofType.isBlank()) return;
        String rt = roofType.trim().toUpperCase(Locale.ROOT);
        if (rt.equals("FLAT") || rt.equals("GABLE")) {
            if (!(compsObj instanceof List<?> list)) return;
            @SuppressWarnings("unchecked")
            List<Object> comps = (List<Object>) list;
            boolean hasRoof = false;
            for (Object it : comps) {
                if (!(it instanceof Map<?, ?> cm)) continue;
                String t = AssemblyMacroSupport.str(cm.get("type"), AssemblyMacroSupport.str(cm.get("op"), "")).trim().toUpperCase(Locale.ROOT);
                if (t.contains("ROOF")) { hasRoof = true; break; }
            }

            Integer rise = roofCurvatureToRise(macro.get("roofCurvature"), macro.get("roof_curvature"), primary);
            if (hasRoof) {
                if (rise != null) {
                    for (Object it : comps) {
                        if (!(it instanceof Map<?, ?> cm)) continue;
                        Map<String, Object> c = (Map<String, Object>) cm;
                        String t = AssemblyMacroSupport.str(c.get("type"), AssemblyMacroSupport.str(c.get("op"), "")).trim().toUpperCase(Locale.ROOT);
                        if (!t.contains("ROOF")) continue;
                        if (c.get("rise") == null) {
                            c.put("rise", rise);
                            issues.add(AssemblyMacroSupport.warn("$.macro.roofCurvature", "W_MACRO_ROOF_CURVATURE", "roofCurvature applied to existing ROOF_COVER.rise=" + rise));
                        }
                    }
                }
                return;
            }

            Integer h = AssemblyMacroSupport.intOrNull(primary.get("h"));
            String pt = AssemblyMacroSupport.str(primary.get("type"), "").trim().toUpperCase(Locale.ROOT);
            if (h == null) return;

            Integer w = AssemblyMacroSupport.intOrNull(primary.get("w"));
            Integer d = AssemblyMacroSupport.intOrNull(primary.get("d"));
            if (w == null || d == null) {
                if (pt.contains("EXTRUDE")) {
                    int[] bb = polygonBoundsXZ(primary.get("points"));
                    if (bb != null) {
                        w = Math.max(3, bb[1] - bb[0] + 1);
                        d = Math.max(3, bb[3] - bb[2] + 1);
                    }
                }
            }
            if (w == null || d == null) return;

            int overhang = overhangToInt(AssemblyMacroSupport.str(macro.get("overhang"), null));
            Map<String, Object> roof = new java.util.LinkedHashMap<>();
            roof.put("id", "Roof");
            roof.put("type", "ROOF_COVER");
            roof.put("w", w);
            roof.put("d", d);
            roof.put("y", h + 1);
            roof.put("roofType", rt);
            if (overhang > 0) roof.put("overhang", overhang);
            if (rise != null && rt.equals("GABLE")) roof.put("rise", rise);

            Object cpObj = macro.get("roofCurvaturePower");
            if (cpObj == null) cpObj = macro.get("roof_curvature_power");
            if (cpObj != null) {
                try {
                    double cp = (cpObj instanceof Number n) ? n.doubleValue() : Double.parseDouble(String.valueOf(cpObj));
                    if (cp >= 0.1 && cp <= 3.0) roof.put("curvaturePower", cp);
                } catch (Exception e) {
                    AssemblyMacroSupport.LOG.debug("parse roofCurvaturePower failed value={}", cpObj);
                }
            }
            Object clObj = macro.get("roofCornerLift");
            if (clObj == null) clObj = macro.get("roof_corner_lift");
            if (clObj != null) {
                try {
                    double cl = (clObj instanceof Number n) ? n.doubleValue() : Double.parseDouble(String.valueOf(clObj));
                    if (cl >= 0.0 && cl <= 2.0) roof.put("cornerLift", cl);
                } catch (Exception e) {
                    AssemblyMacroSupport.LOG.debug("parse roofCornerLift failed value={}", clObj);
                }
            }

            comps.add(roof);
            issues.add(AssemblyMacroSupport.warn("$.macro.roofType", "W_MACRO_ROOF_ADD", "auto-added ROOF_COVER (roofType=" + rt + ")"));
        }
    }

    private static Integer roofCurvatureToRise(Object a, Object b, Map<String, Object> primary) {
        Object v = (a != null) ? a : b;
        if (v == null) return null;
        double t = Double.NaN;
        if (v instanceof Number n) t = n.doubleValue();
        else {
            String s = String.valueOf(v).trim().toUpperCase(Locale.ROOT);
            if (s.isEmpty()) return null;
            if (s.equals("LOW") || s.equals("SMALL")) t = 0.25;
            else if (s.equals("MEDIUM") || s.equals("MID")) t = 0.5;
            else if (s.equals("HIGH") || s.equals("TALL")) t = 0.85;
            else if (s.contains("FLAT") || s.contains("平")) t = 0.0;
            else if (s.contains("STEEP") || s.contains("陡")) t = 1.0;
            else {
                try { t = Double.parseDouble(s); } catch (Exception e) {
                    AssemblyMacroSupport.LOG.debug("parse roof pitch failed value={}", s);
                }
            }
        }
        if (Double.isNaN(t)) return null;
        if (t < 0.0) t = 0.0;
        if (t > 1.0) t = 1.0;

        int w = AssemblyMacroSupport.intOrNull(primary.get("w")) != null ? AssemblyMacroSupport.intOrNull(primary.get("w")) : 15;
        int d = AssemblyMacroSupport.intOrNull(primary.get("d")) != null ? AssemblyMacroSupport.intOrNull(primary.get("d")) : 15;
        int base = Math.max(2, Math.min(8, Math.max(w, d) / 6));
        return Math.max(1, (int) Math.round(2 + t * (base + 6)));
    }

    private static int[] polygonBoundsXZ(Object ptsObj) {
        if (!(ptsObj instanceof List<?> pts) || pts.isEmpty()) return null;
        int xMin = Integer.MAX_VALUE, xMax = Integer.MIN_VALUE, zMin = Integer.MAX_VALUE, zMax = Integer.MIN_VALUE;
        boolean any = false;
        for (Object it : pts) {
            if (!(it instanceof Map<?, ?> pm)) continue;
            Integer x = AssemblyMacroSupport.intOrNull(pm.get("x"));
            Integer z = AssemblyMacroSupport.intOrNull(pm.get("z"));
            if (x == null || z == null) continue;
            xMin = Math.min(xMin, x);
            xMax = Math.max(xMax, x);
            zMin = Math.min(zMin, z);
            zMax = Math.max(zMax, z);
            any = true;
        }
        if (!any) return null;
        return new int[]{xMin, xMax, zMin, zMax};
    }

    private static int overhangToInt(String s) {
        if (s == null) return 0;
        String t = s.trim().toUpperCase(Locale.ROOT);
        if (t.isEmpty()) return 0;
        return switch (t) {
            case "NONE", "NO" -> 0;
            case "SMALL" -> 1;
            case "MEDIUM" -> 2;
            case "LARGE", "WIDE" -> 3;
            default -> {
                try { yield Math.max(0, Integer.parseInt(t)); } catch (Exception e) { yield 0; }
            }
        };
    }

    static void applyOpenness(Map<String, Object> primary, Map<String, Object> macro, List<AssemblyValidationIssue> issues) {
        Object ov = macro.get("openness");
        if (ov == null) return;
        double o = Double.NaN;
        if (ov instanceof Number n) o = n.doubleValue();
        else {
            String s = String.valueOf(ov).trim();
            if (s.isEmpty()) return;
            try { o = Double.parseDouble(s); } catch (Exception e) {
                AssemblyMacroSupport.LOG.debug("parse roof overhang failed value={}", s);
            }
        }
        if (Double.isNaN(o)) return;
        if (o < 0.0) o = 0.0;
        if (o > 1.0) o = 1.0;

        Object facadeObj = primary.get("facade");
        if (!(facadeObj instanceof Map<?, ?> fm)) return;
        Map<String, Object> facade = (Map<String, Object>) fm;
        Object openingsObj = facade.get("openings");
        if (!(openingsObj instanceof List<?> list) || list.isEmpty()) return;

        for (int i = 0; i < list.size(); i++) {
            Object it = list.get(i);
            if (!(it instanceof Map<?, ?> mm)) continue;
            Map<String, Object> opening = (Map<String, Object>) mm;
            String kind = AssemblyMacroSupport.str(opening.get("kind"), AssemblyMacroSupport.str(opening.get("type"), "")).trim().toUpperCase(Locale.ROOT);
            if (kind.isBlank()) continue;
            if (kind.contains("WINDOW_GRID") || kind.contains("ARCH")) {
                int baseCols = AssemblyMacroSupport.intOrNull(opening.get("cols")) != null ? AssemblyMacroSupport.intOrNull(opening.get("cols")) : 3;
                int baseRows = AssemblyMacroSupport.intOrNull(opening.get("rows")) != null ? AssemblyMacroSupport.intOrNull(opening.get("rows")) : 2;
                int cols = AssemblyMacroSupport.clampInt((int) Math.round(2 + o * 10), 1, 24);
                int rows = AssemblyMacroSupport.clampInt((int) Math.round(1 + o * 6), 1, 12);
                opening.put("cols", cols);
                opening.put("rows", rows);
                issues.add(AssemblyMacroSupport.warn("$.macro.openness" + ".facade.openings[" + i + "]", "W_MACRO_OPENNESS", "openness=" + o + ": rows/cols " + baseRows + "/" + baseCols + " -> " + rows + "/" + cols));
            }
        }
    }

    static void applySymmetryToConnections(List<?> conns, Map<String, Object> macro, List<AssemblyValidationIssue> issues) {
        String sym = AssemblyMacroSupport.str(macro.get("symmetry"), null);
        if (sym == null || sym.isBlank()) return;
        String s = sym.trim().toUpperCase(Locale.ROOT);
        String routingStyle = null;
        if (s.contains("ASYM") || s.contains("RANDOM")) routingStyle = "ORGANIC";
        else if (s.contains("AXIS") || s.contains("RADIAL") || s.contains("SYMM")) routingStyle = "PLANNED";
        if (routingStyle == null) return;

        for (int i = 0; i < conns.size(); i++) {
            Object it = conns.get(i);
            if (!(it instanceof Map<?, ?> cm)) continue;
            Map<String, Object> c = (Map<String, Object>) cm;
            if (c.get("routingStyle") != null) continue;
            c.put("routingStyle", routingStyle);
            issues.add(AssemblyMacroSupport.warn("$.macro.symmetry", "W_MACRO_SYMMETRY", "injected connections[" + i + "].routingStyle=" + routingStyle));
        }
    }
}
