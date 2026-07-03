package com.formacraft.server.build.quality;

/**
 * Numeric counters backing player-facing quality summaries.
 */
public final class BuildQualityStats {
    public int totalBlocks;
    public int clippedByConstraint;
    public int worldHeightViolations;
    public int unloadedChunkBlocks;
    public int illegalBlocks;
    public int floatingColumns;
    public int repairedColumns;
    public int supportBlocksAdded;
    public int duplicatePositions;
    public int rejectedPatches;

    public void mergeFrom(BuildQualityStats other) {
        if (other == null) return;
        totalBlocks += other.totalBlocks;
        clippedByConstraint += other.clippedByConstraint;
        worldHeightViolations += other.worldHeightViolations;
        unloadedChunkBlocks += other.unloadedChunkBlocks;
        illegalBlocks += other.illegalBlocks;
        floatingColumns += other.floatingColumns;
        repairedColumns += other.repairedColumns;
        supportBlocksAdded += other.supportBlocksAdded;
        duplicatePositions += other.duplicatePositions;
        rejectedPatches += other.rejectedPatches;
    }
}
