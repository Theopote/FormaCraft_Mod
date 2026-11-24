package com.formacraft.common.generation.component.util;

import com.formacraft.common.compiler.semantic.SemanticComponent;
import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.dto.LlmPlan;
import com.formacraft.common.patch.BlockPatch;
import net.minecraft.util.math.Direction;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 主路径 Window Order：在窗洞周围装配 sill（窗台）、lintel（倒楼梯眉）与 optional pediment（山花）。
 */
public final class ComponentWindowOrderDecorator {

    public enum OrderLevel {
        OFF,
        MEDIUM,
        FULL
    }

    public record FacadeOpening(
            Direction outward,
            int fixedCoord,
            int axisMin,
            int axisMax,
            int yMin,
            int yMax,
            boolean onPrimaryFacade,
            boolean centeredOnBuilding
    ) {
        int centerAxis() {
            return (axisMin + axisMax) / 2;
        }
    }

    private ComponentWindowOrderDecorator() {}

    public static OrderLevel resolveLevel(LlmPlan plan, Map<String, Object> params, SemanticComponent semantic) {
        if (isDisabled(getParam(params, "window_order", "windowOrder"))) {
            return OrderLevel.OFF;
        }
        String raw = getParam(params, "window_order", "windowOrder");
        if (raw != null) {
            OrderLevel parsed = parseLevel(raw);
            if (parsed != OrderLevel.OFF) {
                return parsed;
            }
        }
        if (plan != null && plan.proportionHints() != null) {
            Object hint = plan.proportionHints().get("window_order");
            if (hint == null) {
                hint = plan.proportionHints().get("windowOrder");
            }
            if (hint != null) {
                OrderLevel parsed = parseLevel(String.valueOf(hint));
                if (parsed != OrderLevel.OFF) {
                    return parsed;
                }
            }
            String typology = String.valueOf(plan.proportionHints().getOrDefault("typology", "")).toLowerCase(Locale.ROOT);
            if (typology.contains("classical") || typology.contains("monument") || typology.contains("castle")) {
                return OrderLevel.FULL;
            }
        }
        String profile = getParam(params, "facade_profile", "facadeProfile");
        if (profile != null) {
            String fp = profile.toLowerCase(Locale.ROOT);
            if (fp.contains("pilaster") || fp.contains("colonnade") || fp.contains("classical")) {
                return OrderLevel.FULL;
            }
        }
        if (semantic != null && semantic.source() != null && semantic.source().features() != null) {
            for (String f : semantic.source().features()) {
                if (f == null) {
                    continue;
                }
                String lower = f.toLowerCase(Locale.ROOT);
                if (lower.contains("window_order") || lower.contains("pediment") || lower.contains("facade_rhythm")) {
                    return OrderLevel.FULL;
                }
            }
        }
        return OrderLevel.OFF;
    }

    public static boolean shouldApply(LlmPlan plan, Map<String, Object> params, SemanticComponent semantic) {
        return resolveLevel(plan, params, semantic) != OrderLevel.OFF;
    }

    public static boolean isGlassBlock(String blockId) {
        if (blockId == null || blockId.isBlank()) {
            return false;
        }
        String lower = blockId.toLowerCase(Locale.ROOT);
        return lower.contains("glass") || lower.contains("iron_bars");
    }

    /** 在 south/north 立面上，fixedCoord 为 z，axis 为 x。east/west 立面 fixedCoord 为 x，axis 为 z。 */
    public static List<FacadeOpening> clusterFacadeOpenings(
            Set<long[]> glassCells,
            int minX, int maxX, int minY, int maxY, int minZ, int maxZ,
            int buildingCenterX,
            int buildingCenterZ,
            Direction primaryOutward
    ) {
        List<FacadeOpening> out = new ArrayList<>();
        out.addAll(clusterOnFace(glassCells, Direction.SOUTH, minX, maxX, minY, maxY, minZ, maxZ, minZ, true, buildingCenterX, primaryOutward));
        out.addAll(clusterOnFace(glassCells, Direction.NORTH, minX, maxX, minY, maxY, minZ, maxZ, maxZ, true, buildingCenterX, primaryOutward));
        out.addAll(clusterOnFace(glassCells, Direction.WEST, minX, maxX, minY, maxY, minZ, maxZ, minX, false, buildingCenterZ, primaryOutward));
        out.addAll(clusterOnFace(glassCells, Direction.EAST, minX, maxX, minY, maxY, minZ, maxZ, maxX, false, buildingCenterZ, primaryOutward));
        return out;
    }

