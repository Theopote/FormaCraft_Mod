package com.formacraft.common.compiler.postprocess;

import com.formacraft.common.patch.BlockPatch;
import com.formacraft.common.palette.component.Palette;
import com.formacraft.common.palette.component.PaletteLibrary;
import com.formacraft.common.semantic.SemanticPart;
import com.formacraft.FormacraftMod;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * DetailEnhancementPostProcessor（细节装饰增强后处理器）
 * 
 * 在基础结构上添加细节装饰元素，使建筑更加丰富和真实。
 * 
 * 功能：
 * - 在墙体边缘添加装饰块
 * - 在屋顶边缘添加檐口
 * - 在角落添加装饰柱
 * - 在顶部添加装饰元素
 */
public class DetailEnhancementPostProcessor implements PostProcessor {

    @Override
    public List<BlockPatch> process(List<BlockPatch> patches, PostProcessContext context) {
        if (patches == null || patches.isEmpty()) {
            return patches;
        }

        List<BlockPatch> result = new ArrayList<>(patches);
        String styleProfile = context.plan().styleProfile() != null 
                ? context.plan().styleProfile() 
                : "MEDIEVAL_CLASSIC";
        Palette palette = PaletteLibrary.forStyle(styleProfile);

        Map<Long, BlockPatch> solid = new HashMap<>();
        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxY = Integer.MIN_VALUE;
        int maxZ = Integer.MIN_VALUE;

        for (BlockPatch patch : patches) {
            if (patch == null) continue;
            if (BlockPatch.REMOVE.equals(patch.action())) continue;
            String target = patch.targetBlock();
            if (target == null || target.isBlank() || "minecraft:air".equals(target)) continue;
            long key = pack(patch.dx(), patch.dy(), patch.dz());
            solid.put(key, patch);
            minX = Math.min(minX, patch.dx());
            minY = Math.min(minY, patch.dy());
            minZ = Math.min(minZ, patch.dz());
            maxX = Math.max(maxX, patch.dx());
            maxY = Math.max(maxY, patch.dy());
            maxZ = Math.max(maxZ, patch.dz());
        }

        if (solid.isEmpty()) {
            return result;
        }

        List<BlockPatch> enhancements = new ArrayList<>();
        Map<Long, Integer> topYByXZ = new HashMap<>();
        for (BlockPatch patch : solid.values()) {
            long key = packXZ(patch.dx(), patch.dz());
            int top = topYByXZ.getOrDefault(key, Integer.MIN_VALUE);
            if (patch.dy() > top) {
                topYByXZ.put(key, patch.dy());
            }
        }

        addCornice(enhancements, solid, topYByXZ, palette, minX, maxX, minZ, maxZ);
        addCornerPillars(enhancements, solid, palette, minX, maxX, minZ, maxZ, minY, maxY);
        addBeltCourse(enhancements, solid, palette, minX, maxX, minZ, maxZ, minY, maxY);

        result.addAll(enhancements);
        
        if (!enhancements.isEmpty()) {
            FormacraftMod.LOGGER.debug("DetailEnhancementPostProcessor: added {} enhancement patches", 
                    enhancements.size());
        }

        return result;
    }

    /**
     * 在墙体顶部添加檐口装饰
     */
    private void addCornice(List<BlockPatch> enhancements,
                            Map<Long, BlockPatch> solid,
                            Map<Long, Integer> topYByXZ,
                            Palette palette,
                            int minX, int maxX, int minZ, int maxZ) {
        if (enhancements.size() > 1500) return;
        String decor = palette.pick(SemanticPart.DECOR);
        if (decor == null || decor.isBlank()) {
            decor = "minecraft:stone_brick_slab";
        }

        for (Map.Entry<Long, Integer> entry : topYByXZ.entrySet()) {
            int x = unpackX(entry.getKey());
            int z = unpackZ(entry.getKey());
            if (x != minX && x != maxX && z != minZ && z != maxZ) {
                continue;
            }
            int y = entry.getValue() + 1;
            long key = pack(x, y, z);
            if (solid.containsKey(key)) continue;
            enhancements.add(new BlockPatch(BlockPatch.PLACE, x, y, z, decor));
            if (enhancements.size() > 1500) return;
        }
    }

    /**
     * 在角落添加装饰柱
     */
    private void addCornerPillars(List<BlockPatch> enhancements,
                                  Map<Long, BlockPatch> solid,
                                  Palette palette,
                                  int minX, int maxX, int minZ, int maxZ,
                                  int minY, int maxY) {
        String accent = palette.pick(SemanticPart.WALL_ACCENT);
        if (accent == null || accent.isBlank()) {
            accent = "minecraft:stone_bricks";
        }
        int[][] corners = new int[][] {
                {minX, minZ},
                {minX, maxZ},
                {maxX, minZ},
                {maxX, maxZ}
        };
        for (int[] corner : corners) {
            int x = corner[0];
            int z = corner[1];
            for (int y = minY; y <= maxY; y++) {
                long key = pack(x, y, z);
                if (!solid.containsKey(key)) continue;
                enhancements.add(new BlockPatch(BlockPatch.REPLACE, x, y, z, accent));
                if (enhancements.size() > 1500) return;
            }
        }
    }

    /**
     * 在边缘添加装饰块
     */
    private void addBeltCourse(List<BlockPatch> enhancements,
                               Map<Long, BlockPatch> solid,
                               Palette palette,
                               int minX, int maxX, int minZ, int maxZ,
                               int minY, int maxY) {
        int height = Math.max(1, maxY - minY + 1);
        if (height < 5) return;
        int beltY = minY + Math.max(2, height / 2);
        String accent = palette.pick(SemanticPart.WALL_ACCENT);
        if (accent == null || accent.isBlank()) {
            accent = "minecraft:stone_bricks";
        }
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                if (x != minX && x != maxX && z != minZ && z != maxZ) continue;
                long key = pack(x, beltY, z);
                if (!solid.containsKey(key)) continue;
                enhancements.add(new BlockPatch(BlockPatch.REPLACE, x, beltY, z, accent));
                if (enhancements.size() > 1500) return;
            }
        }
    }

    private static long pack(int x, int y, int z) {
        return (((long) x) << 42) ^ (((long) y) << 21) ^ (z & 0x1fffffL);
    }

    private static long packXZ(int x, int z) {
        return (((long) x) << 32) ^ (z & 0xffffffffL);
    }

    private static int unpackX(long key) {
        return (int) (key >> 32);
    }

    private static int unpackZ(long key) {
        return (int) key;
    }
}

