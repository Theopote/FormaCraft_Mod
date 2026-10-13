package com.formacraft.server.terrain;

import com.formacraft.common.build.PlannedBlock;
import net.minecraft.util.math.BlockPos;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * 建筑实际 XZ 占地掩码：用于地形清理，避免按包围盒误挖庭院/L 形空白。
 */
public final class BuildingOccupancyMask {
    private final Set<Long> columns = new HashSet<>();
    private final Map<Long, Integer> columnMaxY = new HashMap<>();

    public static BuildingOccupancyMask fromSolidBlocks(Collection<PlannedBlock> blocks) {
        BuildingOccupancyMask mask = new BuildingOccupancyMask();
        if (blocks == null) return mask;
        for (PlannedBlock b : blocks) {
            if (b == null || b.getPos() == null || b.getTargetState() == null) continue;
            if (b.getTargetState().isAir()) continue;
            mask.add(b.getPos());
        }
        return mask;
    }

    public void add(BlockPos pos) {
        if (pos == null) return;
        long k = columnKey(pos.getX(), pos.getZ());
        columns.add(k);
        columnMaxY.merge(k, pos.getY(), Math::max);
    }

    public boolean contains(int x, int z) {
        return columns.contains(columnKey(x, z));
    }

    public boolean isEmpty() {
        return columns.isEmpty();
    }

    public int size() {
        return columns.size();
    }

    /** 该柱建筑最高 Y；无占地时返回 fallback。 */
    public int maxYInColumn(int x, int z, int fallback) {
        Integer v = columnMaxY.get(columnKey(x, z));
        return v != null ? v : fallback;
    }

    public Set<Long> columnKeys() {
        return Set.copyOf(columns);
    }

    public static int unpackX(long key) {
        return (int) (key >> 32);
    }

    public static int unpackZ(long key) {
        return (int) key;
    }

    public static long columnKey(int x, int z) {
        return (((long) x) << 32) ^ (z & 0xffffffffL);
    }
}
