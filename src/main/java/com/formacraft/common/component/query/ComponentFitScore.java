package com.formacraft.common.component.query;

import com.formacraft.common.component.ComponentDefinition;

/** Evidence for an already permitted fit; never authorizes scaling or placement. */
public record ComponentFitScore(double targetError, double dimensionalChange, double total,
                                boolean suiteMatched, double suiteBonus) {
    public ComponentFitScore(double targetError, double dimensionalChange, double total) {
        this(targetError, dimensionalChange, total, false, 0);
    }
    static ComponentFitScore evaluate(ComponentScore ranked, ComponentDefinition base,
                                      ComponentDefinition fitted, ComponentQuery query) {
        var g = query.geometry;
        double error = g == null ? 0 : Math.max(relative(fitted.size.w, g.openingWidth),
                Math.max(relative(fitted.size.h, g.openingHeight), relative(fitted.size.d, g.targetDepth)));
        double change = Math.max(relative(fitted.size.w, base.size.w),
                Math.max(relative(fitted.size.h, base.size.h), relative(fitted.size.d, base.size.d)));
        double penalty = query.selection == null ? 0.10 : query.selection.adaptationPenalty;
        if (!Double.isFinite(penalty)) penalty = 0.10;
        penalty = Math.clamp(penalty, 0, 1);
        // Replace the preliminary geometry estimate when its breakdown is available.
        double total = ranked.totalScore;
        if (ranked.breakdown != null) total = ranked.breakdown.withGeometry(1 - error);
        else total -= 0.20 * error;
        boolean suiteMatched = query.selection != null && query.selection.preferredSuite != null
                && !query.selection.preferredSuite.isBlank()
                && ComponentQueryMatchUtil.tagIdentityMatches("suite:" + query.selection.preferredSuite.strip(), base.tags);
        double suiteBonus = suiteMatched ? query.selection.suiteBonus : 0;
        if (!Double.isFinite(suiteBonus)) suiteBonus = 0.05;
        suiteBonus = Math.clamp(suiteBonus, 0, 1);
        return new ComponentFitScore(error, change, total - penalty * change + suiteBonus, suiteMatched, suiteBonus);
    }

    private static double relative(int actual, Integer reference) {
        if (reference == null) return 0;
        return Math.min(1, Math.abs((long) actual - reference) / (double) Math.max(1, reference));
    }
}
