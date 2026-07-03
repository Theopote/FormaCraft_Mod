package com.formacraft.server.build;

import java.util.function.Supplier;

/**
 * ThreadLocal report context for "generation-time reporting".
 * Similar to BuildConstraintContext, but for transparency (not enforcement).
 */
public final class BuildReportContext {
    private BuildReportContext() {}

    private static final ThreadLocal<BuildReport> TL = new ThreadLocal<>();

    public record Reported<T>(T value, BuildReport report) {}

    public static BuildReport current() {
        return TL.get();
    }

    public static void addTerrainSnapDy(int dy) {
        BuildReport r = TL.get();
        if (r != null) r.addSnap(dy);
    }

    public static void addTerrainPad(int fill, int clear) {
        BuildReport r = TL.get();
        if (r != null) r.addPad(fill, clear);
    }

    public static void setTerrainBudgetBlocks(int v) {
        BuildReport r = TL.get();
        if (r != null) r.setTerrainBudgetBlocks(Math.max(0, v));
    }

    public static void addTerrainBudgetDegrade() {
        BuildReport r = TL.get();
        if (r != null) r.addTerrainBudgetDegrade();
    }

    public static void addFootingPadUnit() {
        BuildReport r = TL.get();
        if (r != null) r.addFootingPadUnit();
    }

    public static void addFootingStiltUnit() {
        BuildReport r = TL.get();
        if (r != null) r.addFootingStiltUnit();
    }

    public static void addFoundationType(com.formacraft.server.foundation.FoundationType t) {
        BuildReport r = TL.get();
        if (r != null && t != null) r.addFoundationDecision(t.name(), -1, -1, -1);
    }

    public static void addFoundationDecision(com.formacraft.server.foundation.FoundationType t, int range, int padDepth, int clearHeight) {
        BuildReport r = TL.get();
        if (r != null && t != null) r.addFoundationDecision(t.name(), range, padDepth, clearHeight);
    }

    public static void addFoundationExecution(com.formacraft.server.foundation.FoundationType t,
                                              int range,
                                              int plannedPadDepth,
                                              int plannedClearHeight,
                                              int usedPadDepth,
                                              int usedClearHeight,
                                              int degradeSteps) {
        BuildReport r = TL.get();
        if (r != null && t != null) {
            r.addFoundationExecution(t.name(), range, plannedPadDepth, plannedClearHeight, usedPadDepth, usedClearHeight, degradeSteps);
        }
    }

    public static <T> T withNewReport(Supplier<T> fn) {
        BuildReport prev = TL.get();
        try {
            TL.set(new BuildReport());
            return fn.get();
        } finally {
            if (prev == null) TL.remove();
            else TL.set(prev);
        }
    }

    public static <T> Reported<T> withNewReportReported(Supplier<T> fn) {
        BuildReport prev = TL.get();
        try {
            BuildReport r = new BuildReport();
            TL.set(r);
            T v = fn.get();
            return new Reported<>(v, r);
        } finally {
            if (prev == null) TL.remove();
            else TL.set(prev);
        }
    }

    public static BuildReport withNewReport(Runnable fn) {
        BuildReport prev = TL.get();
        try {
            BuildReport r = new BuildReport();
            TL.set(r);
            fn.run();
            return r;
        } finally {
            if (prev == null) TL.remove();
            else TL.set(prev);
        }
    }
}


