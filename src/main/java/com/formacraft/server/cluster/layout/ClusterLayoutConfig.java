package com.formacraft.server.cluster.layout;

import com.formacraft.common.generation.structure.util.StructureSpecParsers;

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
    public final double compactnessWeight; // 0..1 (higher => tighter around main)
    public final double compactnessMaxDist; // <=0 => auto
    public final String axisMode; // "none" / "x" / "z"
    public final double axisWeight; // 0..1
    public final double semanticPublicTargetDistN; // 0..1 (distance band target from CORE/main)
    public final double semanticPublicBandN;       // 0..1 (band half-width)
    public final double semanticPrivateMinDistN;   // 0..1 (min distance from CORE/main)
    public final double semanticServiceMinDistN;   // 0..1
    public final double semanticWeightPublic;      // 0..1
    public final double semanticWeightPrivate;     // 0..1
    public final double semanticWeightService;     // 0..1
    public final double semanticBufferMinDistN;    // 0..1 (min normalized distance between buffered roles)
    public final double semanticBufferWeight;      // 0..1 (penalty weight when buffer is violated)
    public final double semanticServicePrivateMinDistN; // 0..1 (min normalized distance between SERVICE and PRIVATE)
    public final double semanticServicePrivateWeight;   // 0..1
    public final double semanticTransitionMaxDistN;     // 0..1 (TRANSITION should not be too far from CORE)
    public final double semanticTransitionToPublicMaxDistN; // 0..1 (TRANSITION should stay close to PUBLIC)
    public final double semanticWeightTransition;       // 0..1
    public final double semanticWeightTransitionToPublic; // 0..1

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
                               double centerBiasImportanceBoost,
                               double compactnessWeight,
                               double compactnessMaxDist,
                               String axisMode,
                               double axisWeight,
                               double semanticPublicTargetDistN,
                               double semanticPublicBandN,
                               double semanticPrivateMinDistN,
                               double semanticServiceMinDistN,
                               double semanticWeightPublic,
                               double semanticWeightPrivate,
                               double semanticWeightService,
                               double semanticBufferMinDistN,
                               double semanticBufferWeight,
                               double semanticServicePrivateMinDistN,
                               double semanticServicePrivateWeight,
                               double semanticTransitionMaxDistN,
                               double semanticTransitionToPublicMaxDistN,
                               double semanticWeightTransition,
                               double semanticWeightTransitionToPublic) {
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
        this.compactnessWeight = clamp01(compactnessWeight);
        this.compactnessMaxDist = compactnessMaxDist;
        this.axisMode = normalizeAxisMode(axisMode);
        this.axisWeight = clamp01(axisWeight);
        this.semanticPublicTargetDistN = clamp01(semanticPublicTargetDistN);
        this.semanticPublicBandN = clamp01(semanticPublicBandN);
        this.semanticPrivateMinDistN = clamp01(semanticPrivateMinDistN);
        this.semanticServiceMinDistN = clamp01(semanticServiceMinDistN);
        this.semanticWeightPublic = clamp01(semanticWeightPublic);
        this.semanticWeightPrivate = clamp01(semanticWeightPrivate);
        this.semanticWeightService = clamp01(semanticWeightService);
        this.semanticBufferMinDistN = clamp01(semanticBufferMinDistN);
        this.semanticBufferWeight = clamp01(semanticBufferWeight);
        this.semanticServicePrivateMinDistN = clamp01(semanticServicePrivateMinDistN);
        this.semanticServicePrivateWeight = clamp01(semanticServicePrivateWeight);
        this.semanticTransitionMaxDistN = clamp01(semanticTransitionMaxDistN);
        this.semanticTransitionToPublicMaxDistN = clamp01(semanticTransitionToPublicMaxDistN);
        this.semanticWeightTransition = clamp01(semanticWeightTransition);
        this.semanticWeightTransitionToPublic = clamp01(semanticWeightTransitionToPublic);
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

        Map<String, Object> sw = getMap(extra);
        if (sw != null) {
            wCost = getDouble(sw, "cost", wCost);
            wSlope = getDouble(sw, "slope", wSlope);
            wCenter = getDouble(sw, "center", wCenter);
            wImp = getDouble(sw, "importance", wImp);
        }

        // boost center bias for higher-importance units (main buildings)
        double boost = getDouble(extra, "centerBiasImportanceBoost", 0.50);

        // global compactness: make the cluster tighter around the main building
        double compactW = getDouble(extra, "compactnessWeight", 0.20);
        double compactMaxDist = getDouble(extra, "compactnessMaxDist", 0.0); // auto

        // axis alignment (optional): encourage placements along a main axis through the main building
        String axisMode = getString(extra, "axisMode", "none"); // none/x/z
        double axisWeight = getDouble(extra, "axisWeight", 0.0);

        // semantic spacing (optional): "space role" constraints around CORE/main
        double pubTargetN = getDouble(extra, "semanticPublicTargetDistN", 0.30);
        double pubBandN = getDouble(extra, "semanticPublicBandN", 0.18);
        double privMinN = getDouble(extra, "semanticPrivateMinDistN", 0.45);
        double servMinN = getDouble(extra, "semanticServiceMinDistN", 0.40);
        double wPub = getDouble(extra, "semanticWeightPublic", 0.20);
        double wPriv = getDouble(extra, "semanticWeightPrivate", 0.25);
        double wServ = getDouble(extra, "semanticWeightService", 0.20);
        double bufferMinN = getDouble(extra, "semanticBufferMinDistN", 0.18);
        double bufferW = getDouble(extra, "semanticBufferWeight", 0.35);
        double spMinN = getDouble(extra, "semanticServicePrivateMinDistN", 0.25);
        double spW = getDouble(extra, "semanticServicePrivateWeight", 0.45);
        double trMaxN = getDouble(extra, "semanticTransitionMaxDistN", 0.30);
        double trToPubMaxN = getDouble(extra, "semanticTransitionToPublicMaxDistN", 0.22);
        double wTr = getDouble(extra, "semanticWeightTransition", 0.20);
        double wTrPub = getDouble(extra, "semanticWeightTransitionToPublic", 0.25);

        // if all weights become zero (bad config), fall back to defaults
        if (Math.max(0.0, wCost) + Math.max(0.0, wSlope) + Math.max(0.0, wCenter) + Math.max(0.0, wImp) <= 1e-9) {
            wCost = defCost;
            wSlope = defSlope;
            wCenter = defCenter;
            wImp = defImp;
        }

        return new ClusterLayoutConfig(halfX, halfZ, samples, maxRange, maxCost, minGap, back,
                wCost, wSlope, wCenter, wImp,
                boost,
                compactW,
                compactMaxDist,
                axisMode,
                axisWeight,
                pubTargetN,
                pubBandN,
                privMinN,
                servMinN,
                wPub,
                wPriv,
                wServ,
                bufferMinN,
                bufferW,
                spMinN,
                spW,
                trMaxN,
                trToPubMaxN,
                wTr,
                wTrPub);
    }

    private static int getInt(Map<String, Object> extra, String key, int def) {
        if (extra == null) return def;
        return StructureSpecParsers.intValue(extra.get(key), def);
    }

    private static double getDouble(Map<String, Object> extra, String key, double def) {
        if (extra == null) return def;
        return StructureSpecParsers.doubleValue(extra.get(key), def);
    }

    private static String getString(Map<String, Object> extra, String key, String def) {
        if (extra == null) return def;
        Object v = extra.get(key);
        if (v == null) return def;
        String s = String.valueOf(v).trim();
        return s.isEmpty() ? def : s;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> getMap(Map<String, Object> extra) {
        if (extra == null) return null;
        Object v = extra.get("scoreWeights");
        if (v instanceof Map<?, ?> m) {
            return (Map<String, Object>) m;
        }
        return null;
    }

    private static double clamp01(double v) {
        if (v < 0.0) return 0.0;
        return Math.min(v, 1.0);
    }

    private static String normalizeAxisMode(String v) {
        if (v == null) return "none";
        String s = v.trim().toLowerCase(Locale.ROOT);
        if (s.equals("x") || s.equals("z")) return s;
        return "none";
    }

    public static boolean isClusterMode(Object v) {
        if (v == null) return false;
        String s = String.valueOf(v).trim().toLowerCase(Locale.ROOT);
        return s.equals("cluster") || s.equals("terrain_cluster") || s.equals("adaptive_cluster");
    }
}


