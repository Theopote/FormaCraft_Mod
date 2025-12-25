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
    public final double wCost;
    public final double wSlope;
    public final double wCenter;
    public final double wImportance;
    public final double centerBiasImportanceBoost; // 0..1

    public ClusterLayoutConfig(int halfX,
                               int halfZ,
                               int samples,
                               int maxRange,
                               int maxFlattenCost,
                               int minGap,
                               int maxBacktrack,
                               double wCost,
                               double wSlope,
                               double wCenter,
                               double wImportance,
                               double centerBiasImportanceBoost) {
        this.halfX = Math.max(8, halfX);
        this.halfZ = Math.max(8, halfZ);
        this.samples = Math.max(50, samples);
        this.maxRange = Math.max(4, maxRange);
        this.maxFlattenCost = Math.max(0, maxFlattenCost);
        this.minGap = Math.max(0, minGap);
        this.maxBacktrack = Math.max(0, maxBacktrack);
        this.wCost = Math.max(0.0, wCost);
        this.wSlope = Math.max(0.0, wSlope);
        this.wCenter = Math.max(0.0, wCenter);
        this.wImportance = Math.max(0.0, wImportance);
        this.centerBiasImportanceBoost = clamp01(centerBiasImportanceBoost);
    }

    public static ClusterLayoutConfig fromExtra(Map<String, Object> extra, int defaultHalfX, int defaultHalfZ, int count, int spacing) {
        int halfX = getInt(extra, "clusterHalfX", defaultHalfX);
        int halfZ = getInt(extra, "clusterHalfZ", defaultHalfZ);
        int samples = getInt(extra, "candidateBudget", Math.max(200, count * 120));
        int maxRange = getInt(extra, "maxFootprintRange", 14);
        int maxCost = getInt(extra, "maxFlattenCost", 0);
        int minGap = getInt(extra, "minGap", Math.max(2, (int) Math.round(spacing * 0.15)));
        int back = getInt(extra, "maxBacktrack", 2);

        // score weights (data-driven)
        double defCost = 0.40, defSlope = 0.25, defCenter = 0.25, defImp = 0.10;
        double wCost = getDouble(extra, "scoreWCost", defCost);
        double wSlope = getDouble(extra, "scoreWSlope", defSlope);
        double wCenter = getDouble(extra, "scoreWCenter", defCenter);
        double wImp = getDouble(extra, "scoreWImportance", defImp);

        Map<String, Object> sw = getMap(extra, "scoreWeights");
        if (sw != null) {
            wCost = getDouble(sw, "cost", wCost);
            wSlope = getDouble(sw, "slope", wSlope);
            wCenter = getDouble(sw, "center", wCenter);
            wImp = getDouble(sw, "importance", wImp);
        }

        // boost center bias for higher-importance units (main buildings)
        double boost = getDouble(extra, "centerBiasImportanceBoost", 0.50);

        // if all weights become zero (bad config), fall back to defaults
        if (Math.max(0.0, wCost) + Math.max(0.0, wSlope) + Math.max(0.0, wCenter) + Math.max(0.0, wImp) <= 1e-9) {
            wCost = defCost;
            wSlope = defSlope;
            wCenter = defCenter;
            wImp = defImp;
        }

        return new ClusterLayoutConfig(halfX, halfZ, samples, maxRange, maxCost, minGap, back, wCost, wSlope, wCenter, wImp, boost);
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

    private static double getDouble(Map<String, Object> extra, String key, double def) {
        if (extra == null) return def;
        Object v = extra.get(key);
        if (v == null) return def;
        try {
            if (v instanceof Number n) return n.doubleValue();
            String s = String.valueOf(v).trim();
            if (s.isEmpty()) return def;
            return Double.parseDouble(s);
        } catch (Exception ignored) {
            return def;
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> getMap(Map<String, Object> extra, String key) {
        if (extra == null) return null;
        Object v = extra.get(key);
        if (v instanceof Map<?, ?> m) {
            try {
                return (Map<String, Object>) m;
            } catch (Exception ignored) {
                return null;
            }
        }
        return null;
    }

    private static double clamp01(double v) {
        if (v < 0.0) return 0.0;
        if (v > 1.0) return 1.0;
        return v;
    }

    public static boolean isClusterMode(Object v) {
        if (v == null) return false;
        String s = String.valueOf(v).trim().toLowerCase(Locale.ROOT);
        return s.equals("cluster") || s.equals("terrain_cluster") || s.equals("adaptive_cluster");
    }
}