    public static void emitWindowOrder(
            List<BlockPatch> out,
            FacadeOpening opening,
            OrderLevel level,
            String trimBlock,
            String trimStair,
            String trimSlab
    ) {
        emitWindowOrder(out, opening, level, trimBlock, trimStair, trimSlab,
                com.formacraft.common.style.AestheticPassHints.WindowTrim.CLASSIC, null);
    }

    public static void emitWindowOrder(
            List<BlockPatch> out,
            FacadeOpening opening,
            OrderLevel level,
            String trimBlock,
            String trimStair,
            String trimSlab,
            com.formacraft.common.style.AestheticPassHints.WindowTrim trimStyle,
            String trapdoorId
    ) {
        if (level == OrderLevel.OFF || opening == null) {
            return;
        }
        if (trimStyle == com.formacraft.common.style.AestheticPassHints.WindowTrim.NONE) {
            return;
        }
        Direction outDir = opening.outward();
        int width = opening.axisMax() - opening.axisMin() + 1;
        boolean sill = trimStyle != com.formacraft.common.style.AestheticPassHints.WindowTrim.MODERN
                || true; // modern still gets continuous shade bands below
        boolean lintel = true;
        boolean sides = level == OrderLevel.FULL
                && trimStyle == com.formacraft.common.style.AestheticPassHints.WindowTrim.CLASSIC;
        boolean pediment = level == OrderLevel.FULL && opening.centeredOnBuilding() && opening.onPrimaryFacade()
                && width >= 2
                && trimStyle == com.formacraft.common.style.AestheticPassHints.WindowTrim.CLASSIC;

        if (trimStyle == com.formacraft.common.style.AestheticPassHints.WindowTrim.MODERN) {
            // Brise-soleil: continuous top slab band; optional bottom shade.
            String topSlab = SubVoxelBlockStates.slab(trimSlab, true);
            String botSlab = SubVoxelBlockStates.slab(trimSlab, false);
            if (opening.yMax() + 1 <= 320) {
                for (int axis = opening.axisMin() - 1; axis <= opening.axisMax() + 1; axis++) {
                    addOutwardPlace(out, outDir, opening.fixedCoord(), axis, opening.yMax() + 1, topSlab);
                }
            }
            if (opening.yMin() > 0) {
                for (int axis = opening.axisMin() - 1; axis <= opening.axisMax() + 1; axis++) {
                    addOutwardPlace(out, outDir, opening.fixedCoord(), axis, opening.yMin() - 1, botSlab);
                }
            }
            return;
        }

        if (sill && opening.yMin() > 0) {
            String sillBlock = trimStyle == com.formacraft.common.style.AestheticPassHints.WindowTrim.EAST_ASIAN
                    ? SubVoxelBlockStates.slab(trimSlab, false)
                    : (trimSlab != null ? trimSlab : "minecraft:stone_brick_slab");
            if (trimStyle == com.formacraft.common.style.AestheticPassHints.WindowTrim.CLASSIC
                    || trimStyle == com.formacraft.common.style.AestheticPassHints.WindowTrim.ARCH) {
                sillBlock = SubVoxelBlockStates.outwardStairs(trimStair, outDir, false);
                if (sillBlock == null) sillBlock = SubVoxelBlockStates.slab(trimSlab, false);
            }
            for (int axis = opening.axisMin() - 1; axis <= opening.axisMax() + 1; axis++) {
                addOutwardPlace(out, outDir, opening.fixedCoord(), axis, opening.yMin() - 1, sillBlock);
            }
        }
        if (lintel && opening.yMax() + 1 <= 320) {
            if (trimStyle == com.formacraft.common.style.AestheticPassHints.WindowTrim.EAST_ASIAN
                    && trapdoorId != null) {
                String eave = SubVoxelBlockStates.shutterTrapdoor(trapdoorId, outDir, false);
                for (int axis = opening.axisMin(); axis <= opening.axisMax(); axis++) {
                    addOutwardPlace(out, outDir, opening.fixedCoord(), axis, opening.yMax() + 1, eave);
                }
            } else if (trimStyle == com.formacraft.common.style.AestheticPassHints.WindowTrim.ARCH && width >= 3) {
                for (int axis = opening.axisMin(); axis <= opening.axisMax(); axis++) {
                    boolean corner = axis == opening.axisMin() || axis == opening.axisMax();
                    String block = corner
                            ? SubVoxelBlockStates.outwardStairs(trimStair, outDir, true)
                            : SubVoxelBlockStates.slab(trimSlab, true);
                    addOutwardPlace(out, outDir, opening.fixedCoord(), axis, opening.yMax() + 1, block);
                }
            } else {
                for (int axis = opening.axisMin(); axis <= opening.axisMax(); axis++) {
                    addOutwardPlace(out, outDir, opening.fixedCoord(), axis, opening.yMax() + 1,
                            SubVoxelBlockStates.outwardStairs(trimStair, outDir, true) != null
                                    ? SubVoxelBlockStates.outwardStairs(trimStair, outDir, true)
                                    : ComponentFloorCorniceDecorator.corniceStairBlock(trimStair, outDir));
                }
            }
        }
        // Classic shutters for narrow windows share side cells with FULL side posts — prefer shutters
        boolean hasShutters = trimStyle == com.formacraft.common.style.AestheticPassHints.WindowTrim.CLASSIC
                && trapdoorId != null && width <= 2;
        if (hasShutters) {
            String shutter = SubVoxelBlockStates.shutterTrapdoor(trapdoorId, outDir, true);
            for (int y = opening.yMin(); y <= opening.yMax(); y++) {
                addOutwardPlace(out, outDir, opening.fixedCoord(), opening.axisMin() - 1, y, shutter);
                addOutwardPlace(out, outDir, opening.fixedCoord(), opening.axisMax() + 1, y, shutter);
            }
        } else if (sides) {
            for (int y = opening.yMin(); y <= opening.yMax(); y++) {
                addOutwardPlace(out, outDir, opening.fixedCoord(), opening.axisMin() - 1, y, trimBlock);
                addOutwardPlace(out, outDir, opening.fixedCoord(), opening.axisMax() + 1, y, trimBlock);
            }
        }
        if (pediment) {
            int cx = opening.centerAxis();
            addOutwardPlace(out, outDir, opening.fixedCoord(), cx, opening.yMax() + 2,
                    ComponentFloorCorniceDecorator.corniceStairBlock(trimStair, outDir));
            if (opening.axisMin() <= cx - 1 && opening.axisMax() >= cx + 1) {
                addOutwardPlace(out, outDir, opening.fixedCoord(), cx - 1, opening.yMax() + 1,
                        SubVoxelBlockStates.slab(trimSlab, true));
                addOutwardPlace(out, outDir, opening.fixedCoord(), cx + 1, opening.yMax() + 1,
                        SubVoxelBlockStates.slab(trimSlab, true));
            }
        }
    }

