package com.formacraft.common.generation.structure;

import com.formacraft.common.model.build.BuildingSpec;
import com.formacraft.server.build.PlannedBlock;
import net.minecraft.block.BlockState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.util.List;

/**
 * 房屋布局生成器
 *
 * 负责生成建筑内部布局，包括：
 * - 庭院布局（courtyard）
 * - 内部分区（前后/左右分区）
 * - 环形走廊布局（ring corridor）
 *
 * 从 HouseGenerator 中拆分出来，以提高代码可维护性。
 */
public class HouseLayoutGenerator {

    private HouseLayoutGenerator() {} // Utility class

    /**
     * 布局信息记录
     */
    public record LayoutInfo(
            LayoutCourtyard courtyard,
            String plan,
            String symmetry
    ) {}

    /**
     * 庭院布局记录
     */
    public record LayoutCourtyard(boolean enabled, int x0, int x1, int z0, int z1) {
        /**
         * 检查坐标是否在庭院内部
         */
        public boolean containsInterior(int x, int z) {
            return enabled && x >= x0 && x <= x1 && z >= z0 && z <= z1;
        }
    }

    /**
     * 解析布局信息
     *
     * @param spec 建筑规格
     * @param width 建筑宽度
     * @param depth 建筑深度
     * @return 布局信息
     */
    public static LayoutInfo resolveLayout(BuildingSpec spec, int width, int depth) {
        LayoutCourtyard courtyard = resolveLayoutCourtyard(spec, width, depth);
        String plan = resolveLayoutPlan(spec);
        String symmetry = resolveLayoutSymmetry(spec);
        return new LayoutInfo(courtyard, plan, symmetry);
    }

    /**
     * 生成内部分区
     *
     * @param blocks 方块列表
     * @param origin 建筑原点
     * @param width 建筑宽度
     * @param depth 建筑深度
     * @param height 建筑高度
     * @param floors 楼层数
     * @param floorHeight 每层高度
     * @param wall 墙体材质
     * @param trim 装饰材质
     * @param doorSide 门朝向
     * @param layoutInfo 布局信息
     */
    public static void generatePartitions(
            List<PlannedBlock> blocks,
            BlockPos origin,
            int width,
            int depth,
            int height,
            int floors,
            int floorHeight,
            BlockState wall,
            BlockState trim,
            Direction doorSide,
            LayoutInfo layoutInfo) {

        if (layoutInfo == null) return;

        // NOTE: best-effort zoning.
        // - ring_corridor is designed to work WITH courtyard.
        // - other plans are skipped when courtyard is enabled to avoid conflicting geometry.
        if (layoutInfo.courtyard.enabled && isLayoutPlanRingCorridor(layoutInfo.plan)) {
            addRingCorridorPartitions(blocks, origin, width, depth, height, floors, floorHeight,
                    wall, trim, layoutInfo.courtyard, doorSide);
        } else if (!layoutInfo.courtyard.enabled) {
            addInteriorPartitions(blocks, origin, width, depth, height, floors, floorHeight,
                    wall, trim, doorSide, layoutInfo.plan);
        }
    }

    // ========== 布局解析方法 ==========

