package com.formacraft.server.generator;

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
import net.minecraft.block.DoorBlock;
import net.minecraft.block.enums.DoubleBlockHalf;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.state.property.Properties;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * JapaneseCastleKeepGenerator（天守阁强原型，v1）
 *
 * 识别性目标（v1 简化但要“像”）：
 * - 塔身收分：多层矩形体量逐层缩小
 * - 层叠屋檐：每层顶部一圈大出檐（深色瓦），形成横向线条
 * - 入口方向：extra.layout.entranceFacing 控制门朝向（90°旋转）
 *
 * 触发建议：extra.template = "japanese_castle_keep" 或 "tenshu"
 */
public class JapaneseCastleKeepGenerator implements StructureGenerator {

    @Override
    public GeneratedStructure generate(BuildingSpec spec, BlockPos origin, ServerWorld world) {
        List<PlannedBlock> blocks = new ArrayList<>();

        // paletteId fallback: extra.paletteId > profile.details.paletteId
        BuildingStyle style = (spec != null && spec.getStyle() != null) ? spec.getStyle() : BuildingStyle.ASIAN;
        StyleProfile profile = (spec != null) ? StyleProfileRegistry.resolve(spec) : StyleProfileRegistry.forStyle(style);
        DetailPreferences details = profile != null ? profile.details() : null;

        String paletteId = getStringExtra(spec, "paletteId", null);
        if ((paletteId == null || paletteId.isBlank()) && details != null && details.paletteId != null && !details.paletteId.isBlank()) {
            paletteId = details.paletteId.trim();
        }

        Direction entrance = resolveEntranceFacing(spec);

        int baseW = Math.max(17, spec != null && spec.getFootprint() != null ? spec.getFootprint().getWidth() : 19);
        int baseD = Math.max(17, spec != null && spec.getFootprint() != null ? spec.getFootprint().getDepth() : 19);
        baseW = clampOdd(baseW, 17, 71);
        baseD = clampOdd(baseD, 17, 71);

        int tiers = clamp(getIntExtra(spec, "tiers", 4), 3, 6);
        int tierHeight = clamp(getIntExtra(spec, "tierHeight", 5), 4, 8);
        int roofHeight = clamp(getIntExtra(spec, "roofHeight", 4), 3, 6);
        int taperStep = clamp(getIntExtra(spec, "taperStep", 4), 2, 6); // shrink per tier (both sides total)
        int podiumH = clamp(getIntExtra(spec, "podiumHeight", 2), 1, 4);
        int eavesOverhang = clamp(getIntExtra(spec, "eavesOverhang", 2), 1, 3);

        // Materials (palette-driven)
        BlockState foundation = Blocks.STONE_BRICKS.getDefaultState();
        BlockState wall = Blocks.BIRCH_PLANKS.getDefaultState();
        BlockState trim = Blocks.SPRUCE_LOG.getDefaultState();
        BlockState floor = Blocks.BIRCH_PLANKS.getDefaultState();
        BlockState window = Blocks.WHITE_STAINED_GLASS_PANE.getDefaultState();
        BlockState roofTile = Blocks.DEEPSLATE_TILES.getDefaultState();
        BlockState roofStairs = Blocks.DEEPSLATE_TILE_STAIRS.getDefaultState();
        BlockState roofSlab = Blocks.DEEPSLATE_TILE_SLAB.getDefaultState();
        BlockState lantern = Blocks.LANTERN.getDefaultState();

        if (paletteId != null && !paletteId.isBlank()) {
            foundation = PaletteResolver.pick(world, paletteId, "WALL_FOUNDATION", origin, 0xC45001L, foundation);
            wall = PaletteResolver.pick(world, paletteId, "WALL_BASE", origin, 0xC45002L, wall);
            trim = PaletteResolver.pick(world, paletteId, "FRAME", origin, 0xC45003L, trim);
            trim = PaletteResolver.pick(world, paletteId, "DECOR_DETAIL", origin, 0xC45004L, trim);
            floor = PaletteResolver.pick(world, paletteId, "FLOORING", origin, 0xC45005L, floor);
            window = PaletteResolver.pick(world, paletteId, "WINDOW", origin, 0xC45006L, window);
            roofTile = PaletteResolver.pick(world, paletteId, "ROOF_TILE", origin, 0xC45007L, roofTile);
            roofStairs = PaletteResolver.pick(world, paletteId, "ROOF_SLOPE", origin, 0xC45008L, roofStairs);
            roofSlab = PaletteResolver.pick(world, paletteId, "FLOOR_SLAB", origin, 0xC45009L, roofSlab);
            lantern = PaletteResolver.pick(world, paletteId, "LIGHTING", origin, 0xC4500AL, lantern);
            lantern = PaletteResolver.pick(world, paletteId, "ROAD_LIGHT", origin, 0xC4500BL, lantern);
        }

        // local build: entrance always SOUTH in local space; then rotate to entrance
        // Podium (stone base)
        int pad = 2;
        fillRect(blocks, origin, entrance, -pad, 0, -pad, baseW - 1 + pad, podiumH - 1, baseD - 1 + pad, foundation, null);
        // podium top trim ring
        ringRect(blocks, origin, entrance, -pad, podiumH, -pad, baseW - 1 + pad, podiumH, baseD - 1 + pad, roofSlab, null);

        // Entrance steps (local SOUTH side)
        int cx = baseW / 2;
        int zFront = baseD - 1 + pad;
        for (int dx = -2; dx <= 2; dx++) {
            BlockPos p = local(origin, entrance, cx + dx, podiumH, zFront + 1);
            blocks.add(new PlannedBlock(p, rotateState(withFacingIfPossible(roofStairs, Direction.NORTH), entrance)));
            blocks.add(new PlannedBlock(p.down(), rotateState(roofSlab, entrance)));
        }

        // Tiers
        int tierW = baseW;
        int tierD = baseD;
        int offX = 0;
        int offZ = 0;
        int tierBaseY = podiumH + 1;

        for (int t = 0; t < tiers; t++) {
            int x0 = offX;
            int z0 = offZ;
            int x1 = offX + tierW - 1;
            int z1 = offZ + tierD - 1;

            // floor
            fillRect(blocks, origin, entrance, x0, tierBaseY, z0, x1, tierBaseY, z1, floor, null);

            // walls + hollow interior
            int wallTop = tierBaseY + tierHeight;
            for (int yy = tierBaseY + 1; yy <= wallTop; yy++) {
                ringRect(blocks, origin, entrance, x0, yy, z0, x1, yy, z1, wall, null);
            }
            // corners/edge trims (vertical logs)
            for (int yy = tierBaseY + 1; yy <= wallTop; yy++) {
                put(blocks, origin, entrance, x0, yy, z0, trim, null);
                put(blocks, origin, entrance, x1, yy, z0, trim, null);
                put(blocks, origin, entrance, x0, yy, z1, trim, null);
                put(blocks, origin, entrance, x1, yy, z1, trim, null);
            }
            // interior air carve (keep 1-block shell)
            fillRect(blocks, origin, entrance, x0 + 1, tierBaseY + 1, z0 + 1, x1 - 1, wallTop, z1 - 1, Blocks.AIR.getDefaultState(), null);

            // windows (small, symmetric-ish) on midpoints, skip bottom tier front center
            int mx = (x0 + x1) / 2;
            int mz = (z0 + z1) / 2;
            int wy = tierBaseY + 2;
            if (wy <= wallTop - 1) {
                put(blocks, origin, entrance, mx, wy, z0, window, null); // north
                put(blocks, origin, entrance, mx, wy, z1, window, null); // south
                put(blocks, origin, entrance, x0, wy, mz, window, null); // west
                put(blocks, origin, entrance, x1, wy, mz, window, null); // east
            }

            // entrance opening at bottom tier (local SOUTH)
            if (t == 0) {
                int doorY0 = tierBaseY + 1;
                int doorY1 = tierBaseY + 3;
                int doorZ = z1;
                // clear a 2-wide door centered
                for (int yy = doorY0; yy <= doorY1; yy++) {
                    put(blocks, origin, entrance, mx, yy, doorZ, Blocks.AIR.getDefaultState(), null);
                    put(blocks, origin, entrance, mx - 1, yy, doorZ, Blocks.AIR.getDefaultState(), null);
                }
                // place a door (best-effort)
                placeDoubleDoor(blocks, origin, entrance, mx - 1, tierBaseY + 1, doorZ, Direction.SOUTH);
                // lanterns flanking entrance
                put(blocks, origin, entrance, mx - 3, tierBaseY + 3, doorZ + 1, lantern, null);
                put(blocks, origin, entrance, mx + 2, tierBaseY + 3, doorZ + 1, lantern, null);
            }

            // Eaves roof for this tier
            int roofBaseY = wallTop + 1;
            addHippedRoof(blocks, origin, entrance,
                    x0 - eavesOverhang, roofBaseY, z0 - eavesOverhang,
                    x1 + eavesOverhang, roofBaseY, z1 + eavesOverhang,
                    roofHeight, roofStairs, roofSlab, roofTile);

            tierBaseY = roofBaseY + roofHeight + 1;
            // taper next tier
            tierW = Math.max(9, tierW - taperStep);
            tierD = Math.max(9, tierD - taperStep);
            offX += taperStep / 2;
            offZ += taperStep / 2;
            if (tierW < 9 || tierD < 9) break;
        }

        String desc = "Japanese Castle Keep (tenshu) v1";
        return new GeneratedStructure(null, origin, desc, blocks);
    }