    public static String inferSlabBlock(String blockId) {
        String stair = ComponentFloorCorniceDecorator.inferStairsBlock(blockId);
        if (stair.endsWith("_stairs")) {
            return stair.substring(0, stair.length() - "_stairs".length()) + "_slab";
        }
        return "minecraft:stone_brick_slab";
    }

    static List<FacadeOpening> clusterOnFace(
            Set<long[]> glassCells,
            Direction outward,
            int minX, int maxX, int minY, int maxY, int minZ, int maxZ,
            int fixedCoord,
            boolean axisIsX,
            int buildingCenterAxis,
            Direction primaryOutward
    ) {
        boolean[][] grid = new boolean[axisIsX ? (maxX - minX + 1) : (maxZ - minZ + 1)][maxY - minY + 1];
        for (long[] cell : glassCells) {
            int x = (int) cell[0];
            int y = (int) cell[1];
            int z = (int) cell[2];
            if (axisIsX) {
                if (z != fixedCoord) {
                    continue;
                }
            } else if (x != fixedCoord) {
                continue;
            }
            int axis = axisIsX ? x : z;
            int axisMinBound = axisIsX ? minX : minZ;
            int axisIndex = axis - axisMinBound;
            int yIndex = y - minY;
            if (axisIndex >= 0 && axisIndex < grid.length && yIndex >= 0 && yIndex < grid[0].length) {
                grid[axisIndex][yIndex] = true;
            }
        }

        boolean[][] visited = new boolean[grid.length][grid[0].length];
        List<FacadeOpening> openings = new ArrayList<>();
        for (int ai = 0; ai < grid.length; ai++) {
            for (int yi = 0; yi < grid[0].length; yi++) {
                if (!grid[ai][yi] || visited[ai][yi]) {
                    continue;
                }
                int axisMin = ai;
                int axisMax = ai;
                int yMin = yi;
                int yMax = yi;
                List<int[]> stack = new ArrayList<>();
                stack.add(new int[] {ai, yi});
                visited[ai][yi] = true;
                while (!stack.isEmpty()) {
                    int[] cur = stack.remove(stack.size() - 1);
                    axisMin = Math.min(axisMin, cur[0]);
                    axisMax = Math.max(axisMax, cur[0]);
                    yMin = Math.min(yMin, cur[1]);
                    yMax = Math.max(yMax, cur[1]);
                    for (int[] nb : new int[][] {{cur[0] - 1, cur[1]}, {cur[0] + 1, cur[1]}, {cur[0], cur[1] - 1}, {cur[0], cur[1] + 1}}) {
                        if (nb[0] < 0 || nb[0] >= grid.length || nb[1] < 0 || nb[1] >= grid[0].length) {
                            continue;
                        }
                        if (!grid[nb[0]][nb[1]] || visited[nb[0]][nb[1]]) {
                            continue;
                        }
                        visited[nb[0]][nb[1]] = true;
                        stack.add(nb);
                    }
                }
                int axisMinWorld = (axisIsX ? minX : minZ) + axisMin;
                int axisMaxWorld = (axisIsX ? minX : minZ) + axisMax;
                int center = (axisMinWorld + axisMaxWorld) / 2;
                openings.add(new FacadeOpening(
                        outward,
                        fixedCoord,
                        axisMinWorld,
                        axisMaxWorld,
                        minY + yMin,
                        minY + yMax,
                        outward == primaryOutward,
                        Math.abs(center - buildingCenterAxis) <= 1
                ));
            }
        }
        return openings;
    }

