package com.formacraft.common.component.variant;

import java.util.HashSet;
import java.util.Set;

/**
 * Voxel（体素）v1：变体编译器的最小单元。
 * <p>
 * 职责：
 * - 相对坐标（dx/dy/dz）
 * - 方块状态字符串（blockState，例如 "minecraft:spruce_door[facing=south,half=lower]"）
 * - 分段标签（segment tags，例如 "SEG_START" / "SEG_MID_Y" / "SEG_END"）
 * - 语义标签（semantic tags，例如 "FRAME" / "PANEL" / "ACCENT"）
 * <p>
 * 注意：Segment Tag 和 Semantic Tag 是正交的（一个 Voxel 可以同时属于多个标签）。
 */
public final class Voxel {
    private int dx, dy, dz;
    private String blockState;
    private final Set<String> segmentTags = new HashSet<>();
    private final Set<String> semanticTags = new HashSet<>();

    public Voxel(int dx, int dy, int dz, String blockState) {
        this.dx = dx;
        this.dy = dy;
        this.dz = dz;
        this.blockState = blockState != null ? blockState : "minecraft:air";
    }

    public int x() { return dx; }
    public int y() { return dy; }
    public int z() { return dz; }
    public String blockState() { return blockState; }

    public void setBlockState(String state) {
        this.blockState = state != null ? state : "minecraft:air";
    }

    public Set<String> segmentTags() { return segmentTags; }
    public Set<String> semanticTags() { return semanticTags; }

    public void addSegmentTag(String tag) {
        if (tag != null && !tag.isBlank()) segmentTags.add(tag.trim().toUpperCase());
    }

    public void addSemanticTag(String tag) {
        if (tag != null && !tag.isBlank()) semanticTags.add(tag.trim().toUpperCase());
    }

    public boolean hasSegmentTag(String tag) {
        return tag != null && segmentTags.contains(tag.trim().toUpperCase());
    }

    public boolean hasSemanticTag(String tag) {
        return tag != null && semanticTags.contains(tag.trim().toUpperCase());
    }

    /**
     * 在指定轴上偏移（用于分段重复）。
     */
    public Voxel shift(Axis axis, int offset) {
        Voxel v = new Voxel(
                axis == Axis.X ? dx + offset : dx,
                axis == Axis.Y ? dy + offset : dy,
                axis == Axis.Z ? dz + offset : dz,
                blockState
        );
        v.segmentTags.addAll(this.segmentTags);
        v.semanticTags.addAll(this.semanticTags);
        return v;
    }

    /**
     * 变换坐标（用于朝向/镜像）。
     */
    public Voxel transform(int newDx, int newDy, int newDz) {
        Voxel v = new Voxel(newDx, newDy, newDz, blockState);
        v.segmentTags.addAll(this.segmentTags);
        v.semanticTags.addAll(this.semanticTags);
        return v;
    }

    @Override
    public String toString() {
        return String.format("Voxel[%d,%d,%d, block=%s, seg=%s, sem=%s]",
                dx, dy, dz, blockState, segmentTags, semanticTags);
    }

    public enum Axis {
        X, Y, Z
    }
}
