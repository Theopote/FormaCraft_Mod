package com.formacraft.server.cluster.layout;

import java.util.Locale;
import java.util.Map;

/**
 * ClusterLayoutConfig: shared tuning knobs for CandidateGenerator + PlacementSolver.
 * Parsed from spec.extra (data-driven).
 */
public final class ClusterLayoutConfig {
    public final int halfX;
    public final int halfZ;
    public final int samples;
    public final int maxRange;
    public final int maxFlattenCost;
    public final int minGap;
    public final int maxBacktrack;

    public ClusterLayoutConfig(int halfX, int halfZ, int samples, int maxRange, int maxFlattenCost, int minGap, int maxBacktrack) {
        this.halfX = Math.max(8, halfX);
        this.halfZ = Math.max(8, halfZ);
        this.samples = Math.max(50, samples);
        this.maxRange = Math.max(4, maxRange);
        this.maxFlattenCost = Math.max(0, maxFlattenCost);
        this.minGap = Math.max(0, minGap);
        this.maxBacktrack = Math.max(0, maxBacktrack);
    }

    public static ClusterLayoutConfig fromExtra(Map<String, Object> extra, int defaultHalfX, int defaultHalfZ, int count, int spacing) {
        int halfX = getInt(extra, "clusterHalfX", defaultHalfX);
        int halfZ = getInt(extra, "clusterHalfZ", defaultHalfZ);
        int samples = getInt(extra, "candidateBudget", Math.max(200, count * 120));
        int maxRange = getInt(extra, "maxFootprintRange", 14);
        int maxCost = getInt(extra, "maxFlattenCost", 0);
        int minGap = getInt(extra, "minGap", Math.max(2, (int) Math.round(spacing * 0.15)));
        int back = getInt(extra, "maxBacktrack", 2);
        return new ClusterLayoutConfig(halfX, halfZ, samples, maxRange, maxCost, minGap, back);
    }

    private static int getInt(Map<String, Object> extra, String key, int def) {
        if (extra == null) return def;
        Object v = extra.get(key);
        if (v == null) return def;
        try {
            if (v instanceof Number n) return n.intValue();
            String s = String.valueOf(v).trim();
            if (s.isEmpty()) return def;
            return Integer.parseInt(s);
        } catch (Exception ignored) {
            return def;
        }
    }

    public static boolean isClusterMode(Object v) {
        if (v == null) return false;
        String s = String.valueOf(v).trim().toLowerCase(Locale.ROOT);
        return s.equals("cluster") || s.equals("terrain_cluster") || s.equals("adaptive_cluster");
    }
}


