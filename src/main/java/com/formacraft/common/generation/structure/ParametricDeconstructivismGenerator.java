package com.formacraft.common.generation.structure;

import com.formacraft.common.model.build.BuildingSpec;
import com.formacraft.common.model.build.BuildingStyle;
import com.formacraft.common.style.profile.DetailPreferences;
import com.formacraft.common.style.profile.StyleProfile;
import com.formacraft.common.style.profile.StyleProfileRegistry;
import com.formacraft.server.build.GeneratedStructure;
import com.formacraft.server.build.PlannedBlock;
import com.formacraft.server.material.PaletteResolver;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * ParametricDeconstructivismGenerator（解构主义/参数化强原型，v1）
 *
 * 目标：在 Minecraft 轴对齐体素里，用“标量场 + 壳体”近似流体曲面/非线性（Zaha/Gehry 风）。
 * - 平面：扭曲椭圆（warp ellipse）
 * - 立面/屋顶：波浪高度场（sin/cos + twist）
 * - 壳体：薄壳厚度 1~2
 * - 玻璃：在曲面上抽样生成“流动窗带”
 */
public class ParametricDeconstructivismGenerator implements StructureGenerator {

    @Override
    public GeneratedStructure generate(BuildingSpec spec, BlockPos origin, ServerWorld world) {
        List<PlannedBlock> blocks = new ArrayList<>();

        BuildingStyle style = (spec != null && spec.getStyle() != null) ? spec.getStyle() : BuildingStyle.MODERN;
        StyleProfile profile = (spec != null) ? StyleProfileRegistry.resolve(spec) : StyleProfileRegistry.forStyle(style);
        DetailPreferences details = profile != null ? profile.details() : null;

        String paletteId = null;
        if (spec != null && spec.getExtra() != null && spec.getExtra().get("paletteId") != null) {
            paletteId = String.valueOf(spec.getExtra().get("paletteId")).trim();
        }
        if ((paletteId == null || paletteId.isBlank()) && details != null && details.paletteId != null && !details.paletteId.isBlank()) {
            paletteId = details.paletteId.trim();
        }

        Direction entrance = resolveEntranceFacing(spec);

        int w = clampOdd(getIntExtra(spec, "width", (spec != null && spec.getFootprint() != null ? spec.getFootprint().getWidth() : 23)), 15, 81);
        int d = clampOdd(getIntExtra(spec, "depth", (spec != null && spec.getFootprint() != null ? spec.getFootprint().getDepth() : 31)), 15, 101);
        int h = clamp(getIntExtra(spec, "height", (spec != null ? spec.getHeight() : 28)), 16, 120);

        int shell = clamp(getIntExtra(spec, "shellThickness", 2), 1, 3);
        double warpA = clampDouble(getDoubleExtra(spec, "warpA", 2.6), 0.0, 6.0);
        double warpB = clampDouble(getDoubleExtra(spec, "warpB", 1.8), 0.0, 6.0);
        double amp = clampDouble(getDoubleExtra(spec, "amplitude", Math.max(4.0, h * 0.32)), 2.0, Math.max(6.0, h * 0.55));
        boolean glazing = getBoolExtra(spec, "glazing", true);

        // Materials (palette-driven, clean white)
        BlockState foundation = Blocks.SMOOTH_QUARTZ.getDefaultState();
        BlockState wall = Blocks.WHITE_CONCRETE.getDefaultState();
        BlockState trim = Blocks.SMOOTH_QUARTZ.getDefaultState();
        BlockState floor = Blocks.QUARTZ_BLOCK.getDefaultState();
        BlockState glass = Blocks.LIGHT_BLUE_STAINED_GLASS.getDefaultState();
        BlockState glassPane = Blocks.LIGHT_BLUE_STAINED_GLASS_PANE.getDefaultState();
        BlockState roofSlab = Blocks.SMOOTH_QUARTZ_SLAB.getDefaultState();
        BlockState internal = Blocks.SEA_LANTERN.getDefaultState();

        if (paletteId != null && !paletteId.isBlank()) {
            foundation = PaletteResolver.pick(world, paletteId, "WALL_FOUNDATION", origin, 0xD301L, foundation);
            wall = PaletteResolver.pick(world, paletteId, "WALL_BASE", origin, 0xD302L, wall);
            trim = PaletteResolver.pick(world, paletteId, "FRAME", origin, 0xD303L, trim);
            trim = PaletteResolver.pick(world, paletteId, "DECOR_DETAIL", origin, 0xD304L, trim);
            floor = PaletteResolver.pick(world, paletteId, "FLOORING", origin, 0xD305L, floor);
            roofSlab = PaletteResolver.pick(world, paletteId, "FLOOR_SLAB", origin, 0xD306L, roofSlab);
            glass = PaletteResolver.pick(world, paletteId, "FACADE_CURTAIN", origin, 0xD307L, glass);
            glassPane = PaletteResolver.pick(world, paletteId, "WINDOW", origin, 0xD308L, glassPane);
            internal = PaletteResolver.pick(world, paletteId, "INTERNAL_LIGHT", origin, 0xD309L, internal);
            internal = PaletteResolver.pick(world, paletteId, "LIGHTING", origin, 0xD30AL, internal);
        }

        int rx = w / 2;
        int rz = d / 2;
        int baseY = 0;
        int pad = 6;
        int yMax = h + 14;

        // Clear (best-effort)
        for (int x = -rx - pad; x <= rx + pad; x++) {
            for (int z = -rz - pad; z <= rz + pad; z++) {
                for (int y = 0; y <= yMax; y++) {
                    blocks.add(new PlannedBlock(local(origin, entrance, x, y, z), Blocks.AIR.getDefaultState()));
                }
            }
        }

        // Foundation disk-ish pad (rect for simplicity)
        fillRect(blocks, origin, entrance, -rx - 1, baseY, -rz - 1, rx + 1, baseY + 1, rz + 1, foundation);

        // Scalar field helpers
        // Inside shape: warped ellipse. Height: wave field with twist.
        for (int x = -rx - 2; x <= rx + 2; x++) {
            for (int z = -rz - 2; z <= rz + 2; z++) {
                // warp
                double xw = x + warpA * Math.sin(z * 0.23);
                double zw = z + warpB * Math.sin(x * 0.19);
                double nx = xw / Math.max(1.0, rx);
                double nz = zw / Math.max(1.0, rz);
                double e = (nx * nx) + (nz * nz); // <= 1 means inside
                if (e > 1.06) continue; // outside w/ small feather

                double wave = Math.sin(x * 0.21) + 0.9 * Math.cos(z * 0.17) + 0.6 * Math.sin((x + z) * 0.13);
                double twist = 0.35 * Math.sin((xw * 0.11) - (zw * 0.15));
                double hLocal = 6.0 + (amp * (0.55 + 0.45 * (wave / 2.5))) + (amp * 0.22 * twist);
                int top = clamp((int) Math.round(hLocal), 8, h + 10);

                // inner carve
                double inner = e / 1.0; // reuse
                boolean insideInner = (inner <= 0.72);
                int innerTop = top - shell - 2;

                // floor inside
                if (insideInner) {
                    blocks.add(new PlannedBlock(local(origin, entrance, x, 2, z), floor));
                    // internal light sparse
                    if (((x * 31 + z * 17) & 31) == 0) {
                        blocks.add(new PlannedBlock(local(origin, entrance, x, 3, z), internal));
                    }
                }

                // shell volume
                for (int y = 3; y <= top; y++) {
                    boolean solid = (e <= 1.0) && (y <= top);
                    if (!solid) continue;

                    boolean hollow = insideInner && (y <= innerTop);
                    if (hollow) {
                        blocks.add(new PlannedBlock(local(origin, entrance, x, y, z), Blocks.AIR.getDefaultState()));
                        continue;
                    }

                    // decide if on surface (thin shell) by checking neighbor outside/above
                    boolean surface = false;
                    if (y >= top - (shell - 1)) surface = true;
                    if (e >= 0.94) surface = true;

                    if (!surface) {
                        // keep it mostly hollow, but add ribs occasionally to avoid “too empty”
                        if (((x + z + y) & 15) == 0) {
                            blocks.add(new PlannedBlock(local(origin, entrance, x, y, z), trim));
                        } else {
                            blocks.add(new PlannedBlock(local(origin, entrance, x, y, z), Blocks.AIR.getDefaultState()));
                        }
                        continue;
                    }

                    // glazing ribbon: on mid band of the shell
                    if (glazing) {
                        int bandY0 = (int) Math.round(top * 0.45);
                        int bandY1 = bandY0 + 2;
                        boolean inBand = (y >= bandY0 && y <= bandY1);
                        if (inBand && e >= 0.90 && ((x * 13 + z * 7) & 3) != 0) {
                            blocks.add(new PlannedBlock(local(origin, entrance, x, y, z), (y == bandY0 ? glassPane : glass)));
                            continue;
                        }
                    }

                    // roof smoothing near top
                    if (y == top) {
                        blocks.add(new PlannedBlock(local(origin, entrance, x, y, z), roofSlab));
                    } else {
                        blocks.add(new PlannedBlock(local(origin, entrance, x, y, z), wall));
                    }
                }
            }
        }

        String desc = "Parametric Deconstructivism v1";
        return new GeneratedStructure(null, origin, desc, blocks);
    }

