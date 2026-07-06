package com.formacraft.server.generation.structure;

import com.formacraft.common.build.GeneratedStructure;
import com.formacraft.common.build.PlannedBlock;
import com.formacraft.common.model.build.BuildingSpec;
import com.formacraft.common.model.build.BuildingStyle;
import com.formacraft.common.style.profile.DetailPreferences;
import com.formacraft.common.style.profile.StyleProfile;
import com.formacraft.common.style.profile.StyleProfileRegistry;
import com.formacraft.server.generation.structure.util.ChineseLandmarkDetailUtil;
import com.formacraft.server.generation.structure.util.StructureSpecParsers;
import com.formacraft.server.material.PaletteResolver;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.state.property.Properties;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.util.ArrayList;
import java.util.List;

/**
 * FoguangTempleHallGenerator：佛光寺东大殿（唐代木构佛殿强原型）专用生成器（v3 / P3）
 *
 * P3: 铺作分区（柱头/跳/枋/撩檐）、副阶内檐。
 */
public class FoguangTempleHallGenerator implements StructureGenerator {

    @Override
    public GeneratedStructure generate(BuildingSpec spec, BlockPos origin, ServerWorld world) {
        List<PlannedBlock> blocks = new ArrayList<>(8000);

        BuildingStyle style = (spec != null && spec.getStyle() != null) ? spec.getStyle() : BuildingStyle.ASIAN;
        StyleProfile profile = (spec != null) ? StyleProfileRegistry.resolve(spec) : StyleProfileRegistry.forStyle(style);
        DetailPreferences details = profile != null ? profile.details() : null;

        String paletteId = StructureSpecParsers.extraString(spec, "paletteId", null);
        if ((paletteId == null || paletteId.isBlank()) && details != null && details.paletteId != null && !details.paletteId.isBlank()) {
            paletteId = details.paletteId.trim();
        }

        Direction entrance = StructureSpecParsers.resolveEntranceFacing(spec, Direction.SOUTH);

        int baysX = clamp(StructureSpecParsers.extraInt(spec, "baysX", 7), 5, 9);
        int baysZ = clamp(StructureSpecParsers.extraInt(spec, "baysZ", 4), 3, 5);
        int bayWidth = clamp(StructureSpecParsers.extraInt(spec, "bayWidth", 3), 2, 4);
        int w = clampOdd(StructureSpecParsers.extraInt(spec, "width", baysX * bayWidth), 15, 35);
        int d = clampOdd(StructureSpecParsers.extraInt(spec, "depth", baysZ * bayWidth), 11, 25);
        int platformH = clamp(StructureSpecParsers.extraInt(spec, "platformHeight", 2), 1, 4);
        int bodyH = clamp(StructureSpecParsers.extraInt(spec, "hallHeight", 7), 5, 12);
        boolean subEaves = StructureSpecParsers.extraBool(spec, "includeSubEaves", true);
        int subDepth = subEaves ? clamp(StructureSpecParsers.extraInt(spec, "subEavesDepth", 3), 2, 5) : 0;

        int outerW = w + subDepth * 2;
        int outerD = d + subDepth * 2;
        int ox = subDepth;
        int oz = subDepth;

        BlockState stone = Blocks.STONE_BRICKS.getDefaultState();
        BlockState pillar = Blocks.DARK_OAK_LOG.getDefaultState();
        BlockState wall = Blocks.RED_TERRACOTTA.getDefaultState();
        BlockState trim = Blocks.DARK_OAK_PLANKS.getDefaultState();
        BlockState bracket = Blocks.SPRUCE_STAIRS.getDefaultState();
        BlockState paint = Blocks.GREEN_TERRACOTTA.getDefaultState();
        BlockState lattice = Blocks.IRON_BARS.getDefaultState();
        BlockState roofTile = Blocks.DEEPSLATE_TILES.getDefaultState();
        BlockState roofStairs = Blocks.DEEPSLATE_BRICK_STAIRS.getDefaultState();
        BlockState roofSlab = Blocks.DEEPSLATE_TILE_SLAB.getDefaultState();

        if (paletteId != null && !paletteId.isBlank()) {
            stone = PaletteResolver.pick(world, paletteId, "WALL_FOUNDATION", origin, 0xF00001L, stone);
            pillar = PaletteResolver.pick(world, paletteId, "PILLAR", origin, 0xF00002L, pillar);
            wall = PaletteResolver.pick(world, paletteId, "WALL_BASE", origin, 0xF00003L, wall);
            trim = PaletteResolver.pick(world, paletteId, "DECOR_DETAIL", origin, 0xF00004L, trim);
            bracket = PaletteResolver.pick(world, paletteId, "ROOF_SLOPE", origin, 0xF00008L, bracket);
            roofTile = PaletteResolver.pick(world, paletteId, "ROOF_TILE", origin, 0xF00005L, roofTile);
            roofStairs = PaletteResolver.pick(world, paletteId, "ROOF_SLOPE", origin, 0xF00006L, roofStairs);
            roofSlab = PaletteResolver.pick(world, paletteId, "FLOOR_SLAB", origin, 0xF00007L, roofSlab);
        }

        int clearH = platformH + bodyH + 14;
        for (int x = -2; x <= outerW + 1; x++) {
            for (int z = -2; z <= outerD + 1; z++) {
                for (int y = 0; y <= clearH; y++) {
                    put(blocks, origin, entrance, x, y, z, Blocks.AIR.getDefaultState());
                }
            }
        }

        fillRect(blocks, origin, entrance, 0, 0, 0, outerW - 1, platformH - 1, outerD - 1, stone);
        ringRect(blocks, origin, entrance, 0, platformH, 0, outerW - 1, platformH, outerD - 1, trim);

        int yCol0 = platformH + 1;
        int yCol1 = yCol0 + bodyH - 2;
        int yWallTop = yCol0 + bodyH - 1;
        int bodyTop = platformH + bodyH;

        placeBayColumns(blocks, origin, entrance, ox, oz, w, d, bayWidth, yCol0, yCol1, pillar, trim);
        if (subEaves) {
            placePerimeterColumns(blocks, origin, entrance, 0, 0, outerW - 1, outerD - 1, bayWidth, yCol0, yCol1 - 1, pillar, trim);
            addSubEavesBeams(blocks, origin, entrance, ox, oz, ox + w, oz + d, outerW - 1, outerD - 1, yCol1, trim);
        }

        fillWallsBetweenColumns(blocks, origin, entrance, ox, oz, w, d, bayWidth, yCol0, yWallTop, wall);
        addLatticeWindows(blocks, origin, entrance, ox, oz, w, d, bayWidth, yCol0, yWallTop, trim, lattice);
        carveMainEntrance(blocks, origin, entrance, ox, oz, w, d, bayWidth, yCol0, yWallTop);

        addPuzuoAtColumns(blocks, origin, entrance, ox, oz, ox + w, oz + d, bayWidth, yCol1 + 1, false, trim, bracket, trim, paint);
        if (subEaves) {
            addPuzuoAtColumns(blocks, origin, entrance, 0, 0, outerW - 1, outerD - 1, bayWidth, yCol1, true, trim, bracket, trim, paint);
            int innerRoofY = yCol1 + 3;
            ChineseLandmarkDetailUtil.addSubEavesInnerRoof(
                    (pos, state) -> put(blocks, origin, entrance, pos.getX(), pos.getY(), pos.getZ(), state),
                    ox, oz, ox + w, oz + d,
                    0, 0, outerW - 1, outerD - 1,
                    innerRoofY, roofSlab, roofStairs
            );
        }

        List<PlannedBlock> roofBlocks = new ArrayList<>();
        BlockPos localRoofOrigin = new BlockPos(0, bodyTop, 0);
        HouseRoofGenerator.generateRoof(
                roofBlocks,
                localRoofOrigin,
                w,
                d,
                bodyTop,
                "xie_shan",
                roofTile,
                roofStairs,
                roofSlab,
                trim,
                BuildingStyle.ASIAN,
                profile,
                Direction.SOUTH,
                spec,
                paletteId
        );
        for (PlannedBlock pb : roofBlocks) {
            put(blocks, origin, entrance, ox + pb.getPos().getX(), pb.getPos().getY(), oz + pb.getPos().getZ(), pb.getTargetState());
        }

        addTerraceSteps(blocks, origin, entrance, ox, oz, w, d, platformH, roofStairs);

        String desc = String.format(
                "FoguangTempleHall v3 (puzuo zones, sub-eaves inner roof, %d×%d bays, w=%d d=%d, subEaves=%s)",
                baysX, baysZ, w, d, subEaves
        );
        return new GeneratedStructure(null, origin, desc, blocks);
    }