    private static void addHippedRoof(List<PlannedBlock> blocks, BlockPos origin, Direction entrance,
                                      int x0, int y0, int z0, int x1, int y1, int z1,
                                      int h, BlockState stairs, BlockState slab, BlockState cap) {
        if (h <= 0) return;
        int lx0 = x0, lz0 = z0, lx1 = x1, lz1 = z1;
        for (int i = 0; i < h; i++) {
            int y = y0 + i;
            // ring outline with inward-facing stairs
            for (int x = lx0; x <= lx1; x++) {
                put(blocks, origin, entrance, x, y, lz0, stairs, Direction.SOUTH); // north edge faces inward (south)
                put(blocks, origin, entrance, x, y, lz1, stairs, Direction.NORTH); // south edge faces inward (north)
            }
            for (int z = lz0; z <= lz1; z++) {
                put(blocks, origin, entrance, lx0, y, z, stairs, Direction.EAST);  // west edge faces inward (east)
                put(blocks, origin, entrance, lx1, y, z, stairs, Direction.WEST);  // east edge faces inward (west)
            }
            // shrink
            lx0 += 1; lz0 += 1; lx1 -= 1; lz1 -= 1;
            if (lx0 > lx1 || lz0 > lz1) break;
        }
        // cap the top with slabs/tiles
        int topY = y0 + h;
        if (lx0 <= lx1 && lz0 <= lz1) {
            for (int x = lx0; x <= lx1; x++) {
                for (int z = lz0; z <= lz1; z++) {
                    BlockState s = ((x == lx0 || x == lx1 || z == lz0 || z == lz1) ? slab : cap);
                    put(blocks, origin, entrance, x, topY, z, s, null);
                }
            }
        }
    }