    private static LayoutCourtyard resolveLayoutCourtyard(BuildingSpec spec, int width, int depth) {
        // Only meaningful when there is enough interior space.
        if (width < 9 || depth < 9) return new LayoutCourtyard(false, 0, -1, 0, -1);
        if (spec == null || spec.getExtra() == null) return new LayoutCourtyard(false, 0, -1, 0, -1);

        boolean enabled = false;
        double ratio = 0.45; // default when enabled
        try {
            Object layoutObj = spec.getExtra().get("layout");
            if (layoutObj instanceof java.util.Map<?, ?> m) {
                Object ct = m.get("courtyard");
                if (ct instanceof Boolean b) enabled = b;
                else if (ct != null) {
                    String s = String.valueOf(ct).trim().toLowerCase(java.util.Locale.ROOT);
                    if (!s.isEmpty()) enabled = (s.equals("true") || s.equals("1") || s.equals("yes") || s.equals("y") || s.equals("on"));
                }
                Object cr = m.get("courtyardRatio");
                if (cr != null) {
                    try {
                        ratio = Double.parseDouble(String.valueOf(cr).trim());
                    } catch (Exception ignored) {}
                }
            }
        } catch (Throwable ignored) {}

        if (!enabled) return new LayoutCourtyard(false, 0, -1, 0, -1);

        if (ratio < 0.2) ratio = 0.2;
        if (ratio > 0.8) ratio = 0.8;

        int interiorW = width - 2;
        int interiorD = depth - 2;
        int cw = Math.max(3, (int) Math.round(interiorW * ratio));
        int cd = Math.max(3, (int) Math.round(interiorD * ratio));
        // keep at least 1 block margin to outer walls for structural stability
        cw = Math.min(cw, interiorW - 2);
        cd = Math.min(cd, interiorD - 2);
        if (cd < 3) return new LayoutCourtyard(false, 0, -1, 0, -1);

        int x0 = 1 + (interiorW - cw) / 2;
        int z0 = 1 + (interiorD - cd) / 2;
        int x1 = x0 + cw - 1;
        int z1 = z0 + cd - 1;

        return new LayoutCourtyard(true, x0, x1, z0, z1);
    }

    private static String resolveLayoutPlan(BuildingSpec spec) {
        if (spec == null || spec.getExtra() == null) return "none";
        try {
            Object layoutObj = spec.getExtra().get("layout");
            if (layoutObj instanceof java.util.Map<?, ?> m) {
                Object plan = m.get("plan");
                if (plan != null) {
                    String p = String.valueOf(plan).trim().toLowerCase(java.util.Locale.ROOT);
                    if (p.isEmpty()) return "none";
                    switch (p) {
                        case "none", "no", "false", "0", "off" -> {
                            return "none";
                        }
                        case "front_back", "frontback", "front-back", "front/back", "前后", "前后分区", "前后布局",
                             "前厅后室" -> {
                            return "front_back";
                        }
                        case "left_right", "leftright", "left-right", "left/right", "左右", "左右分区", "左右布局" -> {
                            return "left_right";
                        }
                        case "ring_corridor", "ring", "courtyard_corridor", "gallery", "cloister", "回廊", "环廊",
                             "环形走廊", "围绕中庭", "回字形", "回字布局", "回字走廊" -> {
                            return "ring_corridor";
                        }
                    }
                }
            }
        } catch (Throwable ignored) {}
        return "none";
    }

    private static String resolveLayoutSymmetry(BuildingSpec spec) {
        if (spec == null || spec.getExtra() == null) return "NONE";
        try {
            Object layoutObj = spec.getExtra().get("layout");
            if (layoutObj instanceof java.util.Map<?, ?> m) {
                Object sym = m.get("symmetry");
                if (sym != null) {
                    String s = String.valueOf(sym).trim().toUpperCase(java.util.Locale.ROOT);
                    if (s.equals("NONE") || s.equals("X") || s.equals("Z") || s.equals("BOTH")) return s;
                }
            }
        } catch (Throwable ignored) {}
        return "NONE";
    }

    private static boolean isLayoutPlanRingCorridor(String plan) {
        if (plan == null) return false;
        String p = plan.trim().toLowerCase(java.util.Locale.ROOT);
        return p.equals("ring_corridor");
    }

    // ========== 分区生成方法 ==========

