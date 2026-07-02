package com.formacraft.common.generation.structure;

import com.formacraft.common.model.build.BuildingSpec;
import com.formacraft.common.style.profile.BuildStrategy;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.enums.DoubleBlockHalf;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.state.property.Properties;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.util.Set;

/**
 * HouseGenerator 工具方法类
 *
 * 提供各种辅助方法，包括：
 * - 方块状态处理（朝向、门状态等）
 * - 位置判断（门边缘、靠近门等）
 * - 窗户放置逻辑
 * - 门位置计算
 *
 * 从 HouseGenerator 中拆分出来，提高代码可维护性。
 */
public class HouseGeneratorUtils {

    private HouseGeneratorUtils() {} // Utility class

    // ========== 方块状态工具方法 ==========

    /**
     * 如果方块支持朝向属性，则设置朝向
     */
    public static BlockState withFacingIfPossible(BlockState state, Direction facing) {
        if (state == null) return null;
        try {
            if (state.contains(Properties.HORIZONTAL_FACING)) {
                return state.with(Properties.HORIZONTAL_FACING, facing);
            }
        } catch (Throwable ignored) {}
        return state;
    }

    /**
     * 设置门的状态（朝向、半块、铰链）
     */
    public static BlockState withDoorState(BlockState door, DoubleBlockHalf half, boolean leftSide, Direction facing) {
        BlockState s = door;
        try {
            if (s.contains(Properties.HORIZONTAL_FACING)) s = s.with(Properties.HORIZONTAL_FACING, facing != null ? facing : Direction.NORTH);
        } catch (Throwable ignored) {}
        try {
            if (s.contains(Properties.DOUBLE_BLOCK_HALF)) s = s.with(Properties.DOUBLE_BLOCK_HALF, half);
        } catch (Throwable ignored) {}
        // 双门时用相反铰链，避免都向同一侧开（即便不完美，也比默认好）
        try {
            if (s.contains(Properties.DOOR_HINGE)) {
                s = s.with(Properties.DOOR_HINGE, leftSide ? net.minecraft.block.enums.DoorHinge.LEFT : net.minecraft.block.enums.DoorHinge.RIGHT);
            }
        } catch (Throwable ignored) {}
        return s;
    }

    // ========== 门位置判断方法 ==========

    /**
     * 判断位置是否在门边缘（门的入口边）
     */
    public static boolean isDoorEdge(Direction doorSide, int x, int z, int width, int depth) {
        if (doorSide == null) return (z == 0);
        return switch (doorSide) {
            case NORTH -> z == 0;
            case SOUTH -> z == depth - 1;
            case WEST -> x == 0;
            case EAST -> x == width - 1;
            default -> z == 0;
        };
    }

    /**
     * 判断位置是否靠近门（在门的中心区域）
     */
    public static boolean isNearDoor(Direction doorSide, int x, int z, int width, int depth) {
        int cx = width / 2;
        int cz = depth / 2;
        if (doorSide == null) doorSide = Direction.NORTH;
        return switch (doorSide) {
            case NORTH -> (z == 0) && (x == cx || x == cx - 1);
            case SOUTH -> (z == depth - 1) && (x == cx || x == cx - 1);
            case WEST -> (x == 0) && (z == cz || z == cz - 1);
            case EAST -> (x == width - 1) && (z == cz || z == cz - 1);
            default -> (z == 0) && (x == cx || x == cx - 1);
        };
    }

    /**
     * 解析门的方向
     * 优先级：extra.layout.entranceFacing > extra.doorSide > extra.facing > 默认 NORTH
     */
    public static Direction resolveDoorSide(BuildingSpec spec) {
        if (spec == null || spec.getExtra() == null) return Direction.NORTH;

        // Layout IR: extra.layout.entranceFacing overrides legacy doorSide/facing
        try {
            Object layoutObj = spec.getExtra().get("layout");
            if (layoutObj instanceof java.util.Map<?, ?> m) {
                Object ef = m.get("entranceFacing");
                if (ef != null) {
                    String s = String.valueOf(ef).trim().toUpperCase();
                    switch (s) {
                        case "N", "NORTH", "北", "朝北" -> { return Direction.NORTH; }
                        case "S", "SOUTH", "南", "朝南" -> { return Direction.SOUTH; }
                        case "E", "EAST", "东", "朝东" -> { return Direction.EAST; }
                        case "W", "WEST", "西", "朝西" -> { return Direction.WEST; }
                        default -> {}
                    }
                }
            }
        } catch (Throwable ignored) {}
        Object v = spec.getExtra().get("doorSide");
        if (v == null) v = spec.getExtra().get("facing"); // tolerate reuse of facing as "front side"
        if (v == null) return Direction.NORTH;
        String s = String.valueOf(v).trim().toUpperCase();
        return switch (s) {
            case "N", "NORTH", "北", "朝北" -> Direction.NORTH;
            case "S", "SOUTH", "南", "朝南" -> Direction.SOUTH;
            case "E", "EAST", "东", "朝东" -> Direction.EAST;
            case "W", "WEST", "西", "朝西" -> Direction.WEST;
            default -> Direction.NORTH;
        };
    }

