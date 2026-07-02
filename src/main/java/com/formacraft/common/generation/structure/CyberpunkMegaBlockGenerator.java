package com.formacraft.common.generation.structure;

import com.formacraft.common.generation.structure.util.StructureSpecParsers;
import com.formacraft.common.logging.FcaLog;
import com.formacraft.common.model.build.BuildingSpec;
import com.formacraft.common.model.build.BuildingStyle;
import com.formacraft.common.model.build.Footprint;
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
 * CyberpunkMegaBlockGenerator (v1)
 *
 * 识别性目标（P0）：
 * - 高密度巨构：底部“破败拥挤”的贫民窟基座（杂乱外凸盒子/阳台/灯带）
 * - 顶部“高科技”：一到两根塔楼（Curtain wall + 内部灯）
 * - 光污染：高密度霓虹灯带 + 全息广告牌（ROAD_SIGNAGE + 玻璃叠层）
 *
 * 强原型入口：用 template 触发，避免把所有 Cyberpunk 建筑都路由到该生成器。
 */
public class CyberpunkMegaBlockGenerator implements StructureGenerator {

    private static final FcaLog LOG = FcaLog.of("CyberpunkMegaBlockGenerator");

    @Override
    public GeneratedStructure generate(BuildingSpec spec, BlockPos origin, ServerWorld world) {
        List<PlannedBlock> blocks = new ArrayList<>();

        Footprint fp = (spec != null) ? spec.getFootprint() : null;
        int w = clamp(getIntExtra(spec, "width", fp != null ? fp.getWidth() : 39), 25, 95);
        int d = clamp(getIntExtra(spec, "depth", fp != null ? fp.getDepth() : 49), 25, 129);
        int baseH = clamp(getIntExtra(spec, "baseHeight", 14), 10, 28);
        int towerH = clamp(getIntExtra(spec, "towerHeight", 34), 18, 88);
        int towerCount = clamp(getIntExtra(spec, "towers", 1), 1, 2);
        int slumPods = clamp(getIntExtra(spec, "slumPods", Math.max(18, (w + d) / 2)), 8, 120);

        BuildingStyle style = (spec != null && spec.getStyle() != null) ? spec.getStyle() : BuildingStyle.MODERN;
        StyleProfile profile = (spec != null) ? StyleProfileRegistry.resolve(spec) : StyleProfileRegistry.forStyle(style);
        DetailPreferences details = profile != null ? profile.details() : null;
        String paletteId = (spec != null && spec.getExtra() != null && spec.getExtra().get("paletteId") != null)
                ? String.valueOf(spec.getExtra().get("paletteId")).trim()
                : null;
        if ((paletteId == null || paletteId.isBlank()) && details != null && details.paletteId != null && !details.paletteId.isBlank()) {
            paletteId = details.paletteId.trim();
        }

        Direction facing = resolveEntranceFacing(spec);

        // Materials (semantic first)
        BlockState wall = Blocks.BLACK_CONCRETE.getDefaultState();
        BlockState foundation = Blocks.POLISHED_DEEPSLATE.getDefaultState();
        BlockState floor = Blocks.GRAY_CONCRETE.getDefaultState();
        BlockState slab = Blocks.SMOOTH_STONE_SLAB.getDefaultState();
        BlockState frame = Blocks.LIGHT_GRAY_CONCRETE.getDefaultState();
        BlockState glass = Blocks.CYAN_STAINED_GLASS.getDefaultState();
        BlockState signage = Blocks.CYAN_STAINED_GLASS.getDefaultState();
        BlockState light = Blocks.SEA_LANTERN.getDefaultState();
        BlockState internal = Blocks.SEA_LANTERN.getDefaultState();
        BlockState decor = Blocks.END_ROD.getDefaultState();
        BlockState rail = Blocks.IRON_BARS.getDefaultState();

        if (paletteId != null && !paletteId.isBlank() && world != null) {
            wall = PaletteResolver.pick(world, paletteId, "WALL_BASE", origin, 0xC8B0001L, wall);
            foundation = PaletteResolver.pick(world, paletteId, "WALL_FOUNDATION", origin, 0xC8B0002L, foundation);
            floor = PaletteResolver.pick(world, paletteId, "FLOORING", origin, 0xC8B0003L, floor);
            slab = PaletteResolver.pick(world, paletteId, "FLOOR_SLAB", origin, 0xC8B0004L, slab);
            frame = PaletteResolver.pick(world, paletteId, "FRAME", origin, 0xC8B0005L, frame);
            glass = PaletteResolver.pick(world, paletteId, "FACADE_CURTAIN", origin, 0xC8B0006L, glass);
            glass = PaletteResolver.pick(world, paletteId, "WINDOW", origin, 0xC8B0007L, glass);
            signage = PaletteResolver.pick(world, paletteId, "ROAD_SIGNAGE", origin, 0xC8B0008L, signage);
            signage = PaletteResolver.pick(world, paletteId, "DECOR_DETAIL", origin, 0xC8B0009L, signage);
            light = PaletteResolver.pick(world, paletteId, "ROAD_LIGHT", origin, 0xC8B000AL, light);
            light = PaletteResolver.pick(world, paletteId, "LIGHTING", origin, 0xC8B000BL, light);
            internal = PaletteResolver.pick(world, paletteId, "INTERNAL_LIGHT", origin, 0xC8B000CL, internal);
            internal = PaletteResolver.pick(world, paletteId, "LIGHTING", origin, 0xC8B000DL, internal);
            decor = PaletteResolver.pick(world, paletteId, "DECOR_DETAIL", origin, 0xC8B000EL, decor);
            rail = PaletteResolver.pick(world, paletteId, "BRIDGE_RAIL", origin, 0xC8B000FL, rail);
            rail = PaletteResolver.pick(world, paletteId, "ROAD_BORDER", origin, 0xC8B0010L, rail);
        }

        int x0 = -w / 2;
        int z0 = 0;

        // --- 1) Base slab + foundation under corners
        for (int x = 0; x < w; x++) {
            for (int z = 0; z < d; z++) {
                blocks.add(new PlannedBlock(rotateLocal(origin, x0 + x, 0, z0 + z, facing), floor));
                if (((x == 0 || x == w - 1) && (z % 8 == 0)) || ((z == 0 || z == d - 1) && (x % 8 == 0))) {
                    for (int y = -1; y >= -3; y--) {
                        blocks.add(new PlannedBlock(rotateLocal(origin, x0 + x, y, z0 + z, facing), foundation));
                    }
                }
            }
        }

        // --- 2) Base shell (slum podium)
        for (int y = 1; y <= baseH; y++) {
            for (int x = 0; x < w; x++) {
                for (int z : new int[]{0, d - 1}) {
                    BlockState s = wall;
                    // sparse “broken” gaps at low levels
                    if (y <= 3 && (hash01(x, y, z) < 0.10)) s = Blocks.AIR.getDefaultState();
                    blocks.add(new PlannedBlock(rotateLocal(origin, x0 + x, y, z0 + z, facing), s));
                }
            }
            for (int z = 0; z < d; z++) {
                for (int x : new int[]{0, w - 1}) {
                    BlockState s = wall;
                    if (y <= 3 && (hash01(x, y, z) < 0.10)) s = Blocks.AIR.getDefaultState();
                    blocks.add(new PlannedBlock(rotateLocal(origin, x0 + x, y, z0 + z, facing), s));
                }
            }
        }

        // --- 3) Neon strip around base roofline
        int roofY = baseH + 1;
        for (int x = 0; x < w; x += 2) {
            blocks.add(new PlannedBlock(rotateLocal(origin, x0 + x, roofY, z0 + 0, facing), light));
            blocks.add(new PlannedBlock(rotateLocal(origin, x0 + x, roofY, z0 + d - 1, facing), light));
        }
        for (int z = 0; z < d; z += 2) {
            blocks.add(new PlannedBlock(rotateLocal(origin, x0 + 0, roofY, z0 + z, facing), light));
            blocks.add(new PlannedBlock(rotateLocal(origin, x0 + w - 1, roofY, z0 + z, facing), light));
        }

        // --- 4) Slum pods (random extrusions) on the lower perimeter
        for (int i = 0; i < slumPods; i++) {
            int side = (int) Math.floor(hash01(i, w, d) * 4.0);
            int podW = 3 + ((int) Math.floor(hash01(i, 7, 11) * 3.0)); // 3..5
            int podD = 3 + ((int) Math.floor(hash01(i, 13, 17) * 3.0));
            int podH = 2 + ((int) Math.floor(hash01(i, 19, 23) * 3.0)); // 2..4
            int y = 1 + ((int) Math.floor(hash01(i, 29, 31) * 3.0)); // 1..3
            int x, z;
            int out = 2 + (i % 2); // extrusion depth
            if (side == 0) { // north (z=0)
                x = 2 + (int) Math.floor(hash01(i, 37, 41) * (w - podW - 4));
                z = -out;
            } else if (side == 1) { // south (z=d-1)
                x = 2 + (int) Math.floor(hash01(i, 43, 47) * (w - podW - 4));
                z = d - 1 + out - podD + 1;
            } else if (side == 2) { // west (x=0)
                z = 2 + (int) Math.floor(hash01(i, 53, 59) * (d - podD - 4));
                x = -out;
            } else { // east (x=w-1)
                z = 2 + (int) Math.floor(hash01(i, 61, 67) * (d - podD - 4));
                x = w - 1 + out - podW + 1;
            }
            placePod(blocks, origin, facing, x0 + x, y, z0 + z, podW, podH, podD, wall, frame, glass, light, signage);
        }

        // --- 5) Billboards on base (hologram-ish layered glass)
        for (int z = 6; z < d - 6; z += 9) {
            placeBillboard(blocks, origin, facing, x0 + 0, 4, z0 + z, Direction.WEST, signage, light);
            placeBillboard(blocks, origin, facing, x0 + (w - 1), 4, z0 + z, Direction.EAST, signage, light);
        }

        // --- 6) Towers on top (curtain wall + internal lights)
        for (int t = 0; t < towerCount; t++) {
            int tw = clamp((w / 2) - t * 6, 13, w - 10);
            int td = clamp((d / 2) - t * 8, 13, d - 10);
            int ox = x0 + (w - tw) - 2 - (t * 3); // offset to one corner
            int oz = z0 + (d - td) - 2 - (t * 5);
            buildTower(blocks, origin, facing, ox, baseH + 2, oz, tw, td, towerH - t * 8, frame, glass, slab, internal, light, signage);
        }

        // --- 7) Entrance cut on facing side at base
        int cx = x0 + w / 2;
        int dz = z0 + d - 1; // local front = +Z
        for (int x = cx - 1; x <= cx + 1; x++) {
            blocks.add(new PlannedBlock(rotateLocal(origin, x, 1, dz, facing), Blocks.AIR.getDefaultState()));
            blocks.add(new PlannedBlock(rotateLocal(origin, x, 2, dz, facing), Blocks.AIR.getDefaultState()));
        }
        // neon frame above door
        for (int x = cx - 2; x <= cx + 2; x++) {
            blocks.add(new PlannedBlock(rotateLocal(origin, x, 3, dz, facing), light));
        }
        // a couple of street-level lights
        blocks.add(new PlannedBlock(rotateLocal(origin, cx - 3, 2, dz - 1, facing), light));
        blocks.add(new PlannedBlock(rotateLocal(origin, cx + 3, 2, dz - 1, facing), light));

        String desc = String.format("CyberpunkMegaBlock (w=%d,d=%d,baseH=%d,towerH=%d,towers=%d)", w, d, baseH, towerH, towerCount);
        return new GeneratedStructure(null, origin, desc, blocks);
    }

