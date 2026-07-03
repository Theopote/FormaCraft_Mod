package com.formacraft.server.build;

/**
 * BuildReport (v1):
 * A lightweight, generation-time report for user-facing transparency.
 *
 * Current tracked signals:
 * - Terrain snap: how many units were snapped and total delta Y
 * - Terrain adaptive pad: how many blocks were filled / cleared
 */
public final class BuildReport {
    int terrainSnapCount = 0;
    int terrainSnapDySum = 0;
    int terrainPadFill = 0;
    int terrainPadClear = 0;
    int footingPadUnits = 0;
    int footingStiltUnits = 0;
    int foundationFlatPadUnits = 0;
    int foundationSteppedUnits = 0;
    int foundationStiltUnits = 0;
    int foundationEmbeddedUnits = 0;
    int foundationDecisionCount = 0;
    long foundationRangeSum = 0;
    int foundationRangeMax = 0;
    long foundationPadDepthSum = 0;
    long foundationClearHeightSum = 0;
    long foundationPadDepthUsedSum = 0;
    long foundationClearHeightUsedSum = 0;
    int foundationDecisionUsedCount = 0;
    int foundationDegradeStepsSum = 0;
    int terrainBudgetBlocks = -1;
    int terrainBudgetDegradeCount = 0;

    void addSnap(int dy) {
        terrainSnapCount++;
        terrainSnapDySum += dy;
    }

    void addPad(int fill, int clear) {
        terrainPadFill += Math.max(0, fill);
        terrainPadClear += Math.max(0, clear);
    }

    void setTerrainBudgetBlocks(int v) {
        terrainBudgetBlocks = v;
    }

    void addTerrainBudgetDegrade() {
        terrainBudgetDegradeCount++;
    }

    void addFootingPadUnit() {
        footingPadUnits++;
    }

    void addFootingStiltUnit() {
        footingStiltUnits++;
    }

    void addFoundationDecision(String t, int range, int padDepth, int clearHeight) {
        if (t == null) return;
        foundationDecisionCount++;
        switch (t) {
            case "FLAT_PAD" -> foundationFlatPadUnits++;
            case "STEPPED" -> foundationSteppedUnits++;
            case "STILT" -> foundationStiltUnits++;
            case "EMBEDDED" -> foundationEmbeddedUnits++;
            default -> {}
        }
        if (range >= 0) {
            foundationRangeSum += range;
            foundationRangeMax = Math.max(foundationRangeMax, range);
        }
        if (padDepth >= 0) foundationPadDepthSum += padDepth;
        if (clearHeight >= 0) foundationClearHeightSum += clearHeight;
    }

    void addFoundationExecution(String t,
                               int range,
                               int plannedPadDepth,
                               int plannedClearHeight,
                               int usedPadDepth,
                               int usedClearHeight,
                               int degradeSteps) {
        addFoundationDecision(t, range, plannedPadDepth, plannedClearHeight);
        foundationDecisionUsedCount++;
        if (usedPadDepth >= 0) foundationPadDepthUsedSum += usedPadDepth;
        if (usedClearHeight >= 0) foundationClearHeightUsedSum += usedClearHeight;
        foundationDegradeStepsSum += Math.max(0, degradeSteps);
    }

    public String summaryZh() {
        if (terrainSnapCount == 0 && terrainPadFill == 0 && terrainPadClear == 0
                && footingPadUnits == 0 && footingStiltUnits == 0
                && foundationFlatPadUnits == 0 && foundationSteppedUnits == 0
                && foundationStiltUnits == 0 && foundationEmbeddedUnits == 0) return "";
        String dy = (terrainSnapCount > 0) ? String.format("｜吸附%d次(ΔY合计=%+d)", terrainSnapCount, terrainSnapDySum) : "";
        String footing = "";
        if (footingPadUnits > 0 || footingStiltUnits > 0) {
            footing = String.format("｜footing:台基%d/架空%d", footingPadUnits, footingStiltUnits);
        }
        String f2 = "";
        if (foundationFlatPadUnits + foundationSteppedUnits + foundationStiltUnits + foundationEmbeddedUnits > 0) {
            f2 = String.format("｜foundation:%d平垫/%d阶梯/%d架空/%d嵌入",
                    foundationFlatPadUnits, foundationSteppedUnits, foundationStiltUnits, foundationEmbeddedUnits);
        }
        String f3 = "";
        if (foundationDecisionCount > 0) {
            double avgRange = (double) foundationRangeSum / (double) foundationDecisionCount;
            double avgPad = (double) foundationPadDepthSum / (double) foundationDecisionCount;
            double avgClear = (double) foundationClearHeightSum / (double) foundationDecisionCount;
            double avgPadUsed = foundationDecisionUsedCount > 0 ? ((double) foundationPadDepthUsedSum / (double) foundationDecisionUsedCount) : avgPad;
            double avgClearUsed = foundationDecisionUsedCount > 0 ? ((double) foundationClearHeightUsedSum / (double) foundationDecisionUsedCount) : avgClear;
            String degrade = foundationDegradeStepsSum > 0 ? String.format("｜f降级步=%d", foundationDegradeStepsSum) : "";
            f3 = String.format("｜Δh均=%.1f/max=%d｜pad≈%.1f/%.1f｜clear≈%.1f/%.1f%s",
                    avgRange, foundationRangeMax,
                    avgPad, avgPadUsed,
                    avgClear, avgClearUsed,
                    degrade);
        }
        String budget = "";
        if (terrainBudgetBlocks >= 0) {
            int used = Math.max(0, terrainPadFill + terrainPadClear);
            String degrade = terrainBudgetDegradeCount > 0 ? String.format("｜降级%d次", terrainBudgetDegradeCount) : "";
            budget = String.format("｜预算%d/已用%d%s", terrainBudgetBlocks, used, degrade);
        }
        return String.format("地形适配：垫基+%d｜清障碍+%d%s%s%s%s%s", terrainPadFill, terrainPadClear, dy, footing, f2, f3, budget);
    }
}


