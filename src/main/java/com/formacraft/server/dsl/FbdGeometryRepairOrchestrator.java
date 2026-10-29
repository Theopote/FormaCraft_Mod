package com.formacraft.server.dsl;

import com.fasterxml.jackson.databind.node.IntNode;
import com.formacraft.FormacraftMod;
import com.formacraft.common.llm.dto.LlmPlan;
import com.formacraft.server.build.quality.BuildQualityReport;

import java.util.Optional;
import java.util.function.Function;

/**
 * One-shot pre-preview geometry repair: try ROOF_COVER.y −1 then +1 under
 * {@link FbdGeometryRepairGuard} permits. Does not call providers or apply blocks.
 */
public final class FbdGeometryRepairOrchestrator {
    private FbdGeometryRepairOrchestrator() {}

    public record Outcome(LlmPlan plan, String reason, int y) {
        public boolean accepted() { return plan != null; }
    }

    /**
     * Assess then try permitted ±1 patches once. Returns the first verified candidate,
     * or an empty outcome that keeps the original preview path.
     */
    public static Outcome tryRepair(LlmPlan plan, BuildQualityReport report,
                                    Function<LlmPlan, BuildQualityReport> verifier) {
        if (plan == null || report == null || verifier == null || !FbdRepairLoop.hasProgram(plan)) {
            return none("SKIPPED");
        }
        var assessment = FbdGeometryRepairGuard.assess(plan, report);
        FormacraftMod.LOGGER.info(
                "FBD geometry repair assessment: eligible={} reason={} path={} instances={}",
                assessment.eligible(), assessment.reason(),
                assessment.eligible() ? assessment.permit().path() : "-",
                assessment.eligible() ? assessment.permit().affectedInstances() : 0);
        if (!assessment.eligible()) return none(assessment.reason());

        var permit = assessment.permit();
        for (int y : new int[]{permit.minimum(), permit.maximum()}) {
            var patch = new FbdRepairLoop.Patch(permit.path(), IntNode.valueOf(y));
            var evaluated = FbdGeometryRepairGuard.evaluate(plan, permit, patch, verifier);
            FormacraftMod.LOGGER.info(
                    "FBD geometry repair candidate: y={} accepted={} reason={}",
                    y, evaluated.accepted(), evaluated.reason());
            if (evaluated.accepted()) {
                return new Outcome(evaluated.candidate(), evaluated.reason(), y);
            }
        }
        return none("NO_ACCEPTED_CANDIDATE");
    }

    private static Outcome none(String reason) {
        return new Outcome(null, reason, Integer.MIN_VALUE);
    }
}