    private static void placeDoubleDoor(List<PlannedBlock> blocks, BlockPos origin, Direction entrance,
                                        int x, int y, int z, Direction localFacingOut) {
        try {
            BlockState door = Blocks.SPRUCE_DOOR.getDefaultState();
            Direction f = rotateDir(localFacingOut, entrance);
            if (door.contains(Properties.HORIZONTAL_FACING)) door = door.with(Properties.HORIZONTAL_FACING, f);
            if (door.contains(Properties.DOUBLE_BLOCK_HALF)) {
                blocks.add(new PlannedBlock(local(origin, entrance, x, y, z), door.with(Properties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.LOWER)));
                blocks.add(new PlannedBlock(local(origin, entrance, x, y + 1, z), door.with(Properties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.UPPER)));
            } else if (door.getBlock() instanceof DoorBlock) {
                // fallback: just place lower as solid
                blocks.add(new PlannedBlock(local(origin, entrance, x, y, z), door));
            }
        } catch (Throwable ignored) {}
    }

    private static void fillRect(List<PlannedBlock> blocks, BlockPos origin, Direction entrance,
                                 int x0, int y0, int z0, int x1, int y1, int z1,
                                 BlockState state, Direction face) {
        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                for (int y = y0; y <= y1; y++) {
                    put(blocks, origin, entrance, x, y, z, state, face);
                }
            }
        }
    }

    private static void ringRect(List<PlannedBlock> blocks, BlockPos origin, Direction entrance,
                                 int x0, int y, int z0, int x1, int y1, int z1,
                                 BlockState state, Direction face) {
        // y and y1 are the same in current usage, but keep signature consistent
        int yy = y;
        for (int x = x0; x <= x1; x++) {
            put(blocks, origin, entrance, x, yy, z0, state, face);
            put(blocks, origin, entrance, x, yy, z1, state, face);
        }
        for (int z = z0; z <= z1; z++) {
            put(blocks, origin, entrance, x0, yy, z, state, face);
            put(blocks, origin, entrance, x1, yy, z, state, face);
        }
    }

    private static void put(List<PlannedBlock> blocks, BlockPos origin, Direction entrance,
                            int x, int y, int z, BlockState state, Direction face) {
        if (state == null) return;
        BlockState s = state;
        if (face != null) {
            s = withFacingIfPossible(s, rotateDir(face, entrance));
        } else {
            s = rotateState(s, entrance);
        }
        blocks.add(new PlannedBlock(local(origin, entrance, x, y, z), s));
    }

    /**
     * Convert local (x,y,z) to world pos by rotating around origin so local SOUTH aligns to entrance.
     */
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

    private static Direction rotateDir(Direction local, Direction entrance) {
        if (entrance == null || entrance == Direction.SOUTH || local == null) return local;
        if (!local.getAxis().isHorizontal()) return local;
        return switch (entrance) {
            case NORTH -> (local == Direction.NORTH) ? Direction.SOUTH
                    : (local == Direction.SOUTH) ? Direction.NORTH
                    : (local == Direction.EAST) ? Direction.WEST
                    : Direction.EAST;
            case EAST -> (local == Direction.SOUTH) ? Direction.EAST
                    : (local == Direction.NORTH) ? Direction.WEST
                    : (local == Direction.EAST) ? Direction.NORTH
                    : Direction.SOUTH;
            case WEST -> (local == Direction.SOUTH) ? Direction.WEST
                    : (local == Direction.NORTH) ? Direction.EAST
                    : (local == Direction.EAST) ? Direction.SOUTH
                    : Direction.NORTH;
            default -> local;
        };
    }

    private static BlockState rotateState(BlockState s, Direction entrance) {
        if (s == null) return null;
        BlockState out = s;
        try {
            if (out.contains(Properties.HORIZONTAL_FACING)) {
                Direction f = out.get(Properties.HORIZONTAL_FACING);
                out = out.with(Properties.HORIZONTAL_FACING, rotateDir(f, entrance));
            }
            if (out.contains(Properties.FACING)) {
                Direction f = out.get(Properties.FACING);
                out = out.with(Properties.FACING, rotateDir(f, entrance));
            }
            if (out.contains(Properties.AXIS)) {
                // rotating 90° swaps X/Z axes
                if (entrance == Direction.EAST || entrance == Direction.WEST) {
                    var axis = out.get(Properties.AXIS);
                    if (axis == Direction.Axis.X) out = out.with(Properties.AXIS, Direction.Axis.Z);
                    else if (axis == Direction.Axis.Z) out = out.with(Properties.AXIS, Direction.Axis.X);
                }
            }
        } catch (Throwable ignored) {}
        return out;
    }

    private static BlockState withFacingIfPossible(BlockState s, Direction facing) {
        if (s == null || facing == null) return s;
        try {
            if (s.contains(Properties.HORIZONTAL_FACING) && facing.getAxis().isHorizontal()) {
                return s.with(Properties.HORIZONTAL_FACING, facing);
            }
            if (s.contains(Properties.FACING)) {
                return s.with(Properties.FACING, facing);
            }
        } catch (Throwable ignored) {}
        return s;
    }

    private static Direction resolveEntranceFacing(BuildingSpec spec) {
        if (spec == null || spec.getExtra() == null) return Direction.SOUTH;
        try {
            Object layoutObj = spec.getExtra().get("layout");
            if (layoutObj instanceof Map<?, ?> m) {
                Object ef = m.get("entranceFacing");
                if (ef != null) {
                    String s = String.valueOf(ef).trim().toUpperCase();
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

    private static String getStringExtra(BuildingSpec spec, String key, String def) {
        if (spec == null || spec.getExtra() == null) return def;
        Object v = spec.getExtra().get(key);
        if (v == null) return def;
        String s = String.valueOf(v).trim();
        return s.isEmpty() ? def : s;
    }

    private static int getIntExtra(BuildingSpec spec, String key, int def) {
        if (spec == null || spec.getExtra() == null) return def;
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

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    private static int clampOdd(int v, int min, int max) {
        int x = clamp(v, min, max);
        return (x % 2 == 0) ? (x + 1 <= max ? x + 1 : x - 1) : x;
    }
}


