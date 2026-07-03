package com.formacraft.server.build;

import com.formacraft.FormacraftMod;
import com.formacraft.common.build.GeneratedStructure;
import com.formacraft.common.model.build.BuildingSpec;
import com.formacraft.server.build.quality.BuildQualityReport;
import com.formacraft.server.build.quality.GradedQualityChecker;
import net.minecraft.server.world.ServerWorld;

import java.util.ArrayList;
import java.util.List;

/**
 * @deprecated Use {@link GradedQualityChecker} and {@link com.formacraft.server.build.quality.BuildQualityReport}.
 */
@Deprecated
public final class QualityChecker {
    private QualityChecker() {}

    public static class QualityReport {
        public final boolean passed;
        public final List<String> warnings;
        public final List<String> errors;

        public QualityReport(boolean passed, List<String> warnings, List<String> errors) {
            this.passed = passed;
            this.warnings = warnings != null ? warnings : new ArrayList<>();
            this.errors = errors != null ? errors : new ArrayList<>();
        }

        public boolean hasIssues() {
            return !warnings.isEmpty() || !errors.isEmpty();
        }
    }

    public static QualityReport checkQuality(GeneratedStructure structure, BuildingSpec spec, ServerWorld world) {
        BuildQualityReport graded = GradedQualityChecker.checkStructure(structure, spec, world);
        List<String> warnings = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        for (var issue : graded.issues()) {
            switch (issue.severity()) {
                case FATAL, ERROR -> errors.add(issue.message());
                case WARNING, INFO -> warnings.add(issue.message());
            }
        }
        return new QualityReport(graded.allowPreview() && errors.isEmpty(), warnings, errors);
    }

    public static void logQualityReport(QualityReport report, String structureDescription) {
        if (report == null) return;
        if (!report.hasIssues()) {
            FormacraftMod.LOGGER.debug("Quality check passed for: {}", structureDescription);
            return;
        }
        if (!report.errors.isEmpty()) {
            FormacraftMod.LOGGER.warn("Quality check errors for {}: {}", structureDescription, report.errors);
        }
        if (!report.warnings.isEmpty()) {
            FormacraftMod.LOGGER.info("Quality check warnings for {}: {}", structureDescription, report.warnings);
        }
    }
}