    /**
     * 计算门火炬的位置
     */
    public static BlockPos doorTorchPos(BlockPos origin, Direction doorSide, int axis, int y, int width, int depth) {
        return switch (doorSide) {
            case NORTH -> origin.add(axis, y, 1);
            case SOUTH -> origin.add(axis, y, depth - 2);
            case WEST -> origin.add(1, y, axis);
            case EAST -> origin.add(width - 2, y, axis);
            default -> origin.add(axis, y, 1);
        };
    }

    // ========== 窗户放置判断方法 ==========

    /**
     * 判断是否应该在该位置放置窗户
     */
    public static boolean isShouldPlaceWindow(BuildStrategy wallStrategy, double windowRatio, boolean preferSymmetry,
                                               int x, int z, int width, int depth) {
        // Only meaningful on exterior ring; caller already checks edges.
        // Derive spacing from density suggestion (still respects explicit windowRatio values).
        int spacing;
        if (windowRatio >= 0.65) spacing = 2;
        else if (windowRatio >= 0.38) spacing = 3;
        else spacing = 4;

        // Don't place windows at corners (corner pillars take that space and look better without glass).
        boolean corner = (x == 0 || x == width - 1) && (z == 0 || z == depth - 1);
        if (corner) return false;

        // Keep a small margin away from corners for better rhythm
        if (x <= 1 || z <= 1 || x >= width - 2 || z >= depth - 2) {
            // still allow on the outermost ring if it's not a corner, but be conservative for SOLID_WALL
            if (wallStrategy == BuildStrategy.SOLID_WALL) return false;
        }

        boolean onNorthSouth = (z == 0 || z == depth - 1);
        boolean onWestEast = (x == 0 || x == width - 1);

        if (wallStrategy == BuildStrategy.SOLID_WALL) {
            // Solid walls: sparse, centered rhythm (stronger silhouette).
            if (onNorthSouth) {
                int cx = width / 2;
                return (Math.abs(x - cx) % spacing == 0) && x >= 2 && x <= width - 3;
            }
            if (onWestEast) {
                int cz = depth / 2;
                return (Math.abs(z - cz) % spacing == 0) && z >= 2 && z <= depth - 3;
            }
            return false;
        }

        // WINDOWED_WALL (default): regular cadence along edges.
        if (preferSymmetry) {
            int cx = width / 2;
            int cz = depth / 2;
            if (onNorthSouth) return (Math.abs(x - cx) % spacing == 0) && x >= 2 && x <= width - 3;
            if (onWestEast) return (Math.abs(z - cz) % spacing == 0) && z >= 2 && z <= depth - 3;
            return false;
        }
        if (onNorthSouth) return (x % spacing == 0) && x >= 2 && x <= width - 3;
        if (onWestEast) return (z % spacing == 0) && z >= 2 && z <= depth - 3;
        return false;
    }

    /**
     * 判断是否是栅栏类窗户
     */
    public static boolean isFenceLikeWindow(BlockState windowBlock) {
        if (windowBlock == null) return false;
        String id = net.minecraft.registry.Registries.BLOCK.getId(windowBlock.getBlock()).toString();
        return id.endsWith("_fence") && !id.endsWith("_fence_gate");
    }

    /**
     * 尝试收集栅栏框架位置（用于栅栏窗户的支撑框架）
     */
    public static void tryCollectFenceFrame(Set<BlockPos> out,
                                             BlockPos origin,
                                             BuildStrategy wallStrategy,
                                             double windowRatio,
                                             boolean preferSymmetry,
                                             int x,
                                             int y,
                                             int z,
                                             int width,
                                             int depth) {
        if (x < 0 || z < 0 || x >= width || z >= depth) return;
        // avoid corner pillars
        if ((x == 0 || x == width - 1) && (z == 0 || z == depth - 1)) return;
        // avoid door area (front center)
        boolean nearDoor = (z == 0) && (x == width / 2 || x == width / 2 - 1);
        if (nearDoor) return;
        // avoid overwriting adjacent windows
        boolean wouldBeWindow = isShouldPlaceWindow(wallStrategy, windowRatio, preferSymmetry, x, z, width, depth);
        if (wouldBeWindow) return;
        out.add(origin.add(x, y, z));
    }

