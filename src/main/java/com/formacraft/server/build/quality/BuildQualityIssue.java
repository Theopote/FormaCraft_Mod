package com.formacraft.server.build.quality;

/** A single graded quality finding. */
public record BuildQualityIssue(BuildQualitySeverity severity, String code, String message) {
    public BuildQualityIssue {
        if (severity == null) severity = BuildQualitySeverity.INFO;
        if (code == null) code = "";
        if (message == null) message = "";
    }
}
