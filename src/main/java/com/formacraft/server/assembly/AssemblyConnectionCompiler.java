package com.formacraft.server.assembly;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Compiles topology connections into routed assembly ops.
 */
final class AssemblyConnectionCompiler {
    private AssemblyConnectionCompiler() {}

    static void emitConnection(List<Map<String, Object>> ops,
                                       Map<String, Object> conn,
                                       Map<String, Map<String, Object>> byId,
                                       Map<String, int[]> originById,
                                       Map<String, Map<String, int[]>> portsById) {
        if (conn == null || conn.isEmpty()) return;
        String type = AssemblyCompilerUtils.str(conn.get("type"), "CONNECTOR_LINE").trim().toUpperCase(Locale.ROOT);
        if (type.isBlank()) type = "CONNECTOR_LINE";

        Endpoint a = parseEndpoint(conn.get("from"));
        Endpoint b = parseEndpoint(conn.get("to"));
        if (a == null || b == null) return;

        // Auto-rewrite spline endpoints: if user references start/end/entrance/exit/in/out without direction,
        // and the component is a SPLINE, rewrite to start_{n/e/s/w} / end_{n/e/s/w} (tangent-direction port).
        a = rewriteSplineTangentPort(a, byId, portsById);
        b = rewriteSplineTangentPort(b, byId, portsById);

        int[] p0 = resolveEndpoint(a, byId, originById, portsById);
        int[] p1 = resolveEndpoint(b, byId, originById, portsById);
        if (p0 == null || p1 == null) return;

        // Expand connection type to corresponding execution op.
        // v1 supports:
        // - CONNECTOR_LINE (beam)
        // - PATH (road)
        // - WALL (wall section)
        // - BRIDGE (bridge span)
        String opName = switch (type) {
            case "PATH", "ROAD" -> "PATH_ROUTE";
            case "WALL" -> "WALL_ROUTE";
            case "BRIDGE" -> "BRIDGE_ROUTE";
            default -> "CONNECTOR_LINE";
        };

        // Routing mode for avoid:
        // - routing: "ASTAR" enables a small 2D grid A* router to avoid boxes
        // - avoidAStar: boolean shorthand
        boolean useAStar = false;
        Object routing = conn.get("routing");
        if (routing != null) {
            String rs = String.valueOf(routing).trim().toUpperCase(Locale.ROOT);
            useAStar = rs.contains("ASTAR") || rs.contains("A*") || rs.contains("A_STAR");
        }
        if (!useAStar) {
            Object aa = conn.get("avoidAStar");
            if (aa instanceof Boolean b2) useAStar = b2;
            else if (aa != null) {
                String s = String.valueOf(aa).trim().toLowerCase(Locale.ROOT);
                useAStar = s.equals("true") || s.equals("1") || s.equals("yes") || s.equals("y") || s.equals("on");
            }
        }

        // via[] routing: expand one logical connection into multiple segments.
        // via items support:
        // - "A.port" / {component,port,...}
        // - {x,y,z} (local coords)
        List<int[]> chain = new ArrayList<>();
        chain.add(p0);
        Object viaObj = conn.get("via");
        List<int[]> userVia = new ArrayList<>();
        if (viaObj instanceof List<?> via) {
            for (Object v : via) {
                int[] wp = resolveWaypoint(v, byId, originById, portsById);
                if (wp != null) userVia.add(wp);
            }
        }

        // avoid[] / avoidComponents / avoidAllComponents: derive avoid rects for routing utilities
        List<AssemblyConnectionRouter.Rect2> avoids = parseAvoids(conn.get("avoid"));
        avoids.addAll(parseAvoidComponents(conn, a.componentId, b.componentId, byId, originById));

        // Lead-out / lead-in: encourage routes to leave/arrive along the port direction.
        // - routingAutoLead=true sets defaults (leadOut=3, leadIn=2) unless explicitly provided.
        int leadOut = AssemblyCompilerUtils.i(conn.get("routingLeadOut"), 0);
        int leadIn = AssemblyCompilerUtils.i(conn.get("routingLeadIn"), 0);
        boolean autoLead = false;
        Object al = conn.get("routingAutoLead");
        if (al instanceof Boolean bb) autoLead = bb;
        else if (al != null) {
            String s = String.valueOf(al).trim().toLowerCase(Locale.ROOT);
            autoLead = s.equals("true") || s.equals("1") || s.equals("yes") || s.equals("y") || s.equals("on");
        }
        if (autoLead) {
            if (conn.get("routingLeadOut") == null) leadOut = 3;
            if (conn.get("routingLeadIn") == null) leadIn = 2;
        }
        leadOut = Math.max(0, Math.min(32, leadOut));
        leadIn = Math.max(0, Math.min(32, leadIn));

        boolean leadSoft = useAStar;
        Object ls = conn.get("routingLeadSoft");
        if (ls instanceof Boolean bb2) leadSoft = bb2;
        else if (ls != null) {
            String s = String.valueOf(ls).trim().toLowerCase(Locale.ROOT);
            leadSoft = s.equals("true") || s.equals("1") || s.equals("yes") || s.equals("y") || s.equals("on");
        }

        // routingLeadHard: force explicit lead-out/lead-in waypoints (a short straight "landing") even when using A*.
        // This is helpful for connecting to spline endpoints where we inferred a tangent-direction port.
        // Default: true when routingAutoLead is enabled AND either endpoint uses a directional prefixed port (start_* / end_*).
        Boolean leadHard = null;
        Object lh = conn.get("routingLeadHard");
        if (lh instanceof Boolean bb3) leadHard = bb3;
        else if (lh != null) {
            String s = String.valueOf(lh).trim().toLowerCase(Locale.ROOT);
            leadHard = s.equals("true") || s.equals("1") || s.equals("yes") || s.equals("y") || s.equals("on");
        }
        if (leadHard == null) {
            String ap = AssemblyCompilerUtils.normalizePortKey(a.port);
            String bp = AssemblyCompilerUtils.normalizePortKey(b.port);
            boolean directionalPrefixed = (ap.startsWith("start_") || ap.startsWith("end_") || bp.startsWith("start_") || bp.startsWith("end_"));
            leadHard = autoLead && directionalPrefixed;
        }
        boolean effectiveLeadSoft = leadSoft && !leadHard;

        // If not using A* soft lead (or leadHard=true), insert lead points as explicit via.
        if (!(useAStar && effectiveLeadSoft)) {
            int[] lead0 = (leadOut > 0) ? AssemblyConnectionRouter.computeLeadPoint(p0, a.port, leadOut, avoids) : null;
            int[] lead1 = (leadIn > 0) ? AssemblyConnectionRouter.computeLeadPoint(p1, b.port, leadIn, avoids) : null;
            if (lead0 != null) chain.add(lead0);
            chain.addAll(userVia);
            if (lead1 != null) chain.add(lead1);
        } else {
            chain.addAll(userVia);
        }
        chain.add(p1);

        // A* routing knobs (safe defaults)
        int routingPadDefault = 6;
        long routingMaxAreaDefault = 80000L;
        int routingMaxNodesDefault = 0; // 0 means auto (scaled with area)
        // routingStyle macro: PLANNED / ORGANIC, plus routingStyleStrength (0..1) to interpolate ORGANIC->PLANNED.
        // This only changes defaults; explicit params always win.
        String routingStyle = AssemblyCompilerUtils.str(conn.get("routingStyle"), "").trim().toUpperCase(Locale.ROOT);
        double routingStyleStrength = AssemblyCompilerUtils.d(conn.get("routingStyleStrength"));
        if (!Double.isNaN(routingStyleStrength)) {
            if (routingStyleStrength < 0.0) routingStyleStrength = 0.0;
            if (routingStyleStrength > 1.0) routingStyleStrength = 1.0;
        }
        int preferStraightDefault = 1;
        int preferAxisWeightDefault = 1;
        String preferAxisDefault = "AUTO";
        boolean preferDoorAxisDefault = false;
        Integer leadWeightDefaultOverride = null;
        Integer leadOutWeightDefaultOverride = null;
        Integer leadInWeightDefaultOverride = null;

        // Endpoints for continuous style:
        // ORGANIC (0) -> PLANNED (1)
        final int organicPreferStraight = 0;
        final int plannedPreferStraight = 3;
        final int organicPreferAxisWeight = 0;
        final int plannedPreferAxisWeight = 3;
        final int organicLeadWeight = 1;
        final int plannedLeadWeight = 6;
        final int organicLeadOutWeight = 1;
        final int plannedLeadOutWeight = 7;
        final int organicLeadInWeight = 1;
        final int plannedLeadInWeight = 5;
        if ("PLANNED".equals(routingStyle)) {
            preferStraightDefault = 3;
            preferAxisWeightDefault = 3;
            preferAxisDefault = "AUTO";
            preferDoorAxisDefault = true;
            leadWeightDefaultOverride = 6;
            leadOutWeightDefaultOverride = 7;
            leadInWeightDefaultOverride = 5;
            routingPadDefault = 8;
            routingMaxAreaDefault = 140000L;
        } else if ("ORGANIC".equals(routingStyle)) {
            preferStraightDefault = 0;
            preferAxisWeightDefault = 0;
            preferAxisDefault = "NONE";
            leadWeightDefaultOverride = 1;
            leadOutWeightDefaultOverride = 1;
            leadInWeightDefaultOverride = 1;
            routingPadDefault = 4;
            routingMaxAreaDefault = 60000L;
            routingMaxNodesDefault = 20000; // cap for speed; 0=auto can be expensive on big areas
        }

        // If strength is provided, it overrides routingStyle defaults (still does not override explicit params).
        if (!Double.isNaN(routingStyleStrength)) {
            preferStraightDefault = (int) Math.round(organicPreferStraight + (plannedPreferStraight - organicPreferStraight) * routingStyleStrength);
            preferAxisWeightDefault = (int) Math.round(organicPreferAxisWeight + (plannedPreferAxisWeight - organicPreferAxisWeight) * routingStyleStrength);
            // Axis preference is categorical; use a small threshold to keep true ORGANIC behaving axis-free.
            preferAxisDefault = (routingStyleStrength <= 0.10) ? "NONE" : "AUTO";
            // Door-axis preference is boolean; threshold at 0.5.
            preferDoorAxisDefault = (routingStyleStrength >= 0.50);
            leadWeightDefaultOverride = (int) Math.round(organicLeadWeight + (plannedLeadWeight - organicLeadWeight) * routingStyleStrength);
            leadOutWeightDefaultOverride = (int) Math.round(organicLeadOutWeight + (plannedLeadOutWeight - organicLeadOutWeight) * routingStyleStrength);
            leadInWeightDefaultOverride = (int) Math.round(organicLeadInWeight + (plannedLeadInWeight - organicLeadInWeight) * routingStyleStrength);

            routingPadDefault = (int) Math.round(4 + (8 - 4) * routingStyleStrength);
            routingMaxAreaDefault = Math.round(60000L + (140000L - 60000L) * routingStyleStrength);
            // Let very PLANNED routes use auto-scaling nodes (0), otherwise cap for speed.
            routingMaxNodesDefault = (routingStyleStrength >= 0.85) ? 0 : (int) Math.round(20000 + (-20000) * routingStyleStrength);
        }

        // routingQoS macro: FAST / BALANCED / ROBUST. Only affects defaults; explicit params always win.
        String routingQoS = AssemblyCompilerUtils.str(conn.get("routingQoS"), "").trim().toUpperCase(Locale.ROOT);
        if (routingQoS.isEmpty()) {
            // Auto QoS based on style strength: organic can be fast, planned can spend more time to be robust.
            if (!Double.isNaN(routingStyleStrength)) {
                if (routingStyleStrength < 0.35) routingQoS = "FAST";
                else if (routingStyleStrength < 0.75) routingQoS = "BALANCED";
                else routingQoS = "ROBUST";
            } else if ("PLANNED".equals(routingStyle)) {
                routingQoS = "ROBUST";
            } else if ("ORGANIC".equals(routingStyle)) {
                routingQoS = "FAST";
            }
        }
        switch (routingQoS) {
            case "FAST" -> {
                routingPadDefault = 4;
                routingMaxAreaDefault = Math.min(routingMaxAreaDefault, 60000L);
                routingMaxNodesDefault = 16000;
            }
            case "BALANCED" -> {
                routingPadDefault = 6;
                routingMaxAreaDefault = Math.min(routingMaxAreaDefault, 90000L);
                routingMaxNodesDefault = 0; // allow auto
            }
            case "ROBUST" -> {
                routingPadDefault = 10;
                routingMaxAreaDefault = Math.max(routingMaxAreaDefault, 160000L);
                routingMaxNodesDefault = 0; // allow auto
            }
        }

        int routingPad = Math.max(0, AssemblyCompilerUtils.i(conn.get("routingPad"), routingPadDefault));
        long routingMaxArea = Math.max(2000L, AssemblyCompilerUtils.i(conn.get("routingMaxArea"), (int) routingMaxAreaDefault));
        int routingMaxNodesRaw = AssemblyCompilerUtils.i(conn.get("routingMaxNodes"), routingMaxNodesDefault);
        int routingMaxNodes = (routingMaxNodesRaw == 0) ? 0 : Math.max(2000, routingMaxNodesRaw); // preserve 0=auto

        int preferStraight = Math.max(0, AssemblyCompilerUtils.i(conn.get("routingPreferStraight"), preferStraightDefault)); // turn penalty weight

        // Soft-lead weight: higher => stronger tendency to leave/arrive along port axis.
        // Default ties to preferStraight (unless routingStyle overrides it) so "more planned" routes also respect door axes.
        int leadWeightDefault = (leadWeightDefaultOverride != null) ? leadWeightDefaultOverride : Math.max(1, 2 + preferStraight);
        int leadWeight = AssemblyCompilerUtils.i(conn.get("routingLeadWeight"), leadWeightDefault);
        int leadOutWeight = AssemblyCompilerUtils.i(conn.get("routingLeadOutWeight"), (leadOutWeightDefaultOverride != null) ? leadOutWeightDefaultOverride : leadWeight);
        int leadInWeight = AssemblyCompilerUtils.i(conn.get("routingLeadInWeight"), (leadInWeightDefaultOverride != null) ? leadInWeightDefaultOverride : leadWeight);
        if (leadOutWeight < 0) leadOutWeight = 0;
        if (leadInWeight < 0) leadInWeight = 0;

        // Prefer axis (X/Z/AUTO/NONE) + weight.
        String preferAxis = AssemblyCompilerUtils.str(conn.get("routingPreferAxis"), preferAxisDefault).trim().toUpperCase(Locale.ROOT);
        int preferAxisWeight = Math.max(0, AssemblyCompilerUtils.i(conn.get("routingPreferAxisWeight"), preferAxisWeightDefault));
        boolean preferDoorAxis = preferDoorAxisDefault;
        Object pda = conn.get("routingPreferDoorAxis");
        if (pda instanceof Boolean b3) preferDoorAxis = b3;
        else if (pda != null) {
            String s = String.valueOf(pda).trim().toLowerCase(Locale.ROOT);
            preferDoorAxis = s.equals("true") || s.equals("1") || s.equals("yes") || s.equals("y") || s.equals("on");
        }
        if (preferDoorAxis) {
            String ax = AssemblyConnectionRouter.axisFromPort(a.port);
            if (ax != null) preferAxis = ax;
        }

        // Soft-lead ring metric:
        // - STEPS: use A* gScore (true path step depth; better under detours)
        // - MANHATTAN: use |dx|+|dz| (legacy-ish)
        String leadRing = AssemblyCompilerUtils.str(conn.get("routingLeadRing"), "STEPS").trim().toUpperCase(Locale.ROOT);
        if (!"MANHATTAN".equals(leadRing)) leadRing = "STEPS";
        int leadInStepsMaxNodes = AssemblyCompilerUtils.i(conn.get("routingLeadInStepsMaxNodes"), 0);
        if (leadInStepsMaxNodes < 0) leadInStepsMaxNodes = 0;

        // avoid routing: if any segment crosses an avoid rect, auto-insert detours.
        if (!avoids.isEmpty()) {
            List<int[]> expanded = new ArrayList<>();
            expanded.add(chain.getFirst());
            for (int i = 0; i + 1 < chain.size(); i++) {
                int[] pA = expanded.getLast();
                int[] pB = chain.get(i + 1);
                int leadOutSeg = (useAStar && effectiveLeadSoft && i == 0) ? leadOut : 0;
                int leadInSeg = (useAStar && effectiveLeadSoft && i + 2 == chain.size()) ? leadIn : 0;
                List<int[]> detour = AssemblyConnectionRouter.computeDetour(pA, pB, avoids, useAStar, routingPad, routingMaxArea, routingMaxNodes, preferStraight, preferAxis, preferAxisWeight,
                        leadOutSeg, leadInSeg, a.port, b.port, leadOutWeight, leadInWeight, leadRing, leadInStepsMaxNodes);
                expanded.addAll(detour);
            }
            chain = expanded;
        }

        for (int i = 0; i + 1 < chain.size(); i++) {
            int[] a0 = chain.get(i);
            int[] a1 = chain.get(i + 1);
            Map<String, Object> o = new HashMap<>();
            o.put("op", opName);
            o.put("from", Map.of("x", a0[0], "y", a0[1], "z", a0[2]));
            o.put("to", Map.of("x", a1[0], "y", a1[1], "z", a1[2]));
            AssemblyCompilerUtils.copyInt(conn, o, "thickness", 1);
            AssemblyCompilerUtils.copyInt(conn, o, "h", AssemblyCompilerUtils.i(conn.get("h"), AssemblyCompilerUtils.i(conn.get("height"), 1)));
            AssemblyCompilerUtils.copy(conn, o, "material");

            // optional route params
            AssemblyCompilerUtils.copyInt(conn, o, "width", AssemblyCompilerUtils.i(conn.get("width"), 3));          // PATH/BRIDGE
            AssemblyCompilerUtils.copyInt(conn, o, "wallHeight", AssemblyCompilerUtils.i(conn.get("wallHeight"), 10)); // WALL
            AssemblyCompilerUtils.copyInt(conn, o, "wallThickness", AssemblyCompilerUtils.i(conn.get("wallThickness"), AssemblyCompilerUtils.i(conn.get("thickness"), 5))); // WALL
            AssemblyCompilerUtils.copyInt(conn, o, "foundationDepth", AssemblyCompilerUtils.i(conn.get("foundationDepth"), 3)); // WALL
            AssemblyCompilerUtils.copyInt(conn, o, "maxStep", AssemblyCompilerUtils.i(conn.get("maxStep"), 1));       // PATH/WALL smoothing
            AssemblyCompilerUtils.copy(conn, o, "terrainAdaptation");                            // passthrough hints

            // Provide sane defaults so LLM can omit terrain genes.
            if (!o.containsKey("terrainAdaptation") || o.get("terrainAdaptation") == null) {
                switch (opName) {
                    case "PATH_ROUTE" -> o.put("terrainAdaptation", Map.of(
                            "mode", "DRAPE",
                            "max_step_height", 1,
                            "foundation_depth", 2,
                            "clearHeight", 2
                    ));
                    case "WALL_ROUTE" -> o.put("terrainAdaptation", Map.of(
                            "mode", "DRAPE",
                            "max_step_height", 1,
                            "foundation_depth", 3
                    ));
                    case "BRIDGE_ROUTE" -> o.put("terrainAdaptation", Map.of(
                            "mode", "ANCHOR",
                            "anchorMaxDepth", 64
                    ));
                }
            }
            ops.add(o);
        }
    }