    // ---- helpers (rotation + parsing) ----

    private static BlockPos local(BlockPos origin, Direction entrance, int x, int y, int z) {
        BlockPos p = origin.add(x, y, z);
        return rotatePos(p, origin, entrance);
    }

    private static BlockPos rotatePos(BlockPos p, BlockPos base, Direction entrance) {
        if (entrance == null || entrance == Direction.SOUTH) return p;
        int dx = p.getX() - base.getX();
        int dy = p.getY() - base.getY();
        int dz = p.getZ() - base.getZ();
        return switch (entrance) {
            case NORTH -> base.add(-dx, dy, -dz);
            case EAST -> base.add(dz, dy, -dx);
            case WEST -> base.add(-dz, dy, dx);
            default -> p;
        };
    }

    private static void fillRect(List<PlannedBlock> blocks, BlockPos origin, Direction entrance,
                                 int x0, int y0, int z0, int x1, int y1, int z1, BlockState s) {
        for (int x = x0; x <= x1; x++) for (int z = z0; z <= z1; z++) for (int y = y0; y <= y1; y++) {
            blocks.add(new PlannedBlock(local(origin, entrance, x, y, z), s));
        }
    }

    private static Direction resolveEntranceFacing(BuildingSpec spec) {
        if (spec == null || spec.getExtra() == null) return Direction.SOUTH;
        try {
            Object layoutObj = spec.getExtra().get("layout");
            if (layoutObj instanceof Map<?, ?> m) {
                Object ef = m.get("entranceFacing");
                if (ef != null) {
                    String s = String.valueOf(ef).trim().toUpperCase(java.util.Locale.ROOT);
                    return switch (s) {
                        case "N", "NORTH", "北", "朝北" -> Direction.NORTH;
                        case "S", "SOUTH", "南", "朝南" -> Direction.SOUTH;
                        case "E", "EAST", "东", "朝东" -> Direction.EAST;
                        case "W", "WEST", "西", "朝西" -> Direction.WEST;
                        default -> Direction.SOUTH;
                    };
                }
            }
        } catch (Throwable ignored) {}
        return Direction.SOUTH;
    }

