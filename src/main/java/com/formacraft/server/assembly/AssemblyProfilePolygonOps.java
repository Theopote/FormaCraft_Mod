package com.formacraft.server.assembly;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 2D polygon / ring helpers used by MetaAssemblyEngine for profile=POLYGON.
 */
public final class AssemblyProfilePolygonOps {
    private AssemblyProfilePolygonOps() {}

    public static List<List<int[]>> parseProfileRings(Map<String, Object> op) {
        ArrayList<List<int[]>> out = new ArrayList<>();
        if (op == null) return out;

        Object ringsObj = op.get("profileRings");
        if (ringsObj == null) ringsObj = op.get("rings");

        if (ringsObj instanceof List<?> rings) {
            for (Object r : rings) {
                if (!(r instanceof List<?> ringPts)) continue;
                ArrayList<int[]> ring = new ArrayList<>();
                for (Object p : ringPts) {
                    if (!(p instanceof Map<?, ?> pm)) continue;
                    int u = AssemblyValueParser.i(pm.get("u"), AssemblyValueParser.i(pm.get("x"), 0));
                    int v = AssemblyValueParser.i(pm.get("v"), AssemblyValueParser.i(pm.get("y"), 0));
                    ring.add(new int[]{u, v});
                }
                if (ring.size() >= 3) out.add(ring);
            }
        }

        if (!out.isEmpty()) return out;

        // fallback: single ring from profilePoints
        List<int[]> single = parseProfile2D(op.get("profilePoints"));
        if (single.size() >= 3) out.add(single);
        return out;
    }

    public static List<int[]> parseProfile2D(Object v) {
        ArrayList<int[]> out = new ArrayList<>();
        if (!(v instanceof List<?> list)) return out;
        for (Object p : list) {
            if (!(p instanceof Map<?, ?> pm)) continue;
            int u = AssemblyValueParser.i(pm.get("u"), AssemblyValueParser.i(pm.get("x"), 0));
            int vv = AssemblyValueParser.i(pm.get("v"), AssemblyValueParser.i(pm.get("y"), 0));
            out.add(new int[]{u, vv});
        }
        return out;
    }

    public static int[] bounds2D(List<int[]> pts) {
        int uMin = Integer.MAX_VALUE, uMax = Integer.MIN_VALUE;
        int vMin = Integer.MAX_VALUE, vMax = Integer.MIN_VALUE;
        for (int[] p : pts) {
            if (p == null || p.length < 2) continue;
            uMin = Math.min(uMin, p[0]);
            uMax = Math.max(uMax, p[0]);
            vMin = Math.min(vMin, p[1]);
            vMax = Math.max(vMax, p[1]);
        }
        if (uMin == Integer.MAX_VALUE) return new int[]{0, 0, 0, 0};
        return new int[]{uMin, uMax, vMin, vMax};
    }

    public static int[] boundsRings2D(List<List<int[]>> rings) {
        int uMin = Integer.MAX_VALUE, uMax = Integer.MIN_VALUE;
        int vMin = Integer.MAX_VALUE, vMax = Integer.MIN_VALUE;
        for (List<int[]> ring : rings) {
            if (ring == null) continue;
            int[] bb = bounds2D(ring);
            uMin = Math.min(uMin, bb[0]);
            uMax = Math.max(uMax, bb[1]);
            vMin = Math.min(vMin, bb[2]);
            vMax = Math.max(vMax, bb[3]);
        }
        if (uMin == Integer.MAX_VALUE) return new int[]{0, 0, 0, 0};
        return new int[]{uMin, uMax, vMin, vMax};
    }

    public static List<int[]> scalePoly(List<int[]> pts, double s) {
        ArrayList<int[]> out = new ArrayList<>();
        for (int[] p : pts) {
            out.add(new int[]{(int) Math.round(p[0] * s), (int) Math.round(p[1] * s)});
        }
        return out;
    }

    public static List<List<int[]>> scaleRings(List<List<int[]>> rings, double s) {
        ArrayList<List<int[]>> out = new ArrayList<>();
        for (List<int[]> ring : rings) out.add(scalePoly(ring, s));
        return out;
    }

    public static boolean pointInPoly2D(int u, int v, List<int[]> poly) {
        // even-odd rule
        boolean inside = false;
        for (int i = 0, j = poly.size() - 1; i < poly.size(); j = i++) {
            int[] pi = poly.get(i);
            int[] pj = poly.get(j);
            int xi = pi[0], yi = pi[1];
            int xj = pj[0], yj = pj[1];
            boolean intersect = ((yi > v) != (yj > v))
                    && (u < (double) (xj - xi) * (v - yi) / (yj - yi + 0.000001) + xi);
            if (intersect) inside = !inside;
        }
        return inside;
    }

    public static boolean pointInRings2D(int u, int v, List<List<int[]>> rings) {
        if (rings == null || rings.isEmpty()) return false;
        // inside outer AND not inside any hole rings
        if (!pointInPoly2D(u, v, rings.getFirst())) return false;
        for (int i = 1; i < rings.size(); i++) {
            if (pointInPoly2D(u, v, rings.get(i))) return false;
        }
        return true;
    }

    public static boolean isRingsBorder(int u, int v, List<List<int[]>> rings, int t) {
        // Border test against composite inside/outside
        for (int k = 1; k <= t; k++) {
            if (!pointInRings2D(u + k, v, rings)) return true;
            if (!pointInRings2D(u - k, v, rings)) return true;
            if (!pointInRings2D(u, v + k, rings)) return true;
            if (!pointInRings2D(u, v - k, rings)) return true;
        }
        return false;
    }
}