    record Endpoint(String componentId, String port, String anchor, int dx, int dy, int dz) {}

    static Endpoint rewriteSplineTangentPort(Endpoint e,
                                                     Map<String, Map<String, Object>> byId,
                                                     Map<String, Map<String, int[]>> portsById) {
        if (e == null || e.componentId == null || e.componentId.isBlank()) return e;
        if (e.port == null || e.port.isBlank()) return e;
        if (byId == null || portsById == null) return e;

        Map<String, Object> comp = byId.get(e.componentId);
        if (comp == null) return e;
        String type = AssemblyCompilerUtils.str(comp.get("type"), "").trim().toUpperCase(Locale.ROOT);
        if (type.isBlank()) type = AssemblyCompilerUtils.str(comp.get("op"), "").trim().toUpperCase(Locale.ROOT);
        if (!type.contains("SPLINE")) return e;

        String p = AssemblyCompilerUtils.normalizePortKey(e.port);
        // If already directional (e.g., start_east) don't touch it.
        if (p.startsWith("start_") || p.startsWith("end_")) return e;

        String kind = null; // "start" or "end"
        if (p.equals("start") || p.equals("entrance") || p.equals("in")) kind = "start";
        if (p.equals("end") || p.equals("exit") || p.equals("out")) kind = "end";
        if (kind == null) return e;

        Map<String, int[]> ports = portsById.get(e.componentId);
        if (ports == null || ports.isEmpty()) return e;

        // We generate at most one of these, but check all 4 for robustness/user overrides.
        String[] dirs = new String[]{"north", "south", "east", "west"};
        for (String d : dirs) {
            String k = kind + "_" + d;
            if (ports.containsKey(k)) {
                return new Endpoint(e.componentId, k, e.anchor, e.dx, e.dy, e.dz);
            }
        }
        return e;
    }