    private static int getIntExtra(BuildingSpec spec, String key, int def) {
        if (spec == null || spec.getExtra() == null || key == null) return def;
        Object v = spec.getExtra().get(key);
        if (v == null) return def;
        try {
            if (v instanceof Number n) return n.intValue();
            String s = String.valueOf(v).trim();
            return s.isEmpty() ? def : Integer.parseInt(s);
        } catch (Exception e) {
            return def;
        }
    }

    private static double getDoubleExtra(BuildingSpec spec, String key, double def) {
        if (spec == null || spec.getExtra() == null || key == null) return def;
        Object v = spec.getExtra().get(key);
        if (v == null) return def;
        try {
            if (v instanceof Number n) return n.doubleValue();
            String s = String.valueOf(v).trim();
            return s.isEmpty() ? def : Double.parseDouble(s);
        } catch (Exception e) {
            return def;
        }
    }

    private static boolean getBoolExtra(BuildingSpec spec, String key, boolean def) {
        if (spec == null || spec.getExtra() == null || key == null) return def;
        Object v = spec.getExtra().get(key);
        if (v == null) return def;
        if (v instanceof Boolean b) return b;
        String s = String.valueOf(v).trim().toLowerCase(java.util.Locale.ROOT);
        if (s.isEmpty()) return def;
        return s.equals("true") || s.equals("1") || s.equals("yes") || s.equals("y") || s.equals("on");
    }

    private static int clamp(int v, int min, int max) { return Math.max(min, Math.min(max, v)); }
    private static int clampOdd(int v, int min, int max) {
        int x = clamp(v, min, max);
        return (x % 2 == 0) ? (x + 1 <= max ? x + 1 : x - 1) : x;
    }
    private static double clampDouble(double v, double min, double max) { return Math.max(min, Math.min(max, v)); }
}


