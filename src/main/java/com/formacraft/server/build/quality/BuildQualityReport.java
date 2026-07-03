package com.formacraft.server.build.quality;

import com.formacraft.FormacraftMod;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Graded quality report with player-facing summary.
 */
public final class BuildQualityReport {
    private static final double OUT_OF_BOUNDS_ERROR_PCT = 10.0;
    private static final int FLOATING_ERROR_COLUMNS = 12;
    private static final int FLOATING_WARNING_COLUMNS = 3;

    private final List<BuildQualityIssue> issues = new ArrayList<>();
    private final BuildQualityStats stats = new BuildQualityStats();

    public BuildQualityStats stats() {
        return stats;
    }

    public List<BuildQualityIssue> issues() {
        return List.copyOf(issues);
    }

    public void add(BuildQualitySeverity severity, String code, String message) {
        if (message == null || message.isBlank()) return;
        issues.add(new BuildQualityIssue(severity, code, message));
    }

    public void merge(BuildQualityReport other) {
        if (other == null) return;
        issues.addAll(other.issues);
        stats.mergeFrom(other.stats);
    }

    public boolean hasSeverity(BuildQualitySeverity severity) {
        for (BuildQualityIssue issue : issues) {
            if (issue.severity() == severity) return true;
        }
        return false;
    }

    public boolean hasFatal() {
        return hasSeverity(BuildQualitySeverity.FATAL);
    }

    public boolean hasError() {
        return hasSeverity(BuildQualitySeverity.ERROR);
    }

    public boolean allowPreview() {
        return !hasFatal();
    }

    public boolean recommendApply() {
        return !hasFatal() && !hasError();
    }

    /**
     * Player-facing one-liner, e.g.
     * 「本次生成 1420 个方块，自动裁剪 35 个越界方块，修复 12 个悬空构件」
     */
    public String summaryZh() {
        StringBuilder sb = new StringBuilder();
        if (stats.totalBlocks > 0) {
            sb.append("本次生成 ").append(stats.totalBlocks).append(" 个方块");
        } else {
            sb.append("本次生成无有效方块");
        }

        List<String> parts = new ArrayList<>();
        if (stats.clippedByConstraint > 0) {
            parts.add("自动裁剪 " + stats.clippedByConstraint + " 个越界方块");
        }
        if (stats.repairedColumns > 0) {
            parts.add("修复 " + stats.repairedColumns + " 个悬空构件");
        } else if (stats.floatingColumns > 0) {
            parts.add("仍有 " + stats.floatingColumns + " 个悬空柱未修复");
        }
        if (stats.supportBlocksAdded > 0) {
            parts.add("补全 " + stats.supportBlocksAdded + " 个支撑方块");
        }
        if (stats.rejectedPatches > 0) {
            parts.add("过滤 " + stats.rejectedPatches + " 个 Patch 操作");
        }
        if (stats.illegalBlocks > 0) {
            parts.add("跳过 " + stats.illegalBlocks + " 个非法方块");
        }
        if (stats.worldHeightViolations > 0) {
            parts.add("触及世界高度边界 " + stats.worldHeightViolations + " 处");
        }
        if (stats.unloadedChunkBlocks > 0) {
            parts.add("未加载区块 " + stats.unloadedChunkBlocks + " 处");
        }
        if (stats.duplicatePositions > 0) {
            parts.add("合并 " + stats.duplicatePositions + " 个重复坐标");
        }

        if (!parts.isEmpty()) {
            sb.append("，").append(String.join("，", parts));
        }

        if (hasFatal()) {
            sb.append("。【致命】无法预览");
        } else if (hasError()) {
            sb.append("。【注意】可预览，但默认不建议直接应用");
        } else if (hasSeverity(BuildQualitySeverity.WARNING)) {
            sb.append("。【提示】存在轻微风险，应用前请检查");
        }

        return sb.toString();
    }

    public void logIssues(String context) {
        if (issues.isEmpty()) {
            FormacraftMod.LOGGER.debug("Quality check passed: {}", context);
            return;
        }
        Map<BuildQualitySeverity, Integer> counts = new EnumMap<>(BuildQualitySeverity.class);
        for (BuildQualityIssue issue : issues) {
            counts.merge(issue.severity(), 1, Integer::sum);
        }
        FormacraftMod.LOGGER.info("Quality [{}]: fatal={} error={} warn={} info={} | {}",
                context,
                counts.getOrDefault(BuildQualitySeverity.FATAL, 0),
                counts.getOrDefault(BuildQualitySeverity.ERROR, 0),
                counts.getOrDefault(BuildQualitySeverity.WARNING, 0),
                counts.getOrDefault(BuildQualitySeverity.INFO, 0),
                summaryZh());
        for (BuildQualityIssue issue : issues) {
            if (issue.severity() == BuildQualitySeverity.FATAL || issue.severity() == BuildQualitySeverity.ERROR) {
                FormacraftMod.LOGGER.warn("  [{}] {}: {}", issue.severity(), issue.code(), issue.message());
            }
        }
    }

    public static double outOfBoundsErrorPct() {
        return OUT_OF_BOUNDS_ERROR_PCT;
    }

    public static int floatingErrorColumns() {
        return FLOATING_ERROR_COLUMNS;
    }

    public static int floatingWarningColumns() {
        return FLOATING_WARNING_COLUMNS;
    }
}
