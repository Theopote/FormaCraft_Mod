package com.formacraft.client.preview.outline;

import com.formacraft.common.patch.BlockPatch;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 从 Patch 生成“外露面”集合，并做 greedy 合并。
 *
 * 约定：
 * - place/replace 参与 “填充集合 filled”
 * - remove 参与 “移除集合 removed”
 *
 * 最终会生成两套 outline：
 * - filledOutline：用于绿色/黄色
 * - removedOutline：用于红色
 */
public final class PatchOutlineBuilder {
    private PatchOutlineBuilder() {}

    public record Result(List<OutlineQuad> filledOutline, List<OutlineQuad> removedOutline) {}

    public static Result build(BlockPos origin, List<BlockPatch> patches) {
        if (origin == null) origin = BlockPos.ORIGIN;
        if (patches == null || patches.isEmpty()) return new Result(List.of(), List.of());

        Set<BlockPos> filled = new HashSet<>();
        Set<BlockPos> removed = new HashSet<>();

        for (BlockPatch p : patches) {
            if (p == null) continue;
            BlockPos pos = origin.add(p.dx(), p.dy(), p.dz()).toImmutable();
            String action = p.action() == null ? "" : p.action().toLowerCase();
            if (BlockPatch.REMOVE.equals(action)) {
                removed.add(pos);
            } else {
                // place/replace 统一当成“将存在的方块”
                filled.add(pos);
            }
        }

        List<OutlineQuad> filledQuads = GreedyOutlineMerger.merge(collectFaces(filled));
        List<OutlineQuad> removedQuads = GreedyOutlineMerger.merge(collectFaces(removed));
        return new Result(filledQuads, removedQuads);
    }

    /**
     * 收集外露面：返回按 direction 分组、再按平面 d 分组的二维单元集合。
     */
    static Map<Direction, Map<Integer, Set<Long>>> collectFaces(Set<BlockPos> voxels) {
        Map<Direction, Map<Integer, Set<Long>>> out = new EnumMap<>(Direction.class);
        for (Direction d : Direction.values()) out.put(d, new HashMap<>());
        if (voxels == null || voxels.isEmpty()) return out;

        for (BlockPos pos : voxels) {
            if (pos == null) continue;
            for (Direction dir : Direction.values()) {
                BlockPos neighbor = pos.offset(dir);
                if (voxels.contains(neighbor)) continue;

                // face cell（u,v）与 plane（d）
                int plane;
                int u;
                int v;
                switch (dir) {
                    case EAST -> { // +X, plane at x = pos.x + 1, u=z, v=y
                        plane = pos.getX() + 1;
                        u = pos.getZ();
                        v = pos.getY();
                    }
                    case WEST -> { // -X, plane at x = pos.x, u=z, v=y
                        plane = pos.getX();
                        u = pos.getZ();
                        v = pos.getY();
                    }
                    case UP -> { // +Y, plane at y = pos.y + 1, u=x, v=z
                        plane = pos.getY() + 1;
                        u = pos.getX();
                        v = pos.getZ();
                    }
                    case DOWN -> { // -Y, plane at y = pos.y, u=x, v=z
                        plane = pos.getY();
                        u = pos.getX();
                        v = pos.getZ();
                    }
                    case SOUTH -> { // +Z, plane at z = pos.z + 1, u=x, v=y
                        plane = pos.getZ() + 1;
                        u = pos.getX();
                        v = pos.getY();
                    }
                    case NORTH -> { // -Z, plane at z = pos.z, u=x, v=y
                        plane = pos.getZ();
                        u = pos.getX();
                        v = pos.getY();
                    }
                    default -> {
                        continue;
                    }
                }

                Map<Integer, Set<Long>> byPlane = out.get(dir);
                Set<Long> cells = byPlane.computeIfAbsent(plane, k -> new HashSet<>());
                cells.add(pack(u, v));
            }
        }

        return out;
    }

    static long pack(int u, int v) {
        return (((long) u) << 32) ^ (v & 0xffffffffL);
    }

    static int unpackU(long key) {
        return (int) (key >> 32);
    }

    static int unpackV(long key) {
        return (int) key;
    }
}

