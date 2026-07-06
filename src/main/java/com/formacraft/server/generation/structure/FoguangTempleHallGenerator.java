package com.formacraft.server.generation.structure;

import com.formacraft.common.build.GeneratedStructure;
import com.formacraft.common.build.PlannedBlock;
import com.formacraft.common.model.build.BuildingSpec;
import com.formacraft.common.model.build.BuildingStyle;
import com.formacraft.common.style.profile.DetailPreferences;
import com.formacraft.common.style.profile.StyleProfile;
import com.formacraft.common.style.profile.StyleProfileRegistry;
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
 * FoguangTempleHallGenerator：佛光寺东大殿（唐代木构佛殿强原型）专用生成器（v1）
 *
 * 识别性目标：
 * - 七开间 × 四进深柱网
 * - 石台基 + 副阶周匝（可选）
 * - 单檐庑殿/歇山顶大出檐 + 檐下斗拱意象
 */
public class FoguangTempleHallGenerator implements StructureGenerator {

    @Override
    public GeneratedStructure generate(BuildingSpec spec, BlockPos origin, ServerWorld world) {
        List<PlannedBlock> blocks = new ArrayList<>(6000);

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
        BlockState roofTile = Blocks.DEEPSLATE_TILES.getDefaultState();
        BlockState roofStairs = Blocks.DEEPSLATE_BRICK_STAIRS.getDefaultState();
        BlockState roofSlab = Blocks.DEEPSLATE_TILE_SLAB.getDefaultState();

        if (paletteId != null && !paletteId.isBlank()) {
            stone = PaletteResolver.pick(world, paletteId, "WALL_FOUNDATION", origin, 0xF00001L, stone);
            pillar = PaletteResolver.pick(world, paletteId, "PILLAR", origin, 0xF00002L, pillar);
            wall = PaletteResolver.pick(world, paletteId, "WALL_BASE", origin, 0xF00003L, wall);
            trim = PaletteResolver.pick(world, paletteId, "DECOR_DETAIL", origin, 0xF00004L, trim);
            roofTile = PaletteResolver.pick(world, paletteId, "ROOF_TILE", origin, 0xF00005L, roofTile);
            roofStairs = PaletteResolver.pick(world, paletteId, "ROOF_SLOPE", origin, 0xF00006L, roofStairs);
            roofSlab = PaletteResolver.pick(world, paletteId, "FLOOR_SLAB", origin, 0xF00007L, roofSlab);
        }

        int clearH = platformH + bodyH + 12;
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

        placeBayColumns(blocks, origin, entrance, ox, oz, w, d, bayWidth, yCol0, yCol1, pillar, trim);
        if (subEaves) {
            placePerimeterColumns(blocks, origin, entrance, 0, 0, outerW - 1, outerD - 1, bayWidth, yCol0, yCol1 - 1, pillar, trim);
        }

        fillWallsBetweenColumns(blocks, origin, entrance, ox, oz, w, d, bayWidth, yCol0, yWallTop, wall);

        int bodyTop = platformH + bodyH;
        addDougongBand(blocks, origin, entrance, ox - 1, oz - 1, ox + w, oz + d, bodyTop - 1, trim);

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
            int lx = pb.getPos().getX();
            int lz = pb.getPos().getZ();
            put(blocks, origin, entrance, ox + lx, pb.getPos().getY(), oz + lz, pb.getTargetState());
        }

        int mx = ox + w / 2;
        for (int dx = -2; dx <= 2; dx++) {
            put(blocks, origin, entrance, mx + dx, platformH, oz + d, withFacing(roofStairs, rotateDir(Direction.NORTH, entrance)));
        }

        String desc = String.format(
                "FoguangTempleHall (Tang timber, %d×%d bays, w=%d d=%d, subEaves=%s)",
                baysX, baysZ, w, d, subEaves
        );
        return new GeneratedStructure(null, origin, desc, blocks);
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

    private static void addDougongBand(List<PlannedBlock> blocks, BlockPos origin, Direction entrance,
                                       int x0, int z0, int x1, int z1, int y, BlockState trim) {
        for (int x = x0; x <= x1; x++) {
            put(blocks, origin, entrance, x, y, z0, trim);
            put(blocks, origin, entrance, x, y, z1, trim);
        }
        for (int z = z0; z <= z1; z++) {
            put(blocks, origin, entrance, x0, y, z, trim);
            put(blocks, origin, entrance, x1, y, z, trim);
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
