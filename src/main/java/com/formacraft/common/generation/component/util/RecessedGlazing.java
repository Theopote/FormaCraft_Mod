package com.formacraft.common.generation.component.util;

import com.formacraft.common.patch.BlockPatch;

import java.util.List;
import java.util.Map;

/**
 * Places façade glass with optional 1-cell recess when wall thickness ≥ 2.
 */
public final class RecessedGlazing {
    private RecessedGlazing() {}

    public static boolean recessEnabled(Map<String, Object> params) {
        if (params == null) return true;
        Object v = params.get("recess_window");
        if (v == null) v = params.get("recessWindow");
        if (v == null) return true;
        String s = String.valueOf(v).trim();
        return !(s.equalsIgnoreCase("false") || s.equalsIgnoreCase("off") || s.equalsIgnoreCase("0"));
    }

    /**
     * @param outerX/Y/Z wall exterior cell
     * @param inwardX/Z direction toward interior (unit)
     * @param thickness wall thickness ≥ 1
     */
    public static void emit(
            List<BlockPatch> out,
            int outerX, int outerY, int outerZ,
            int inwardX, int inwardZ,
            int thickness,
            String glassBlock,
            boolean recess
    ) {
        int t = Math.max(1, thickness);
        boolean doRecess = recess && t >= 2 && (inwardX != 0 || inwardZ != 0);
        if (doRecess) {
            // Outer cell stays open (window niche); glass sits one cell inward.
            out.add(new BlockPatch(BlockPatch.REMOVE, outerX, outerY, outerZ, "minecraft:air"));
            int gx = outerX + inwardX;
            int gz = outerZ + inwardZ;
            GeneratedSurfaceCapture.record(gx, outerY, gz, GeneratedSurfaceCapture.Role.WINDOW);
            out.add(new BlockPatch(BlockPatch.PLACE, gx, outerY, gz, glassBlock));
            for (int layer = 2; layer < t; layer++) {
                out.add(new BlockPatch(BlockPatch.REMOVE,
                        outerX + inwardX * layer, outerY, outerZ + inwardZ * layer, "minecraft:air"));
            }
        } else {
            for (int layer = 1; layer < t; layer++) {
                out.add(new BlockPatch(BlockPatch.REMOVE,
                        outerX + inwardX * layer, outerY, outerZ + inwardZ * layer, "minecraft:air"));
            }
            GeneratedSurfaceCapture.record(outerX, outerY, outerZ, GeneratedSurfaceCapture.Role.WINDOW);
            out.add(new BlockPatch(BlockPatch.PLACE, outerX, outerY, outerZ, glassBlock));
        }
    }
}
