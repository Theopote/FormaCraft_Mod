package com.formacraft.server.assembly;

import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Shared Bezier/spline helpers for Assembly op parameters.
 * <p>
 * Kept in a dedicated class so {@link MetaAssemblyEngine} can focus on op dispatch.
 */
public final class AssemblyBezierOps {
    private AssemblyBezierOps() {}

    public static List<Vec3d> parseVecPoints(Object v) {
        ArrayList<Vec3d> out = new ArrayList<>();
        if (!(v instanceof List<?> list)) return out;
        for (Object p : list) {
            if (!(p instanceof Map<?, ?> pm)) continue;
            double x = AssemblyValueParser.d(pm.get("x"), 0.0);
            double y = AssemblyValueParser.d(pm.get("y"), 0.0);
            double z = AssemblyValueParser.d(pm.get("z"), 0.0);
            out.add(new Vec3d(x, y, z));
        }
        return out;
    }

    /**
     * Catmull-Rom -> cubic Bezier conversion and sampling.
     * Server-side copy of PathTool logic.
     */
    public static List<Vec3d> sampleBezierSpline(List<Vec3d> waypoints, int samplesPerBlock) {
        if (waypoints == null || waypoints.size() < 2) return Collections.emptyList();
        int n = waypoints.size();
        ArrayList<Vec3d> out = new ArrayList<>();
        for (int i = 0; i < n - 1; i++) {
            Vec3d p0 = (i == 0) ? waypoints.getFirst() : waypoints.get(i - 1);
            Vec3d p1 = waypoints.get(i);
            Vec3d p2 = waypoints.get(i + 1);
            Vec3d p3 = (i + 2 < n) ? waypoints.get(i + 2) : waypoints.get(n - 1);
            if (p0 == null || p1 == null || p2 == null || p3 == null) continue;

            Vec3d c1 = p1.add(p2.subtract(p0).multiply(1.0 / 6.0));
            Vec3d c2 = p2.subtract(p3.subtract(p1).multiply(1.0 / 6.0));

            double segLen = p1.distanceTo(p2);
            int steps = (int) Math.max(6, Math.ceil(segLen * Math.max(2.0, samplesPerBlock)));
            int start = (i == 0) ? 0 : 1; // avoid duplicates
            for (int s = start; s <= steps; s++) {
                double t = s / (double) steps;
                out.add(bezier(p1, c1, c2, p2, t));
            }
        }
        return out;
    }

    public static Vec3d bezier(Vec3d p0, Vec3d c1, Vec3d c2, Vec3d p3, double t) {
        double u = 1.0 - t;
        double tt = t * t;
        double uu = u * u;
        double uuu = uu * u;
        double ttt = tt * t;
        double x = uuu * p0.x + 3.0 * uu * t * c1.x + 3.0 * u * tt * c2.x + ttt * p3.x;
        double y = uuu * p0.y + 3.0 * uu * t * c1.y + 3.0 * u * tt * c2.y + ttt * p3.y;
        double z = uuu * p0.z + 3.0 * uu * t * c1.z + 3.0 * u * tt * c2.z + ttt * p3.z;
        return new Vec3d(x, y, z);
    }

    public static double lerp(double a, double b, double t) {
        return a + (b - a) * t;
    }
}

