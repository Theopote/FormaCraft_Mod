package com.formacraft.server.build.quality;

/**
 * Patch 与 BUILD 共用的确认质量门禁。
 * <ul>
 *   <li>FATAL → 禁止</li>
 *   <li>ERROR → 需显式 force</li>
 *   <li>WARNING/INFO → 允许</li>
 * </ul>
 */
public final class BuildQualityGate {
    private BuildQualityGate() {}

    public enum Decision {
        ALLOW,
        REQUIRE_FORCE,
        DENY_FATAL
    }

    public static Decision decide(BuildQualityReport report, boolean force) {
        if (report == null) return Decision.ALLOW;
        if (report.hasFatal()) return Decision.DENY_FATAL;
        if (report.hasError() && !force) return Decision.REQUIRE_FORCE;
        return Decision.ALLOW;
    }

    public static Decision decide(QualitySnapshot snapshot, boolean force) {
        if (snapshot == null) return Decision.ALLOW;
        if (snapshot.hasFatal()) return Decision.DENY_FATAL;
        if (snapshot.hasError() && !force) return Decision.REQUIRE_FORCE;
        return Decision.ALLOW;
    }

    /** Ticket / 网络可携带的不可变质量摘要。 */
    public record QualitySnapshot(boolean hasFatal, boolean hasError, String summaryZh) {
        public static QualitySnapshot from(BuildQualityReport report) {
            if (report == null) {
                return new QualitySnapshot(false, false, "");
            }
            return new QualitySnapshot(report.hasFatal(), report.hasError(), report.summaryZh());
        }
    }
}
