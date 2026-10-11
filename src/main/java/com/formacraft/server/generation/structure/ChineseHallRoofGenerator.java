package com.formacraft.server.generation.structure;

import com.formacraft.common.build.PlannedBlock;
import net.minecraft.block.BlockState;
import net.minecraft.state.property.Properties;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import java.util.List;
import java.util.Locale;

/** Thin voxel roofs; historic joinery and curved lifting remain separate work. */
public final class ChineseHallRoofGenerator {
    private ChineseHallRoofGenerator() {}

    public static String normalize(String form) {
        return switch (form.trim().toLowerCase(Locale.ROOT)) {
            case "ying_shan", "yingshan", "硬山" -> "ying_shan";
            case "xuan_shan", "xuanshan", "悬山" -> "xuan_shan";
            case "xie_shan", "xieshan", "歇山" -> "xie_shan";
            case "wu_dian", "wudian", "庑殿" -> "wu_dian";
            default -> throw new IllegalArgumentException("不支持的中式屋顶形式：" + form);
        };
    }

    public static void generate(List<PlannedBlock> blocks, int width, int depth, int baseY,
                                String form, int rise, BlockState tile, BlockState stairs, BlockState wall) {
        form = normalize(form);
        int zMin = form.equals("ying_shan") ? 0 : -1;
        int zMax = form.equals("ying_shan") ? depth - 1 : depth;
        int run = (width + 1) / 2;
        if (form.equals("wu_dian")) run = Math.min(run, (depth + 1) / 2);
        int hip = Math.min(3, (depth + 1) / 2);
        int thickness = Math.max(1, (rise + run - 1) / run);
        for (int x = -1; x <= width; x++) for (int z = zMin; z <= zMax; z++) {
            int dx = Math.min(x + 1, width - x), dz = Math.min(z + 1, depth - z);
            int distance = dx;
            if (form.equals("wu_dian") || form.equals("xie_shan") && dz < hip)
                distance = Math.min(dx, dz);
            int h = distance * rise / run;
            Direction facing = distance < dx
                    ? (z < depth / 2 ? Direction.SOUTH : Direction.NORTH)
                    : (x < width / 2 ? Direction.EAST : Direction.WEST);
            BlockState surface = distance == run ? tile
                    : stairs.contains(Properties.HORIZONTAL_FACING)
                    ? stairs.with(Properties.HORIZONTAL_FACING, facing) : tile;
            // Only the surface and its vertical riser: steep slopes cannot leave gaps.
            for (int y = Math.max(0, h - thickness + 1); y <= h; y++)
                blocks.add(new PlannedBlock(new BlockPos(x, baseY + y, z), y == h ? surface : tile));
            boolean gable = form.equals("xie_shan") ? dz == hip
                    : !form.equals("wu_dian") && (z == 0 || z == depth - 1);
            int wallBase = form.equals("xie_shan") ? hip * rise / run : 0;
            if (gable && x >= 0 && x < width)
                for (int y = wallBase; y < h - thickness + 1; y++)
                    blocks.add(new PlannedBlock(new BlockPos(x, baseY + y, z), wall));
        }
    }
}