    private static void buildTower(List<PlannedBlock> blocks,
                                   BlockPos origin,
                                   Direction facing,
                                   int ox,
                                   int oy,
                                   int oz,
                                   int w,
                                   int d,
                                   int h,
                                   BlockState frame,
                                   BlockState glass,
                                   BlockState slab,
                                   BlockState internal,
                                   BlockState light,
                                   BlockState signage) {
        // shell
        for (int y = 0; y <= h; y++) {
            boolean roof = (y == h);
            for (int x = 0; x < w; x++) {
                for (int z : new int[]{0, d - 1}) {
                    BlockPos p = rotateLocal(origin, ox + x, oy + y, oz + z, facing);
                    blocks.add(new PlannedBlock(p, roof ? slab : glass));
                    if ((x % 6 == 0) || (y % 5 == 0)) blocks.add(new PlannedBlock(p, frame));
                }
            }
            for (int z = 0; z < d; z++) {
                for (int x : new int[]{0, w - 1}) {
                    BlockPos p = rotateLocal(origin, ox + x, oy + y, oz + z, facing);
                    blocks.add(new PlannedBlock(p, roof ? slab : glass));
                    if ((z % 6 == 0) || (y % 5 == 0)) blocks.add(new PlannedBlock(p, frame));
                }
            }
        }
        // roof neon ring
        for (int x = 0; x < w; x += 2) {
            blocks.add(new PlannedBlock(rotateLocal(origin, ox + x, oy + h + 1, oz, facing), light));
            blocks.add(new PlannedBlock(rotateLocal(origin, ox + x, oy + h + 1, oz + d - 1, facing), light));
        }
        for (int z = 0; z < d; z += 2) {
            blocks.add(new PlannedBlock(rotateLocal(origin, ox, oy + h + 1, oz + z, facing), light));
            blocks.add(new PlannedBlock(rotateLocal(origin, ox + w - 1, oy + h + 1, oz + z, facing), light));
        }
        // internal lights
        for (int y = 2; y < h; y += 4) {
            for (int x = 2; x < w - 2; x += 5) {
                for (int z = 2; z < d - 2; z += 5) {
                    if (hash01(ox + x, oy + y, oz + z) < 0.55) {
                        blocks.add(new PlannedBlock(rotateLocal(origin, ox + x, oy + y, oz + z, facing), internal));
                    }
                }
            }
        }
        // a couple of mid-height billboards
        int by = oy + Math.max(6, h / 2);
        placeBillboard(blocks, origin, facing, ox + (w - 1), by, oz + (d / 3), Direction.EAST, signage, light);
        placeBillboard(blocks, origin, facing, ox, by, oz + (2 * d / 3), Direction.WEST, signage, light);
    }