    static Endpoint parseEndpoint(Object v) {
        switch (v) {
            case null -> {
                return null;
            }
            case String s -> {
                String id = s.trim();
                if (id.isEmpty()) return null;
                // allow "A.south"
                int dot = id.indexOf('.');
                if (dot > 0 && dot < id.length() - 1) {
                    String cid = id.substring(0, dot).trim();
                    String port = id.substring(dot + 1).trim();
                    if (!cid.isEmpty() && !port.isEmpty()) return new Endpoint(cid, port, "CENTER", 0, 0, 0);
                }
                return new Endpoint(id, null, "CENTER", 0, 0, 0);
            }
            case Map<?, ?> mm -> {
                String id = AssemblyCompilerUtils.str(mm.get("component"), null);
                if (id == null) id = AssemblyCompilerUtils.str(mm.get("id"), null);
                if (id == null || id.isBlank()) return null;
                String port = AssemblyCompilerUtils.str(mm.get("port"), null);
                String anchor = AssemblyCompilerUtils.str(mm.get("anchor"), "CENTER").trim().toUpperCase(Locale.ROOT);
                int dx = AssemblyCompilerUtils.i(mm.get("x"), 0);
                int dy = AssemblyCompilerUtils.i(mm.get("y"), 0);
                int dz = AssemblyCompilerUtils.i(mm.get("z"), 0);
                return new Endpoint(id.trim(), port != null && !port.isBlank() ? port.trim() : null, anchor, dx, dy, dz);
            }
            default -> {
            }
        }
        return null;
    }