    private static void addSubEavesBeams(List<PlannedBlock> blocks, BlockPos origin, Direction entrance,
                                         int ix0, int iz0, int ix1, int iz1, int ox1, int oz1, int y, BlockState beam) {
        ringRect(blocks, origin, entrance, ix0 - 1, y, iz0 - 1, ix1 + 1, y, iz1 + 1, beam);
        ringRect(blocks, origin, entrance, 0, y, 0, ox1, y, oz1, beam);
    }

    private static void addLatticeWindows(List<PlannedBlock> blocks, BlockPos origin, Direction entrance,
                                          int x0, int z0, int w, int d, int spacing, int y0, int yTop,
                                          BlockState frame, BlockState infill) {
        int wy0 = y0 + 2;
        int wy1 = Math.min(yTop - 1, y0 + 4);
        for (int x = spacing; x < w; x += spacing) {
            ChineseLandmarkDetailUtil.addLatticeWindow(
                    (pos, state) -> put(blocks, origin, entrance, pos.getX(), pos.getY(), pos.getZ(), state),
                    x0 + x, x0 + x + spacing - 1, wy0, wy1, z0, frame, infill
            );
            ChineseLandmarkDetailUtil.addLatticeWindow(
                    (pos, state) -> put(blocks, origin, entrance, pos.getX(), pos.getY(), pos.getZ(), state),
                    x0 + x, x0 + x + spacing - 1, wy0, wy1, z0 + d, frame, infill
            );
        }
    }

