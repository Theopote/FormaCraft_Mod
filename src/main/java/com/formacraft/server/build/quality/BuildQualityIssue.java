package com.formacraft.server.build.quality;

import java.util.List;

/** Bounded, JSON-safe geometry evidence. Positions are world coordinates, not inferred AST sources. */
public record BuildQualityIssue(BuildQualitySeverity severity, String code, String message,
                                String phase, int affectedCount, List<Position> positions, List<Origin> origins) {
    public static final int MAX_SAMPLES = 8;
    public record Position(int x, int y, int z) {}
    public record Origin(Position position, com.formacraft.common.build.GeometrySource source) {}

    public BuildQualityIssue(BuildQualitySeverity severity, String code, String message,
                             String phase, int affectedCount, List<Position> positions) {
        this(severity, code, message, phase, affectedCount, positions, List.of());
    }

    public BuildQualityIssue(BuildQualitySeverity severity, String code, String message) {
        this(severity, code, message, "unspecified", 0, List.of());
    }

    public BuildQualityIssue {
        if (severity == null) severity = BuildQualitySeverity.INFO;
        if (code == null) code = "";
        if (message == null) message = "";
        if (phase == null) phase = "unspecified";
        affectedCount = Math.max(0, affectedCount);
        positions = positions == null ? List.of() : positions.stream().filter(java.util.Objects::nonNull)
                .limit(MAX_SAMPLES).toList();
        var allowed = positions;
        origins = origins == null ? List.of() : origins.stream().filter(java.util.Objects::nonNull)
                .filter(o -> o.source() != null && allowed.contains(o.position())).limit(MAX_SAMPLES).toList();
    }

    public BuildQualityIssue inPhase(String value) {
        return new BuildQualityIssue(severity, code, message, value, affectedCount, positions, origins);
    }

    public BuildQualityIssue locate(java.util.Map<Position, com.formacraft.common.build.GeometrySource> sources) {
        var located = positions.stream().filter(sources::containsKey)
                .filter(p -> sources.get(p) != null).map(p -> new Origin(p, sources.get(p))).toList();
        return new BuildQualityIssue(severity, code, message, phase, affectedCount, positions, located);
    }

    public BuildQualityIssue asHistory() {
        return new BuildQualityIssue(BuildQualitySeverity.INFO, "INITIAL_" + code,
                "[before repair] " + message, phase, affectedCount, positions, origins);
    }
}