    // =============================================================================================
    // Avoid routing helpers (2D, XZ)
    // =============================================================================================

    record Rect2(int xMin, int zMin, int xMax, int zMax) {}

    static List<AssemblyConnectionRouter.Rect2> parseAvoids(Object v) {
        List<AssemblyConnectionRouter.Rect2> out = new ArrayList<>();
        if (!(v instanceof List<?> list)) return out;
        for (Object it : list) {
            if (!(it instanceof Map<?, ?> m)) continue;
            int x0 = AssemblyCompilerUtils.i(m.get("x0"), 0);
            int z0 = AssemblyCompilerUtils.i(m.get("z0"), 0);
            int x1 = AssemblyCompilerUtils.i(m.get("x1"), 0);
            int z1 = AssemblyCompilerUtils.i(m.get("z1"), 0);
            int margin = Math.max(0, AssemblyCompilerUtils.i(m.get("margin"), 2));
            int minX = Math.min(x0, x1) - margin;
            int maxX = Math.max(x0, x1) + margin;
            int minZ = Math.min(z0, z1) - margin;
            int maxZ = Math.max(z0, z1) + margin;
            out.add(new AssemblyConnectionRouter.Rect2(minX, minZ, maxX, maxZ));
        }
        return out;
    }

    static List<AssemblyConnectionRouter.Rect2> parseAvoidComponents(Map<String, Object> conn,
                                                    String fromComponentId,
                                                    String toComponentId,
                                                    Map<String, Map<String, Object>> byId,
                                                    Map<String, int[]> originById) {
        List<AssemblyConnectionRouter.Rect2> out = new ArrayList<>();
        if (conn == null) return out;

        int margin = Math.max(0, AssemblyCompilerUtils.i(conn.get("avoidMargin"), 3));
        String fromId = fromComponentId != null ? fromComponentId.trim() : "";
        String toId = toComponentId != null ? toComponentId.trim() : "";

        // Include explicit lists
        java.util.Set<String> include = new java.util.HashSet<>();
        Object v = conn.get("avoidComponents");
        if (v instanceof List<?> list) {
            for (Object it : list) {
                if (it == null) continue;
                String id = String.valueOf(it).trim();
                if (!id.isEmpty()) include.add(id);
            }
        }
        Object v2 = conn.get("avoidIncludeComponents");
        if (v2 instanceof List<?> list) {
            for (Object it : list) {
                if (it == null) continue;
                String id = String.valueOf(it).trim();
                if (!id.isEmpty()) include.add(id);
            }
        }

        // Exclude list
        java.util.Set<String> exclude = new java.util.HashSet<>();
        Object ex = conn.get("avoidExcludeComponents");
        if (ex instanceof List<?> list) {
            for (Object it : list) {
                if (it == null) continue;
                String id = String.valueOf(it).trim();
                if (!id.isEmpty()) exclude.add(id);
            }
        }
        if (!fromId.isEmpty()) exclude.add(fromId);
        if (!toId.isEmpty()) exclude.add(toId);

        boolean avoidAll = false;
        Object aa = conn.get("avoidAllComponents");
        if (aa instanceof Boolean b) avoidAll = b;
        else if (aa != null) {
            String s = String.valueOf(aa).trim().toLowerCase(Locale.ROOT);
            avoidAll = s.equals("true") || s.equals("1") || s.equals("yes") || s.equals("y") || s.equals("on");
        }

        // If avoidAllComponents, include every known component id (except excluded)
        if (avoidAll && byId != null) {
            for (String id : byId.keySet()) {
                if (id == null) continue;
                String cid = id.trim();
                if (cid.isEmpty()) continue;
                include.add(cid);
            }
        }

        // Build rects for included ids minus excluded
        for (String id : include) {
            if (id == null) continue;
            String cid = id.trim();
            if (cid.isEmpty() || exclude.contains(cid)) continue;
            Map<String, Object> comp = byId != null ? byId.get(cid) : null;
            int[] o = originById != null ? originById.get(cid) : null;
            if (comp == null || o == null) continue;
            AssemblyConnectionRouter.Rect2 r = inferComponentRect2(comp, o[0], o[2], margin);
            if (r != null) out.add(r);
        }

        return out;
    }