    private static void carveMainEntrance(List<PlannedBlock> blocks, BlockPos origin, Direction entrance,
                                          int x0, int z0, int w, int d, int spacing, int y0, int yTop) {
        int midBay = (w / spacing) / 2 * spacing;
        int doorX = x0 + midBay + 1;
        int doorZ = z0 + d;
        for (int y = y0; y <= yTop; y++) {
            for (int dx = 0; dx < spacing - 1; dx++) {
                put(blocks, origin, entrance, doorX + dx, y, doorZ, Blocks.AIR.getDefaultState());
            }
        }
        put(blocks, origin, entrance, doorX, y0, doorZ, Blocks.OAK_DOOR.getDefaultState());
    }

    private static void addPuzuoAtColumns(List<PlannedBlock> blocks, BlockPos origin, Direction entrance,
                                          int x0, int z0, int x1, int z1, int spacing,
                                          int y, boolean subEaves,
                                          BlockState luDou, BlockState gong, BlockState fang, BlockState paint) {
        for (int[] col : ChineseLandmarkDetailUtil.perimeterColumnPositions(x0, z0, x1, z1, spacing)) {
            int x = col[0];
            int z = col[1];
            boolean corner = ChineseLandmarkDetailUtil.isCornerColumn(x, z, x0, z0, x1, z1);
            Direction out = ChineseLandmarkDetailUtil.outwardFromRectEdge(x, z, x0, z0, x1, z1);
            Direction along = ChineseLandmarkDetailUtil.alongWallFromEdge(x, z, x0, z0, x1, z1);
            ChineseLandmarkDetailUtil.PuzuoProfile profile =
                    ChineseLandmarkDetailUtil.resolvePuzuoProfile(subEaves, corner);
            ChineseLandmarkDetailUtil.addPuzuoZoning(
                    (pos, state) -> put(blocks, origin, entrance, pos.getX(), pos.getY(), pos.getZ(), state),
                    x, y, z, out, along, profile, luDou, gong, fang, paint
            );
        }
    }

    private static void addTerraceSteps(List<PlannedBlock> blocks, BlockPos origin, Direction entrance,
                                          int ox, int oz, int w, int d, int platformH, BlockState stair) {
        int mx = ox + w / 2;
        for (int tier = 0; tier < 3; tier++) {
            int z = oz + d + 1 + tier;
            int y = platformH - tier;
            if (y < 0) break;
            for (int dx = -3 + tier; dx <= 3 - tier; dx++) {
                put(blocks, origin, entrance, mx + dx, y, z, withFacing(stair, rotateDir(Direction.NORTH, entrance)));
            }
        }
    }

    private static void placeBayColumns(List<PlannedBlock> blocks, BlockPos origin, Direction entrance,
                                        int x0, int z0, int w, int d, int spacing,
                                        int y0, int y1, BlockState pillar, BlockState capital) {
        for (int x = 0; x <= w; x += spacing) {
            placeColumn(blocks, origin, entrance, x0 + x, y0, z0, y1, pillar, capital);
            placeColumn(blocks, origin, entrance, x0 + x, y0, z0 + d, y1, pillar, capital);
        }
        for (int z = spacing; z < d; z += spacing) {
            placeColumn(blocks, origin, entrance, x0, y0, z0 + z, y1, pillar, capital);
            placeColumn(blocks, origin, entrance, x0 + w, y0, z0 + z, y1, pillar, capital);
        }
    }

    private static void placePerimeterColumns(List<PlannedBlock> blocks, BlockPos origin, Direction entrance,
                                              int x0, int z0, int x1, int z1, int spacing,
                                              int y0, int y1, BlockState pillar, BlockState capital) {
        for (int x = x0; x <= x1; x += spacing) {
            placeColumn(blocks, origin, entrance, x, y0, z0, y1, pillar, capital);
            placeColumn(blocks, origin, entrance, x, y0, z1, y1, pillar, capital);
        }
        for (int z = z0 + spacing; z < z1; z += spacing) {
            placeColumn(blocks, origin, entrance, x0, y0, z, y1, pillar, capital);
            placeColumn(blocks, origin, entrance, x1, y0, z, y1, pillar, capital);
        }
    }