    private static void placePod(List<PlannedBlock> blocks,
                                 BlockPos origin,
                                 Direction facing,
                                 int ox,
                                 int oy,
                                 int oz,
                                 int w,
                                 int h,
                                 int d,
                                 BlockState wall,
                                 BlockState frame,
                                 BlockState glass,
                                 BlockState light,
                                 BlockState signage) {
        // small box extrusion with a little neon sign
        for (int y = 0; y <= h; y++) {
            for (int x = 0; x < w; x++) {
                for (int z = 0; z < d; z++) {
                    boolean edge = (x == 0 || z == 0 || x == w - 1 || z == d - 1);
                    if (!edge) continue;
                    BlockState s = wall;
                    if (y == 1 && (x + z) % 3 == 1) s = glass;
                    if (y == 0) s = wall;
                    blocks.add(new PlannedBlock(rotateLocal(origin, ox + x, oy + y, oz + z, facing), s));
                    if ((x == 0 || x == w - 1) && (z == 0 || z == d - 1) && y <= h) {
                        blocks.add(new PlannedBlock(rotateLocal(origin, ox + x, oy + y, oz + z, facing), frame));
                    }
                }
            }
        }
        // tiny roof
        for (int x = 0; x < w; x++) {
            for (int z = 0; z < d; z++) {
                blocks.add(new PlannedBlock(rotateLocal(origin, ox + x, oy + h + 1, oz + z, facing), frame));
            }
        }
        // neon sign plate + light above
        BlockPos sp = rotateLocal(origin, ox + (w / 2), oy + 2, oz + (d / 2), facing);
        blocks.add(new PlannedBlock(sp, signage));
        blocks.add(new PlannedBlock(sp.up(), light));
    }