    private static void addOnFace(List<BlockPatch> out, Direction outward, int fixedCoord, int axis, int y, String block) {
        addOutwardPlace(out, outward, fixedCoord, axis, y, block);
    }

    /** Place trim one cell outward from the façade plane (air niche / visual depth). */
    private static void addOutwardPlace(List<BlockPatch> out, Direction outward, int fixedCoord, int axis, int y, String block) {
        if (block == null || block.isBlank()) {
            return;
        }
        int x;
        int z;
        switch (outward) {
            case SOUTH -> {
                x = axis;
                z = fixedCoord + 1;
            }
            case NORTH -> {
                x = axis;
                z = fixedCoord - 1;
            }
            case WEST -> {
                x = fixedCoord - 1;
                z = axis;
            }
            case EAST -> {
                x = fixedCoord + 1;
                z = axis;
            }
            default -> {
                x = axis;
                z = fixedCoord;
            }
        }
        out.add(new BlockPatch(BlockPatch.PLACE, x, y, z, block));
    }

    public static long[] packCell(int x, int y, int z) {
        return new long[] {x, y, z};
    }

    public static Set<long[]> cellSetFromKeys(Set<Long> keys) {
        Set<long[]> out = new HashSet<>();
        for (Long key : keys) {
            if (key == null) {
                continue;
            }
            out.add(new long[] {
                    (int) (key >> 42),
                    (int) ((key >> 21) & 0x1fffffL),
                    (int) (key & 0x1fffffL)
            });
        }
        return out;
    }

    private static OrderLevel parseLevel(String raw) {
        if (raw == null) {
            return OrderLevel.OFF;
        }
        String v = raw.trim().toLowerCase(Locale.ROOT);
        if (v.isEmpty() || "false".equals(v) || "off".equals(v) || "none".equals(v)) {
            return OrderLevel.OFF;
        }
        if ("full".equals(v) || "high".equals(v) || "true".equals(v) || "yes".equals(v)) {
            return OrderLevel.FULL;
        }
        return OrderLevel.MEDIUM;
    }

    private static boolean isDisabled(Object v) {
        return v != null && ("false".equalsIgnoreCase(String.valueOf(v).trim()) || "off".equalsIgnoreCase(String.valueOf(v).trim()));
    }

    private static String getParam(Map<String, Object> params, String... keys) {
        if (params == null) {
            return null;
        }
        for (String key : keys) {
            Object v = params.get(key);
            if (v == null) {
                continue;
            }
            String s = String.valueOf(v).trim();
            if (!s.isEmpty()) {
                return s;
            }
        }
        return null;
    }
}