    private static void fillWallsBetweenColumns(List<PlannedBlock> blocks, BlockPos origin, Direction entrance,
                                                int x0, int z0, int w, int d, int spacing,
                                                int y0, int yTop, BlockState wall) {
        for (int y = y0; y <= yTop; y++) {
            for (int x = spacing; x < w; x += spacing) {
                for (int t = 1; t < spacing - 1; t++) {
                    put(blocks, origin, entrance, x0 + x + t, y, z0, wall);
                    put(blocks, origin, entrance, x0 + x + t, y, z0 + d, wall);
                }
            }
            for (int z = spacing; z < d; z += spacing) {
                for (int t = 1; t < spacing - 1; t++) {
                    put(blocks, origin, entrance, x0, y, z0 + z + t, wall);
                    put(blocks, origin, entrance, x0 + w, y, z0 + z + t, wall);
                }
            }
        }
    }

    private static void placeColumn(List<PlannedBlock> blocks, BlockPos origin, Direction entrance,
                                    int x, int y0, int z, int y1,
                                    BlockState pillar, BlockState capital) {
        for (int y = y0; y <= y1; y++) {
            put(blocks, origin, entrance, x, y, z, pillar);
        }
        put(blocks, origin, entrance, x, y1 + 1, z, capital);
    }

    private static void fillRect(List<PlannedBlock> blocks, BlockPos origin, Direction entrance,
                                 int x0, int y0, int z0, int x1, int y1, int z1, BlockState s) {
        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                for (int y = y0; y <= y1; y++) {
                    put(blocks, origin, entrance, x, y, z, s);
                }
            }
        }
    }

    private static void ringRect(List<PlannedBlock> blocks, BlockPos origin, Direction entrance,
                                 int x0, int y, int z0, int x1, int y1, int z1, BlockState s) {
        for (int x = x0; x <= x1; x++) {
            put(blocks, origin, entrance, x, y, z0, s);
            put(blocks, origin, entrance, x, y, z1, s);
        }
        for (int z = z0; z <= z1; z++) {
            put(blocks, origin, entrance, x0, y, z, s);
            put(blocks, origin, entrance, x1, y, z, s);
        }
    }

    private static void put(List<PlannedBlock> blocks, BlockPos origin, Direction entrance, int x, int y, int z, BlockState state) {
        blocks.add(new PlannedBlock(rotate(origin.add(x, y, z), origin, entrance), state));
    }

    private static BlockPos rotate(BlockPos local, BlockPos origin, Direction entrance) {
        int lx = local.getX() - origin.getX();
        int ly = local.getY() - origin.getY();
        int lz = local.getZ() - origin.getZ();
        if (entrance == null || entrance == Direction.SOUTH) {
            return origin.add(lx, ly, lz);
        }
        return switch (entrance) {
            case NORTH -> origin.add(-lx, ly, -lz);
            case EAST -> origin.add(lz, ly, -lx);
            case WEST -> origin.add(-lz, ly, lx);
            default -> origin.add(lx, ly, lz);
        };
    }

    private static Direction rotateDir(Direction local, Direction entrance) {
        if (entrance == null || entrance == Direction.SOUTH || local == null) return local;
        if (!local.getAxis().isHorizontal()) return local;
        return switch (entrance) {
            case NORTH -> switch (local) {
                case NORTH -> Direction.SOUTH;
                case SOUTH -> Direction.NORTH;
                case EAST -> Direction.WEST;
                default -> Direction.EAST;
            };
            case EAST -> switch (local) {
                case SOUTH -> Direction.EAST;
                case NORTH -> Direction.WEST;
                case EAST -> Direction.NORTH;
                default -> Direction.SOUTH;
            };
            case WEST -> switch (local) {
                case SOUTH -> Direction.WEST;
                case NORTH -> Direction.EAST;
                case EAST -> Direction.SOUTH;
                default -> Direction.NORTH;
            };
            default -> local;
        };
    }

    private static BlockState withFacing(BlockState state, Direction facing) {
        if (state != null && state.contains(Properties.HORIZONTAL_FACING)) {
            return state.with(Properties.HORIZONTAL_FACING, facing);
        }
        return state;
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    private static int clampOdd(int v, int min, int max) {
        v = clamp(v, min, max);
        if (v % 2 == 0) v += 1;
        return clamp(v, min, max);
    }
}