    private static void placeBillboard(List<PlannedBlock> blocks,
                                       BlockPos origin,
                                       Direction facing,
                                       int x,
                                       int y,
                                       int z,
                                       Direction outwardLocal,
                                       BlockState signage,
                                       BlockState light) {
        // A 3x4 plate in the outward direction (local axis), with a 1-block "depth" stack to hint hologram layering.
        // We place in local coords then rotate to world.
        int h = 4;
        for (int dy = 0; dy < h; dy++) {
            for (int i = -1; i <= 1; i++) {
                int lx = x;
                int lz = z;
                if (outwardLocal == Direction.EAST || outwardLocal == Direction.WEST) {
                    lz = z + i;
                } else {
                    lx = x + i;
                }
                BlockPos p0 = rotateLocal(origin, lx, y + dy, lz, facing);
                blocks.add(new PlannedBlock(p0, signage));
                // stacked layer
                BlockPos p1 = offsetOutwardLocal(p0, outwardLocal, facing, 1);
                blocks.add(new PlannedBlock(p1, signage));
            }
        }
        // top light
        BlockPos lp = rotateLocal(origin, x, y + h, z, facing);
        blocks.add(new PlannedBlock(lp, light));
    }

    private static BlockPos offsetOutwardLocal(BlockPos pWorld, Direction outwardLocal, Direction facing, int n) {
        // Convert a local outward direction into world offset given building facing.
        Direction worldDir = localToWorld(outwardLocal, facing);
        return pWorld.offset(worldDir, n);
    }