    private static void addInteriorPartitions(List<PlannedBlock> blocks, BlockPos origin,
                                              int width, int depth, int height,
                                              int floors, int floorHeight,
                                              BlockState wall, BlockState trim,
                                              Direction doorSide, String plan) {
        if (plan == null) return;
        String p = plan.trim().toLowerCase(java.util.Locale.ROOT);
        if (p.isEmpty() || p.equals("none")) return;

        // Need a minimum interior to partition meaningfully.
        if (width < 8 || depth < 8) return;

        // y range inside each floor cell: leave floor at y0 and ceiling at y0+floorHeight.
        int yMin = 1;
        int yMax = Math.max(2, floorHeight - 1); // exclusive upper bound for walls
        int doorH = Math.min(3, Math.max(2, yMax - yMin)); // 2~3 blocks door opening

        boolean splitZ = p.equals("front_back");   // partition line is Z constant (splits front/back)
        boolean splitX = p.equals("left_right");   // partition line is X constant (splits left/right)
        if (!splitZ && !splitX) return;

        boolean doorOnNS = (doorSide == Direction.NORTH || doorSide == Direction.SOUTH);
        int doorAxisCenter = doorOnNS ? (width / 2) : (depth / 2);

        // Partition position: slightly biased so "front" zone (near entrance) is smaller than "back" zone.
        double frontRatio = 0.42;
        int interiorW = width - 2;
        int interiorD = depth - 2;
        int zSplitInterior = 1 + (int) Math.round((interiorD - 1) * frontRatio);
        int xSplitInterior = 1 + (int) Math.round((interiorW - 1) * frontRatio);

        if (doorSide == Direction.SOUTH) {
            // entrance at far Z: mirror split
            zSplitInterior = (depth - 2) - (zSplitInterior - 1);
        }
        if (doorSide == Direction.EAST) {
            // entrance at far X: mirror split
            xSplitInterior = (width - 2) - (xSplitInterior - 1);
        }

        // clamp
        zSplitInterior = Math.max(2, Math.min(depth - 3, zSplitInterior));
        xSplitInterior = Math.max(2, Math.min(width - 3, xSplitInterior));

        // opening width: 1 for narrow, 2 for wide.
        int openW = (doorOnNS ? width : depth) >= 12 ? 2 : 1;
        int open0 = Math.max(2, doorAxisCenter - (openW / 2));
        int open1 = Math.min((doorOnNS ? width - 3 : depth - 3), open0 + openW - 1);

        for (int f = 0; f < floors; f++) {
            int y0 = f * floorHeight;
            if (y0 >= height) break;

            int yTop = Math.min(height - 1, y0 + yMax);
            for (int y = y0 + yMin; y < yTop; y++) {
                if (splitZ) {
                    for (int x = 1; x < width - 1; x++) {
                        boolean opening = (doorOnNS && x >= open0 && x <= open1 && y < (y0 + yMin + doorH));
                        if (opening) continue;
                        BlockState w = wall;
                        // add a subtle trim at top of partition wall
                        if (y == yTop - 1) w = trim;
                        blocks.add(new PlannedBlock(origin.add(x, y, zSplitInterior), w));
                    }
                } else if (splitX) {
                    for (int z = 1; z < depth - 1; z++) {
                        boolean opening = (!doorOnNS && z >= open0 && z <= open1 && y < (y0 + yMin + doorH));
                        if (opening) continue;
                        BlockState w = wall;
                        if (y == yTop - 1) w = trim;
                        blocks.add(new PlannedBlock(origin.add(xSplitInterior, y, z), w));
                    }
                }
            }
        }
    }

