package com.formacraft.server.assembly.macro;

import com.formacraft.server.assembly.validation.AssemblyValidationIssue;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

final class AssemblyMacroInjectApplier {
    private AssemblyMacroInjectApplier() {}

    @SuppressWarnings("unchecked")
    static void applyBridgeTower(Map<String, Object> root,
                                 Map<String, Object> graph,
                                 Object compsObj,
                                 Map<String, Object> macro,
                                 List<AssemblyValidationIssue> issues) {
        Object btObj = macro.get("bridgeTower");
        if (btObj == null) btObj = macro.get("bridge_tower");
        if (btObj == null) return;

        Map<String, Object> bt = null;
        if (btObj instanceof Map<?, ?> mm) bt = AssemblyMacroSupport.safeMap(mm);
        else if (btObj instanceof Boolean b && b) bt = new java.util.LinkedHashMap<>();
        if (bt == null) {
            issues.add(AssemblyMacroSupport.warn("$.macro.bridgeTower", "W_MACRO_BRIDGE_TOWER_TYPE", "bridgeTower 建议是对象（map）或 true（使用默认值）"));
            return;
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
        for (Object it : comps) {
            if (it instanceof Map<?, ?> cm) {
                String id = AssemblyMacroSupport.str(cm.get("id"), "").trim();
                if (!id.isEmpty()) used.add(id);
            }
        }

        String baseId = AssemblyMacroSupport.str(bt.get("id"), "BridgeTower").trim();
        baseId = AssemblyMacroSupport.uniqueId(baseId, used);
        used.add(baseId);

        int ax, ay, az;
        Object at = bt.get("at");
        if (at instanceof Map<?, ?> am) {
            ax = AssemblyMacroSupport.i(am.get("x"), 0);
            ay = AssemblyMacroSupport.i(am.get("y"), 0);
            az = AssemblyMacroSupport.i(am.get("z"), 0);
        } else {
            ax = AssemblyMacroSupport.i(bt.get("x"), 0);
            ay = AssemblyMacroSupport.i(bt.get("y"), 0);
            az = AssemblyMacroSupport.i(bt.get("z"), 0);
        }
        Map<String, Object> atMap = java.util.Map.of("x", ax, "y", ay, "z", az);

        int towerW = AssemblyMacroSupport.clamp(AssemblyMacroSupport.i(bt.get("towerW"), AssemblyMacroSupport.i(bt.get("w"), 4)), 3, 129);
        int towerD = AssemblyMacroSupport.clamp(AssemblyMacroSupport.i(bt.get("towerD"), AssemblyMacroSupport.i(bt.get("d"), 6)), 3, 129);
        int towerH = AssemblyMacroSupport.clamp(AssemblyMacroSupport.i(bt.get("towerH"), AssemblyMacroSupport.i(bt.get("h"), 30)), 6, 255);

        Object towerWall = bt.get("towerWall");
        if (towerWall == null) towerWall = bt.get("towerMaterial");
        Object towerRoof = bt.get("towerRoof");
        if (towerRoof == null) towerRoof = towerWall;
        Object towerFloor = bt.get("towerFloor");
        if (towerFloor == null) towerFloor = "smooth_stone";

        Object saddleObj = bt.get("saddle");
        Map<String, Object> saddle = (saddleObj instanceof Map<?, ?> sm) ? AssemblyMacroSupport.safeMap(sm) : null;
        int rollerR = AssemblyMacroSupport.clamp(AssemblyMacroSupport.i(saddle != null ? saddle.get("r") : null, AssemblyMacroSupport.i(bt.get("rollerR"), 2)), 2, 8);
        int rollerH = AssemblyMacroSupport.clamp(AssemblyMacroSupport.i(saddle != null ? saddle.get("h") : null, AssemblyMacroSupport.i(bt.get("rollerH"), 2)), 1, 8);
        int rollerSpacing = AssemblyMacroSupport.clamp(AssemblyMacroSupport.i(saddle != null ? saddle.get("spacing") : null, AssemblyMacroSupport.i(bt.get("rollerSpacing"), 4)), 1, 32);
        String saddleAxis = AssemblyMacroSupport.str(saddle != null ? saddle.get("axis") : null, AssemblyMacroSupport.str(bt.get("saddleAxis"), "Z")).trim().toUpperCase(Locale.ROOT);
        Object rollerMat = (saddle != null && saddle.get("material") != null) ? saddle.get("material") : bt.get("rollerMaterial");
        if (rollerMat == null) rollerMat = (towerWall != null ? towerWall : "iron_block");

        int saddleY = AssemblyMacroSupport.i(bt.get("saddleY"), ay + towerH + 2);
        int o0 = -(rollerSpacing / 2);
        int o1 = (rollerSpacing / 2);

        Map<String, Object> tower = new java.util.LinkedHashMap<>();
        tower.put("id", baseId);
        tower.put("type", "SHELL_BOX");
        tower.put("at", atMap);
        tower.put("w", towerW);
        tower.put("d", towerD);
        tower.put("h", towerH);
        if (towerWall != null) tower.put("wall", towerWall);
        tower.put("floor", towerFloor);
        if (towerRoof != null) tower.put("roof", towerRoof);

        Map<String, Object> ports = null;
        Object portsObj = tower.get("ports");
        if (portsObj instanceof Map<?, ?> pm) ports = AssemblyMacroSupport.safeMap(pm);
        if (ports == null) ports = new java.util.LinkedHashMap<>();
        int saddleYLocal = saddleY - ay;
        if ("X".equals(saddleAxis)) {
            AssemblyMacroSupport.putPortIfAbsent(ports, "saddle_left", -Math.abs(o1), saddleYLocal, 0);
            AssemblyMacroSupport.putPortIfAbsent(ports, "saddle_right", Math.abs(o1), saddleYLocal, 0);
            AssemblyMacroSupport.putPortIfAbsent(ports, "saddle_a", o0, saddleYLocal, 0);
            AssemblyMacroSupport.putPortIfAbsent(ports, "saddle_b", o1, saddleYLocal, 0);
        } else {
            AssemblyMacroSupport.putPortIfAbsent(ports, "saddle_left", 0, saddleYLocal, -Math.abs(o1));
            AssemblyMacroSupport.putPortIfAbsent(ports, "saddle_right", 0, saddleYLocal, Math.abs(o1));
            AssemblyMacroSupport.putPortIfAbsent(ports, "saddle_a", 0, saddleYLocal, o0);
            AssemblyMacroSupport.putPortIfAbsent(ports, "saddle_b", 0, saddleYLocal, o1);
        }
        AssemblyMacroSupport.putPortIfAbsent(ports, "saddle_center", 0, saddleYLocal, 0);
        AssemblyMacroSupport.putPortIfAbsent(ports, "cable_top", 0, towerH, 0);
        AssemblyMacroSupport.putPortIfAbsent(ports, "cable", 0, saddleYLocal, 0);
        if (!ports.isEmpty()) tower.put("ports", ports);
        comps.add(tower);

        int margin = AssemblyMacroSupport.clamp(AssemblyMacroSupport.i(bt.get("foundationMargin"), 1), 0, 16);
        int halfW = towerW / 2;
        int halfD = towerD / 2;
        int fx0 = -halfW - margin, fx1 = halfW + margin;
        int fz0 = -halfD - margin, fz1 = halfD + margin;
        int fDepth = AssemblyMacroSupport.clamp(AssemblyMacroSupport.i(bt.get("foundationDepth"), AssemblyMacroSupport.i(bt.get("maxDepth"), 24)), 0, 512);
        Object fMat = bt.get("foundationMaterial");
        if (fMat == null) fMat = "stone_bricks";

        String fId = AssemblyMacroSupport.uniqueId(baseId + "_Foundation", used);
        used.add(fId);
        Map<String, Object> foundation = new java.util.LinkedHashMap<>();
        foundation.put("id", fId);
        foundation.put("type", "ANCHOR_FOOTPRINT");
        foundation.put("at", atMap);
        foundation.put("x0", fx0);
        foundation.put("x1", fx1);
        foundation.put("z0", fz0);
        foundation.put("z1", fz1);
        foundation.put("yBase", ay);
        foundation.put("maxDepth", fDepth);
        foundation.put("material", fMat);
        comps.add(foundation);

        String s0 = AssemblyMacroSupport.uniqueId(baseId + "_SaddleA", used); used.add(s0);
        String s1 = AssemblyMacroSupport.uniqueId(baseId + "_SaddleB", used); used.add(s1);

        comps.add(AssemblyMacroSupport.makeRoller(s0, ax, saddleY, az, saddleAxis, o0, rollerR, rollerH, rollerMat));
        comps.add(AssemblyMacroSupport.makeRoller(s1, ax, saddleY, az, saddleAxis, o1, rollerR, rollerH, rollerMat));

        Object notchObj = bt.get("notch");
        Map<String, Object> notch = (notchObj instanceof Map<?, ?> nm) ? AssemblyMacroSupport.safeMap(nm) : null;
        boolean notchOn = (notch != null) ? AssemblyMacroSupport.bool(notch.get("enabled"), true) : AssemblyMacroSupport.bool(bt.get("notchEnabled"), false);
        if (notchOn) {
            int notchDepth = AssemblyMacroSupport.clamp(AssemblyMacroSupport.i(notch != null ? notch.get("depth") : null, AssemblyMacroSupport.i(bt.get("notchDepth"), 2)), 1, 12);
            int notchWidth = AssemblyMacroSupport.clamp(AssemblyMacroSupport.i(notch != null ? notch.get("width") : null, AssemblyMacroSupport.i(bt.get("notchWidth"), 2)), 1, 64);
            int notchLen = AssemblyMacroSupport.clamp(AssemblyMacroSupport.i(notch != null ? notch.get("length") : null, AssemblyMacroSupport.i(bt.get("notchLength"), rollerSpacing + 4)), 2, 128);
            int notchTopY = AssemblyMacroSupport.i(notch != null ? notch.get("topY") : null, AssemblyMacroSupport.i(bt.get("notchTopY"), towerH + 1));
            int nx0, nx1, nz0, nz1;
            if ("X".equals(saddleAxis)) {
                nx0 = -(notchLen / 2);
                nx1 = (notchLen / 2);
                nz0 = -(notchWidth / 2);
                nz1 = (notchWidth / 2);
            } else {
                nz0 = -(notchLen / 2);
                nz1 = (notchLen / 2);
                nx0 = -(notchWidth / 2);
                nx1 = (notchWidth / 2);
            }
            nx0 = Math.max(nx0, -halfW);
            nx1 = Math.min(nx1, halfW);
            nz0 = Math.max(nz0, -halfD);
            nz1 = Math.min(nz1, halfD);
            int ny1 = notchTopY;
            int ny0 = notchTopY - notchDepth + 1;
            ny0 = Math.max(0, ny0);
            ny1 = Math.min(towerH + 1, ny1);
            if (nx0 <= nx1 && nz0 <= nz1 && ny0 <= ny1) {
                String nid = AssemblyMacroSupport.uniqueId(baseId + "_SaddleNotch", used); used.add(nid);
                Map<String, Object> carve = new java.util.LinkedHashMap<>();
                carve.put("id", nid);
                carve.put("type", "CLEAR_BOX");
                carve.put("at", atMap);
                carve.put("x0", nx0); carve.put("y0", ny0); carve.put("z0", nz0);
                carve.put("x1", nx1); carve.put("y1", ny1); carve.put("z1", nz1);
                comps.add(carve);
            }
        }

        Object holesObj = bt.get("holes");
        if (holesObj == null) holesObj = bt.get("cableHoles");
        if (holesObj instanceof List<?> holes) {
            for (int hi = 0; hi < holes.size(); hi++) {
                Object hv = holes.get(hi);
                if (!(hv instanceof Map<?, ?> hm)) continue;
                Map<String, Object> hole = AssemblyMacroSupport.safeMap(hm);
                if (hole == null) continue;
                String face = AssemblyMacroSupport.str(hole.get("face"), "").trim().toUpperCase(Locale.ROOT);
                if (face.isBlank()) continue;
                int hy = AssemblyMacroSupport.i(hole.get("y"), towerH);
                int r = AssemblyMacroSupport.clamp(AssemblyMacroSupport.i(hole.get("r"), AssemblyMacroSupport.i(hole.get("radius"), 1)), 1, 8);
                int len = AssemblyMacroSupport.clamp(AssemblyMacroSupport.i(hole.get("len"), AssemblyMacroSupport.i(hole.get("length"), 6)), 1, 128);
                int hx = AssemblyMacroSupport.i(hole.get("x"), 0);
                int hz = AssemblyMacroSupport.i(hole.get("z"), 0);

                int cx0, cx1, cz0, cz1;
                int cy0 = Math.max(0, hy - r), cy1 = Math.min(towerH + 1, hy + r);

                if (face.equals("EAST") || face.equals("WEST")) {
                    int sx = face.equals("WEST") ? -halfW : halfW;
                    int ex = sx + (face.equals("WEST") ? len : -len);
                    cx0 = Math.min(sx, ex); cx1 = Math.max(sx, ex);
                    cz0 = Math.max(-halfD, hz - r); cz1 = Math.min(halfD, hz + r);
                } else if (face.equals("NORTH") || face.equals("SOUTH")) {
                    int sz = face.equals("NORTH") ? -halfD : halfD;
                    int ez = sz + (face.equals("NORTH") ? len : -len);
                    cz0 = Math.min(sz, ez); cz1 = Math.max(sz, ez);
                    cx0 = Math.max(-halfW, hx - r); cx1 = Math.min(halfW, hx + r);
                } else {
                    continue;
                }
                cx0 = Math.max(cx0, -halfW); cx1 = Math.min(cx1, halfW);
                cz0 = Math.max(cz0, -halfD); cz1 = Math.min(cz1, halfD);
                if (cx0 <= cx1 && cz0 <= cz1 && cy0 <= cy1) {
                    String hid = AssemblyMacroSupport.uniqueId(baseId + "_CableHole_" + (hi + 1), used); used.add(hid);
                    Map<String, Object> carve = new java.util.LinkedHashMap<>();
                    carve.put("id", hid);
                    carve.put("type", "CLEAR_BOX");
                    carve.put("at", atMap);
                    carve.put("x0", cx0); carve.put("y0", cy0); carve.put("z0", cz0);
                    carve.put("x1", cx1); carve.put("y1", cy1); carve.put("z1", cz1);
                    comps.add(carve);
                }
            }
        }

        issues.add(AssemblyMacroSupport.warn("$.macro.bridgeTower", "W_MACRO_BRIDGE_TOWER",
                "bridgeTower injected: " + baseId + " (SHELL_BOX) + " + fId + " (ANCHOR_FOOTPRINT) + saddle rollers"));
    }

    static void applySubtractHoles(Map<String, Object> root,
                                   Map<String, Object> graph,
                                   Object compsObj,
                                   Map<String, Object> primary,
                                   Map<String, Object> macro,
                                   List<AssemblyValidationIssue> issues) {
        Object holesObj = macro.get("subtractHoles");
        if (holesObj == null) holesObj = macro.get("subtract_holes");
        if (holesObj == null) return;
        if (!(holesObj instanceof List<?> holesList)) return;
        if (holesList.isEmpty()) return;

        if (primary == null) return;
        if (!(compsObj instanceof List<?> list)) return;
        @SuppressWarnings("unchecked")
        List<Object> comps = (List<Object>) list;

        Integer pw = AssemblyMacroSupport.intOrNull(primary.get("w"));
        Integer pd = AssemblyMacroSupport.intOrNull(primary.get("d"));
        Integer ph = AssemblyMacroSupport.intOrNull(primary.get("h"));
        if (pw == null || pd == null || ph == null) return;

        java.util.HashSet<String> used = new java.util.HashSet<>();
        for (Object it0 : comps) if (it0 instanceof Map<?, ?> cm) {
            String id0 = AssemblyMacroSupport.str(cm.get("id"), "").trim();
            if (!id0.isEmpty()) used.add(id0);
        }

        for (int i = 0; i < holesList.size(); i++) {
            Object holeObj = holesList.get(i);
            if (!(holeObj instanceof Map<?, ?> holeMap)) continue;
            @SuppressWarnings("unchecked")
            Map<String, Object> hole = (Map<String, Object>) holeMap;

            String holeType = AssemblyMacroSupport.str(hole.get("type"), "RECTANGLE").trim().toUpperCase(Locale.ROOT);
            if (!holeType.equals("RECTANGLE") && !holeType.equals("RECT")) continue;

            Integer hw = AssemblyMacroSupport.intOrNull(hole.get("w"));
            Integer hd = AssemblyMacroSupport.intOrNull(hole.get("d"));
            if (hw == null || hd == null) continue;
            if (hw <= 0 || hd <= 0) continue;

            Object atObj = hole.get("at");
            int hcx = 0, hcz = 0;
            if (atObj instanceof Map<?, ?> atMap) {
                hcx = AssemblyMacroSupport.intOrNull(atMap.get("x")) != null ? AssemblyMacroSupport.intOrNull(atMap.get("x")) : 0;
                hcz = AssemblyMacroSupport.intOrNull(atMap.get("z")) != null ? AssemblyMacroSupport.intOrNull(atMap.get("z")) : 0;
            }

            int x0 = hcx - hw / 2;
            int x1 = hcx + hw / 2 - 1;
            int z0 = hcz - hd / 2;
            int z1 = hcz + hd / 2 - 1;
            int y0 = 0;
            int y1 = ph - 1;

            String holeId = AssemblyMacroSupport.uniqueId("Hole" + (i + 1), used);
            used.add(holeId);

            Map<String, Object> clearBox = new java.util.LinkedHashMap<>();
            clearBox.put("id", holeId);
            clearBox.put("type", "CLEAR_BOX");
            clearBox.put("x0", x0);
            clearBox.put("y0", y0);
            clearBox.put("z0", z0);
            clearBox.put("x1", x1);
            clearBox.put("y1", y1);
            clearBox.put("z1", z1);

            comps.add(clearBox);
        }

        if (!holesList.isEmpty()) {
            issues.add(AssemblyMacroSupport.warn("$.macro.subtractHoles", "W_MACRO_SUBTRACT_HOLES", "subtractHoles injected " + holesList.size() + " CLEAR_BOX components"));
        }
    }
}