    private static Direction localToWorld(Direction local, Direction facing) {
        // local forward = +Z (SOUTH). Map local dirs by facing rotation.
        if (facing == null) facing = Direction.SOUTH;
        return switch (facing) {
            case SOUTH -> local;
            case NORTH -> local.getOpposite();
            case EAST -> switch (local) {
                case NORTH -> Direction.EAST;
                case SOUTH -> Direction.WEST;
                case EAST -> Direction.SOUTH;
                case WEST -> Direction.NORTH;
                default -> local;
            };
            case WEST -> switch (local) {
                case NORTH -> Direction.WEST;
                case SOUTH -> Direction.EAST;
                case EAST -> Direction.NORTH;
                case WEST -> Direction.SOUTH;
                default -> local;
            };
            default -> local;
        };
    }

    private static BlockPos rotateLocal(BlockPos origin, int lx, int ly, int lz, Direction facing) {
        if (origin == null) return null;
        if (facing == null) facing = Direction.SOUTH;
        // local forward = +Z
        return switch (facing) {
            case SOUTH -> origin.add(lx, ly, lz);
            case NORTH -> origin.add(-lx, ly, -lz);
            case EAST -> origin.add(lz, ly, -lx);
            case WEST -> origin.add(-lz, ly, lx);
            default -> origin.add(lx, ly, lz);
        };
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
        } catch (Throwable ex) { LOG.debug("best-effort step failed", ex); }
        return Direction.SOUTH;
    }

    private static double hash01(int a, int b, int c) {
        long x = 1469598103934665603L;
        x ^= a; x *= 1099511628211L;
        x ^= b; x *= 1099511628211L;
        x ^= c; x *= 1099511628211L;
        x ^= (x >>> 33);
        // map to [0,1)
        return ((x & 0x7fffffffffffffffL) / (double) Long.MAX_VALUE);
    }

    private static int getIntExtra(BuildingSpec spec, String key, int def) {
        return StructureSpecParsers.extraInt(spec, key, def);
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }
}


