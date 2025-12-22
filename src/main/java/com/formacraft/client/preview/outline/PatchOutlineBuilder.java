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

    public record Result(List<OutlineQuad> placeOutline, List<OutlineQuad> replaceOutline, List<OutlineQuad> removeOutline) {}

    public static Result build(BlockPos origin, List<BlockPatch> patches) {
        if (origin == null) origin = BlockPos.ORIGIN;
        if (patches == null || patches.isEmpty()) return new Result(List.of(), List.of(), List.of());

        Set<BlockPos> place = new HashSet<>();
        Set<BlockPos> replace = new HashSet<>();
        Set<BlockPos> remove = new HashSet<>();

        for (BlockPatch p : patches) {
            if (p == null) continue;
            BlockPos pos = origin.add(p.dx(), p.dy(), p.dz()).toImmutable();
            String action = p.action() == null ? "" : p.action().toLowerCase();
            if (BlockPatch.REMOVE.equals(action)) remove.add(pos);
            else if (BlockPatch.PLACE.equals(action)) place.add(pos);
            else if (BlockPatch.REPLACE.equals(action)) replace.add(pos);
            else place.add(pos); // 兜底：未知 action 当 place
        }

        // 关键：place/replace 共用“存在并集”做遮挡判断，避免两者相邻时出现内部噪声边线
        Set<BlockPos> exists = new HashSet<>(place.size() + replace.size());
        exists.addAll(place);
        exists.addAll(replace);

        List<OutlineQuad> placeQuads = GreedyOutlineMerger.merge(collectFaces(place, exists));
        List<OutlineQuad> replaceQuads = GreedyOutlineMerger.merge(collectFaces(replace, exists));
        // remove 组内部也需要相互遮挡（相邻 remove 不画内部面）
        List<OutlineQuad> removeQuads = GreedyOutlineMerger.merge(collectFaces(remove, remove));

        return new Result(placeQuads, replaceQuads, removeQuads);
    }

    /**
     * 收集外露面：返回按 direction 分组、再按平面 d 分组的二维单元集合。
     */
    static Map<Direction, Map<Integer, Set<Long>>> collectFaces(Set<BlockPos> voxels, Set<BlockPos> occlusionSet) {
        Map<Direction, Map<Integer, Set<Long>>> out = new EnumMap<>(Direction.class);
        for (Direction d : Direction.values()) out.put(d, new HashMap<>());
        if (voxels == null || voxels.isEmpty()) return out;
        if (occlusionSet == null) occlusionSet = voxels;

        for (BlockPos pos : voxels) {
            if (pos == null) continue;
            for (Direction dir : Direction.values()) {
                BlockPos neighbor = pos.offset(dir);
                // 只要邻居在“遮挡集合”中，就认为该面不外露
                if (occlusionSet.contains(neighbor)) continue;

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

