package com.formacraft.common.compiler.postprocess;

import com.formacraft.common.palette.dynamic.ExplicitMaterialPolicy;
import com.formacraft.common.patch.BlockPatch;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Diff of folded PLACE occupancy before vs after a post-process step.
 */
public final class PostProcessPatchDelta {
    private final Map<BlockPos, String> addedPlaces;
    private final Map<BlockPos, String> removedPlaces;
    private final Map<BlockPos, String> replacedPlaces;

    public PostProcessPatchDelta(
            Map<BlockPos, String> addedPlaces,
            Map<BlockPos, String> removedPlaces,
            Map<BlockPos, String> replacedPlaces
    ) {
        this.addedPlaces = addedPlaces == null ? Map.of() : Map.copyOf(addedPlaces);
        this.removedPlaces = removedPlaces == null ? Map.of() : Map.copyOf(removedPlaces);
        this.replacedPlaces = replacedPlaces == null ? Map.of() : Map.copyOf(replacedPlaces);
    }

    public static PostProcessPatchDelta diff(List<BlockPatch> before, List<BlockPatch> after) {
        Map<BlockPos, String> b = foldPlaces(before);
        Map<BlockPos, String> a = foldPlaces(after);
        Map<BlockPos, String> added = new HashMap<>();
        Map<BlockPos, String> removed = new HashMap<>();
        Map<BlockPos, String> replaced = new HashMap<>();
        for (var e : a.entrySet()) {
            String prev = b.get(e.getKey());
            if (prev == null) {
                added.put(e.getKey(), e.getValue());
            } else if (!prev.equals(e.getValue())) {
                replaced.put(e.getKey(), e.getValue());
            }
        }
        for (var e : b.entrySet()) {
            if (!a.containsKey(e.getKey())) {
                removed.put(e.getKey(), e.getValue());
            }
        }
        return new PostProcessPatchDelta(added, removed, replaced);
    }

    /** Fold sequential patches to final PLACE canonical (or raw) per cell. */
    public static Map<BlockPos, String> foldPlaces(List<BlockPatch> patches) {
        Map<BlockPos, String> out = new HashMap<>();
        if (patches == null) return out;
        for (BlockPatch p : patches) {
            if (p == null) continue;
            BlockPos pos = new BlockPos(p.dx(), p.dy(), p.dz());
            if (BlockPatch.REMOVE.equals(p.action())) {
                out.remove(pos);
                continue;
            }
            if (!BlockPatch.PLACE.equals(p.action()) && !BlockPatch.REPLACE.equals(p.action())) continue;
            String canon = ExplicitMaterialPolicy.canonical(p.targetBlock());
            out.put(pos, canon != null ? canon : p.targetBlock());
        }
        return out;
    }

    public boolean isEmpty() {
        return addedPlaces.isEmpty() && removedPlaces.isEmpty() && replacedPlaces.isEmpty();
    }

    public Map<BlockPos, String> addedPlaces() { return addedPlaces; }
    public Map<BlockPos, String> removedPlaces() { return removedPlaces; }
    public Map<BlockPos, String> replacedPlaces() { return replacedPlaces; }

    /** Ordered REMOVE then PLACE batch for occupancy commit. */
    public List<BlockPatch> toPatchBatch() {
        List<BlockPatch> out = new ArrayList<>(removedPlaces.size() + addedPlaces.size() + replacedPlaces.size());
        for (BlockPos pos : removedPlaces.keySet()) {
            out.add(new BlockPatch(BlockPatch.REMOVE, pos.getX(), pos.getY(), pos.getZ(), "minecraft:air"));
        }
        for (var e : replacedPlaces.entrySet()) {
            BlockPos pos = e.getKey();
            out.add(new BlockPatch(BlockPatch.PLACE, pos.getX(), pos.getY(), pos.getZ(), e.getValue()));
        }
        for (var e : addedPlaces.entrySet()) {
            BlockPos pos = e.getKey();
            out.add(new BlockPatch(BlockPatch.PLACE, pos.getX(), pos.getY(), pos.getZ(), e.getValue()));
        }
        return out;
    }
}