    /**
     * 判断是否可以安全放置尖拱窗
     */
    public static boolean isPointedArchWindowSafe(BuildStrategy wallStrategy, double windowRatio, boolean preferSymmetry,
                                                   Direction doorSide, int x, int z, int width, int depth) {
        if (isNearDoor(doorSide, x, z, width, depth)) return false;
        boolean corner = (x == 0 || x == width - 1) && (z == 0 || z == depth - 1);
        if (corner) return false;

        // 尖拱窗需要左右两侧各有至少一格空间（不能紧邻其他窗户）
        boolean onNorthSouth = (z == 0 || z == depth - 1);
        if (onNorthSouth) {
            boolean leftWouldBeWindow = isShouldPlaceWindow(wallStrategy, windowRatio, preferSymmetry, x - 1, z, width, depth);
            boolean rightWouldBeWindow = isShouldPlaceWindow(wallStrategy, windowRatio, preferSymmetry, x + 1, z, width, depth);
            return !leftWouldBeWindow && !rightWouldBeWindow;
        }
        // onWestEast
        boolean a = isShouldPlaceWindow(wallStrategy, windowRatio, preferSymmetry, x, z - 1, width, depth);
        boolean b = isShouldPlaceWindow(wallStrategy, windowRatio, preferSymmetry, x, z + 1, width, depth);
        return !a && !b;
    }

    // ========== 墙体图案应用方法 ==========

    /**
     * 应用墙体图案（gradient, striped, random 等）
     */
    public static BlockState applyWallPattern(BlockState wall, BlockState trim, BlockState foundation, String pattern, int y, int height) {
        String p = (pattern == null) ? "uniform" : pattern.trim().toLowerCase();
        // gradient：底部更"厚重"、顶部更"收边"
        switch (p) {
            case "gradient" -> {
                if (y <= 1) return foundation != null ? foundation : wall;
                if (y >= height - 2) return trim != null ? trim : wall;
                return wall;
            }

            // striped：每 3 层一条横向条带
            case "striped" -> {
                if (y % 3 == 0) return trim != null ? trim : wall;
                return wall;
            }

            // random：对 stone_bricks 加一点 cracked/mossy 变化
            case "random" -> {
                Block b = wall != null ? wall.getBlock() : null;
                if (b == Blocks.STONE_BRICKS) {
                    int r = (y * 31 + height * 17) & 7;
                    if (r == 0) return Blocks.CRACKED_STONE_BRICKS.getDefaultState();
                    if (r == 1) return Blocks.MOSSY_STONE_BRICKS.getDefaultState();
                }
                return wall;
            }
        }
        return wall;
    }

    /**
     * 应用立面配置文件到墙体单元格
     */
    public static BlockState applyFacadeProfileToWallCell(BlockState current,
                                                           BlockState wall,
                                                           BlockState trim,
                                                           BlockState foundation,
                                                           String facadeProfile,
                                                           Direction doorSide,
                                                           int x,
                                                           int y,
                                                           int z,
                                                           int width,
                                                           int depth,
                                                           boolean hasDoor,
                                                           int floorHeight) {
        if (current == null) return null;
        String fp = (facadeProfile == null) ? "" : facadeProfile.trim().toLowerCase(java.util.Locale.ROOT);
        if (fp.isBlank()) return current;

        boolean isEdgeX = (x == 0 || x == width - 1);
        boolean isEdgeZ = (z == 0 || z == depth - 1);
        if (!(isEdgeX || isEdgeZ)) return current;

        // Don't clobber non-wall materials (trim/foundation already placed earlier)
        if (wall != null && current != wall) return current;

        boolean nearDoor = isNearDoor(doorSide, x, z, width, depth);
        if (hasDoor && nearDoor) return current;

        // base plinth: heavier base band
        if (fp.contains("base_plinth")) {
            if (y == 1) return foundation != null ? foundation : current;
            return current;
        }

        // vertical pilasters: periodic vertical trim strips
        if (fp.contains("vertical_pilasters")) {
            int cadence = 3;
            if (isEdgeZ) {
                if (x > 0 && x < width - 1 && (x % cadence == 0) && y > 0) return trim != null ? trim : current;
            } else {
                if (z > 0 && z < depth - 1 && (z % cadence == 0) && y > 0) return trim != null ? trim : current;
            }
            return current;
        }

        // mullion grid: stronger floor bands + subtle vertical mullions
        if (fp.contains("mullion_grid")) {
            int localY = (floorHeight > 0) ? (y % floorHeight) : 0;
            if (y > 0 && localY == 0) return trim != null ? trim : current;
            int cadence = 2;
            if (isEdgeZ) {
                if (x > 0 && x < width - 1 && (x % cadence == 0) && y > 0) return trim != null ? trim : current;
            } else {
                if (z > 0 && z < depth - 1 && (z % cadence == 0) && y > 0) return trim != null ? trim : current;
            }
            return current;
        }

        // module grid: brutalist-ish panelization (light touch)
        if (fp.contains("module_grid")) {
            if (y > 0 && (y % 3 == 0)) return trim != null ? trim : current;
            if (isEdgeZ) {
                if (x > 0 && x < width - 1 && (x % 3 == 0) && y > 0) return trim != null ? trim : current;
            } else {
                if (z > 0 && z < depth - 1 && (z % 3 == 0) && y > 0) return trim != null ? trim : current;
            }
            return current;
        }

        return current;
    }
}

