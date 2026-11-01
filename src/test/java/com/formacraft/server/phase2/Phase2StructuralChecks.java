package com.formacraft.server.phase2;

import com.formacraft.common.patch.BlockPatch;
import net.minecraft.util.math.BlockPos;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/** 焦点启发式：不替代观感验收，只挡明显空壳/丢屋顶/无开口。 */
public final class Phase2StructuralChecks {
    private Phase2StructuralChecks() {}

    public record Metrics(
            int solidBlocks,
            int openingCells,
            int roofLike,
            int topLayerSolids,
            int stairLike,
            int glassLike,
            int distinctBlockIds,
            int heightSpan,
            int floorLayersHint,
            String previewDigest
    ) {}

    public static Metrics compute(List<BlockPatch> patches, String previewDigest) {
        Map<BlockPos, String> finalCells = collapseFinalPlaceCells(patches);
        int opening = 0;
        int roof = 0;
        int stair = 0;
        int glass = 0;
        Set<String> ids = new LinkedHashSet<>();
        int minY = Integer.MAX_VALUE;
        int maxY = Integer.MIN_VALUE;
        Set<Integer> floorYs = new TreeSet<>();

        for (var e : finalCells.entrySet()) {
            BlockPos p = e.getKey();
            String block = e.getValue() == null ? "" : e.getValue().toLowerCase(Locale.ROOT);
            ids.add(blockId(block));
            minY = Math.min(minY, p.getY());
            maxY = Math.max(maxY, p.getY());
            if (isOpening(block)) opening++;
            if (isRoofLike(block)) roof++;
            if (isStairLike(block)) stair++;
            if (isGlassLike(block)) glass++;
            floorYs.add(p.getY());
        }

        int topLayer = 0;
        if (!finalCells.isEmpty()) {
            for (var e : finalCells.entrySet()) {
                if (e.getKey().getY() >= maxY - 1) topLayer++;
            }
        }

        int heightSpan = finalCells.isEmpty() ? 0 : (maxY - minY + 1);
        int floorLayers = estimateFloorLayers(floorYs);
        return new Metrics(
                finalCells.size(),
                opening,
                roof,
                topLayer,
                stair,
                glass,
                ids.size(),
                heightSpan,
                floorLayers,
                previewDigest
        );
    }

    public static List<String> violations(Phase2SampleManifest.Checks checks, Metrics m) {
        List<String> v = new java.util.ArrayList<>();
        if (m.solidBlocks() < checks.minSolidBlocks()) {
            v.add("solidBlocks=" + m.solidBlocks() + " < " + checks.minSolidBlocks());
        }
        if (m.distinctBlockIds() < checks.minDistinctBlockIds()) {
            v.add("distinctBlockIds=" + m.distinctBlockIds() + " < " + checks.minDistinctBlockIds());
        }
        if (m.heightSpan() < checks.minHeightSpan()) {
            v.add("heightSpan=" + m.heightSpan() + " < " + checks.minHeightSpan());
        }
        // Roof may emit full blocks / slabs that still form a top enclosure.
        if (checks.requireRoofLike() && m.roofLike() <= 0 && m.topLayerSolids() < 4) {
            v.add("missing roof-like / top-layer enclosure");
        }
        if (checks.requireOpeningLike() && m.openingCells() <= 0) {
            v.add("missing opening-like blocks");
        }
        if (checks.requireStairLike() && m.stairLike() <= 0) {
            v.add("missing stair-like blocks");
        }
        if (checks.requireGlassLike() && m.glassLike() <= 0) {
            v.add("missing glass-like blocks");
        }
        if (checks.minOpeningCells() > 0 && m.openingCells() < checks.minOpeningCells()) {
            v.add("openingCells=" + m.openingCells() + " < " + checks.minOpeningCells());
        }
        if (checks.minFloorLayers() > 0 && m.floorLayersHint() < checks.minFloorLayers()) {
            v.add("floorLayersHint=" + m.floorLayersHint() + " < " + checks.minFloorLayers());
        }
        return v;
    }

    public static Map<BlockPos, String> collapseFinalPlaceCells(List<BlockPatch> patches) {
        Map<BlockPos, String> cells = new LinkedHashMap<>();
        if (patches == null) return cells;
        for (BlockPatch p : patches) {
            if (p == null) continue;
            BlockPos pos = new BlockPos(p.dx(), p.dy(), p.dz());
            String action = p.action() == null ? "" : p.action().toLowerCase(Locale.ROOT);
            if (BlockPatch.REMOVE.equals(action)) {
                cells.remove(pos);
                continue;
            }
            String target = p.targetBlock();
            if (target == null || target.isBlank() || "minecraft:air".equalsIgnoreCase(blockId(target))) {
                cells.remove(pos);
                continue;
            }
            cells.put(pos, target);
        }
        return cells;
    }

    private static int estimateFloorLayers(Set<Integer> ys) {
        if (ys == null || ys.isEmpty()) return 0;
        int layers = 0;
        Integer prev = null;
        for (int y : ys) {
            if (prev == null || y - prev >= 3) {
                layers++;
                prev = y;
            }
        }
        return layers;
    }

    private static String blockId(String stateOrId) {
        if (stateOrId == null) return "";
        int bracket = stateOrId.indexOf('[');
        String id = bracket >= 0 ? stateOrId.substring(0, bracket) : stateOrId;
        return id.trim().toLowerCase(Locale.ROOT);
    }

    private static boolean isOpening(String block) {
        return block.contains("glass") || block.contains("door") || block.contains("pane");
    }

    private static boolean isRoofLike(String block) {
        return block.contains("stairs") || block.contains("slab") || block.contains("roof");
    }

    private static boolean isStairLike(String block) {
        return block.contains("stairs");
    }

    private static boolean isGlassLike(String block) {
        return block.contains("glass");
    }
}