    private static void addRingCorridorPartitions(List<PlannedBlock> blocks, BlockPos origin,
                                                  int width, int depth, int height,
                                                  int floors, int floorHeight,
                                                  BlockState wall, BlockState trim,
                                                  LayoutCourtyard courtyard, Direction doorSide) {
        if (courtyard == null || !courtyard.enabled) return;
        if (width < 11 || depth < 11) return; // too tight for courtyard + ring corridor + rooms

        // corridor thickness: 1 for small, 2 for larger footprints
        int t = (width >= 21 && depth >= 21) ? 2 : 1;

        // ring wall rectangle is offset from courtyard by (t+1), leaving a corridor strip between courtyard void and ring wall.
        int rx0 = courtyard.x0 - (t + 1);
        int rx1 = courtyard.x1 + (t + 1);
        int rz0 = courtyard.z0 - (t + 1);
        int rz1 = courtyard.z1 + (t + 1);

        // clamp inside interior (keep 1-block margin to outer walls)
        rx0 = Math.max(2, rx0);
        rz0 = Math.max(2, rz0);
        rx1 = Math.min(width - 3, rx1);
        rz1 = Math.min(depth - 3, rz1);

        if (rx1 - rx0 < 3 || rz1 - rz0 < 3) return;

        int yMin = 1;
        int yMax = Math.max(2, floorHeight - 1);
        int doorH = Math.min(3, Math.max(2, yMax - yMin));

        // openings on ring wall: 4 midpoints (N/S/E/W)
        int midX = (rx0 + rx1) / 2;
        int midZ = (rz0 + rz1) / 2;
        int openW = (width >= 14 && depth >= 14) ? 2 : 1;
        int openX0 = midX - (openW / 2);
        int openX1 = openX0 + openW - 1;
        int openZ0 = midZ - (openW / 2);
        int openZ1 = openZ0 + openW - 1;

        for (int f = 0; f < floors; f++) {
            int y0 = f * floorHeight;
            if (y0 >= height) break;
            int yTop = Math.min(height - 1, y0 + yMax);

            for (int y = y0 + yMin; y < yTop; y++) {
                boolean openingY = y < (y0 + yMin + doorH);

                BlockState w = (y == yTop - 1) ? trim : wall;

                // north (z=rz0): opening at midpoint
                for (int x = rx0; x <= rx1; x++) {
                    boolean opening = openingY && x >= openX0 && x <= openX1;
                    if (opening) continue;
                    blocks.add(new PlannedBlock(origin.add(x, y, rz0), w));
                }
                // south (z=rz1)
                for (int x = rx0; x <= rx1; x++) {
                    boolean opening = openingY && x >= openX0 && x <= openX1;
                    if (opening) continue;
                    blocks.add(new PlannedBlock(origin.add(x, y, rz1), w));
                }
                // west (x=rx0)
                for (int z = rz0; z <= rz1; z++) {
                    boolean opening = openingY && z >= openZ0 && z <= openZ1;
                    if (opening) continue;
                    blocks.add(new PlannedBlock(origin.add(rx0, y, z), w));
                }
                // east (x=rx1)
                for (int z = rz0; z <= rz1; z++) {
                    boolean opening = openingY && z >= openZ0 && z <= openZ1;
                    if (opening) continue;
                    blocks.add(new PlannedBlock(origin.add(rx1, y, z), w));
                }
            }
        }

        // Optional: two radial walls to split the outer zone into 4 rooms (best-effort)
        int cx = width / 2;
        int cz = depth / 2;
        boolean doorOnNS = (doorSide == Direction.NORTH || doorSide == Direction.SOUTH);
        int doorTopRel = 3;

        for (int f = 0; f < floors; f++) {
            int y0 = f * floorHeight;
            if (y0 >= height) break;
            int yTop = Math.min(height - 1, y0 + (floorHeight - 1));

            for (int y = y0 + 1; y < yTop; y++) {
                boolean openingY = y < (y0 + doorTopRel);
                if (doorOnNS) {
                    // split left/right: wall at x=cx from ring wall to outer walls, with an opening at the ring boundary
                    for (int z = rz0; z >= 1; z--) {
                        boolean opening = openingY && z == rz0;
                        if (opening) continue;
                        blocks.add(new PlannedBlock(origin.add(cx, y, z), wall));
                    }
                    for (int z = rz1; z <= depth - 2; z++) {
                        boolean opening = openingY && z == rz1;
                        if (opening) continue;
                        blocks.add(new PlannedBlock(origin.add(cx, y, z), wall));
                    }
                } else {
                    // split front/back: wall at z=cz from ring wall to outer walls, with an opening at the ring boundary
                    for (int x = rx0; x >= 1; x--) {
                        boolean opening = openingY && x == rx0;
                        if (opening) continue;
                        blocks.add(new PlannedBlock(origin.add(x, y, cz), wall));
                    }
                    for (int x = rx1; x <= width - 2; x++) {
                        boolean opening = openingY && x == rx1;
                        if (opening) continue;
                        blocks.add(new PlannedBlock(origin.add(x, y, cz), wall));
                    }
                }
            }
        }
    }
}