    static AssemblyConnectionRouter.Rect2 inferComponentRect2(Map<String, Object> comp, int ox, int oz, int margin) {
        if (comp == null) return null;
        // Optional explicit bbox override
        Object bbObj = comp.get("bbox");
        if (bbObj instanceof Map<?, ?> m) {
            int x0 = AssemblyCompilerUtils.i(m.get("x0"), 0);
            int z0 = AssemblyCompilerUtils.i(m.get("z0"), 0);
            int x1 = AssemblyCompilerUtils.i(m.get("x1"), 0);
            int z1 = AssemblyCompilerUtils.i(m.get("z1"), 0);
            int mgn = Math.max(margin, AssemblyCompilerUtils.i(m.get("margin"), 0));
            int minX = Math.min(x0, x1) + ox - mgn;
            int maxX = Math.max(x0, x1) + ox + mgn;
            int minZ = Math.min(z0, z1) + oz - mgn;
            int maxZ = Math.max(z0, z1) + oz + mgn;
            return new AssemblyConnectionRouter.Rect2(minX, minZ, maxX, maxZ);
        }

        String type = AssemblyCompilerUtils.str(comp.get("type"), "").trim().toUpperCase(Locale.ROOT);
        if (type.isBlank()) type = AssemblyCompilerUtils.str(comp.get("op"), "").trim().toUpperCase(Locale.ROOT);

        // Cylinder: radius-based
        if (type.contains("CYLINDER")) {
            int r = AssemblyComponentEmitter.componentRadius(comp);
            if (r <= 0) r = AssemblyCompilerUtils.i(comp.get("radius"), 6);
            int minX = ox - r - margin;
            int maxX = ox + r + margin;
            int minZ = oz - r - margin;
            int maxZ = oz + r + margin;
            return new AssemblyConnectionRouter.Rect2(minX, minZ, maxX, maxZ);
        }

        // Extruded polygon: points bbox if present
        if (type.contains("EXTRUDE")) {
            int[] bb = polygonBounds(comp);
            if (bb != null) {
                return new AssemblyConnectionRouter.Rect2(ox + bb[0] - margin, oz + bb[2] - margin, ox + bb[1] + margin, oz + bb[3] + margin);
            }
        }

        // Roof: include overhang if present
        if (type.contains("ROOF")) {
            int w = AssemblyComponentEmitter.componentWidth(comp);
            int d = AssemblyComponentEmitter.componentDepth(comp);
            int overhang = AssemblyCompilerUtils.i(comp.get("overhang"), 0);
            if (w > 0 && d > 0) {
                int hx = w / 2 + overhang;
                int hz = d / 2 + overhang;
                return new AssemblyConnectionRouter.Rect2(ox - hx - margin, oz - hz - margin, ox + hx + margin, oz + hz + margin);
            }
        }

        // Box-like: w/d centered
        int w = AssemblyComponentEmitter.componentWidth(comp);
        int d = AssemblyComponentEmitter.componentDepth(comp);
        if (w > 0 && d > 0) {
            int hx = w / 2;
            int hz = d / 2;
            return new AssemblyConnectionRouter.Rect2(ox - hx - margin, oz - hz - margin, ox + hx + margin, oz + hz + margin);
        }
        return null;
    }

    /**
     * Returns a list of points to append AFTER start (a), ending with b.
     * If no detour needed, returns [b].
     */
    static int[] resolveWaypoint(Object v,
                                         Map<String, Map<String, Object>> byId,
                                         Map<String, int[]> originById,
                                         Map<String, Map<String, int[]>> portsById) {
        if (v == null) return null;
        // direct endpoint reference
        Endpoint e = parseEndpoint(v);
        if (e != null) return resolveEndpoint(e, byId, originById, portsById);

        // raw coordinate map: {x,y,z}
        if (v instanceof Map<?, ?> m) {
            // if it has no component/id field, treat as local absolute point
            Object cid = m.get("component");
            if (cid == null) cid = m.get("id");
            if (cid == null) {
                int x = AssemblyCompilerUtils.i(m.get("x"), 0);
                int y = AssemblyCompilerUtils.i(m.get("y"), 0);
                int z = AssemblyCompilerUtils.i(m.get("z"), 0);
                return new int[]{x, y, z};
            }
        }
        return null;
    }

    static int[] resolveEndpoint(Endpoint e,
                                         Map<String, Map<String, Object>> byId,
                                         Map<String, int[]> originById,
                                         Map<String, Map<String, int[]>> portsById) {
        if (e == null) return null;
        int[] base = originById.get(e.componentId);
        Map<String, Object> comp = byId.get(e.componentId);
        if (base == null || comp == null) return null;

        int ox = base[0], oy = base[1], oz = base[2];
        int ax = 0, ay = 0, az = 0;

        // Port offset wins (Topology-friendly)
        if (e.port != null && portsById != null) {
            Map<String, int[]> ports = portsById.get(e.componentId);
            if (ports != null) {
                String key = AssemblyCompilerUtils.normalizePortKey(e.port);
                int[] p = ports.get(key);
                if (p != null) {
                    ax += p[0];
                    ay += p[1];
                    az += p[2];
                }
            }
        }

        // anchor offsets (relative to component origin)
        String a = (e.anchor == null ? "CENTER" : e.anchor);
        if (a.equals("TOP_CENTER")) {
            ay = AssemblyComponentEmitter.componentHeight(comp);
        }  // no-op


        return new int[]{ox + ax + e.dx, oy + ay + e.dy, oz + az + e.dz};
    }

}
