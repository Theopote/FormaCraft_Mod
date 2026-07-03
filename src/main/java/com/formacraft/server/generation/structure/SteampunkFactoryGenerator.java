package com.formacraft.server.generation.structure;

import com.formacraft.server.generation.structure.util.StructureSpecParsers;
import com.formacraft.common.logging.FcaLog;
import com.formacraft.common.model.build.BuildingSpec;
import com.formacraft.common.model.build.BuildingStyle;
import com.formacraft.common.style.profile.DetailPreferences;
import com.formacraft.common.style.profile.StyleProfile;
import com.formacraft.common.style.profile.StyleProfileRegistry;
import com.formacraft.common.build.GeneratedStructure;
import com.formacraft.common.build.PlannedBlock;
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
 * SteampunkFactoryGenerator (v1)
 *
 * 识别性目标（P0）：
 * - 锯齿屋顶（sawtooth）+ 一侧天窗带（clerestory）
 * - 外露桁架/梁（STRUCTURAL_BEAM/FRAME）
 * - 多根烟囱（DECOR_DETAIL），顶部带“冒烟”提示（campfire）
 *
 * 说明：
 * - 尺度与细节都做了可控参数，默认给一个“像工厂”的体块。
 * - 完全走 paletteId 语义；显式 spec.materials/extra 的覆盖仍然优先。
 */
public class SteampunkFactoryGenerator implements StructureGenerator {

    private static final FcaLog LOG = FcaLog.of("SteampunkFactoryGenerator");

    @Override
    public GeneratedStructure generate(BuildingSpec spec, BlockPos origin, ServerWorld world) {
        List<PlannedBlock> blocks = new ArrayList<>();

        int width = clamp(getIntExtra(spec, "width", 23), 13, 61);
        int depth = clamp(getIntExtra(spec, "depth", 31), 15, 81);
        int wallH = clamp(getIntExtra(spec, "wallHeight", 7), 5, 12);
        int roofH = clamp(getIntExtra(spec, "roofHeight", 5), 3, 9);
        int bays = clamp(getIntExtra(spec, "bays", Math.max(3, depth / 10)), 2, 9);
        int chimneyCount = clamp(getIntExtra(spec, "chimneyCount", 3), 1, 7);
        boolean interiorTruss = getBoolExtra(spec);

        // Style & palette
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
        BlockState foundation = Blocks.DEEPSLATE_BRICKS.getDefaultState();
        BlockState wall = Blocks.DEEPSLATE_BRICKS.getDefaultState();
        BlockState beam = Blocks.SPRUCE_LOG.getDefaultState();
        BlockState frame = Blocks.CUT_COPPER.getDefaultState();
        BlockState roofTile = Blocks.POLISHED_DEEPSLATE.getDefaultState();
        BlockState roofSlope = Blocks.DEEPSLATE_TILE_STAIRS.getDefaultState();
        BlockState floor = Blocks.POLISHED_DEEPSLATE.getDefaultState();
        BlockState window = Blocks.GLASS_PANE.getDefaultState();
        BlockState decor = Blocks.IRON_BARS.getDefaultState();
        BlockState light = Blocks.LANTERN.getDefaultState();

        if (paletteId != null && !paletteId.isBlank() && world != null) {
            foundation = PaletteResolver.pick(world, paletteId, "WALL_FOUNDATION", origin, 0x5F41001L, foundation);
            wall = PaletteResolver.pick(world, paletteId, "WALL_BASE", origin, 0x5F41002L, wall);
            beam = PaletteResolver.pick(world, paletteId, "STRUCTURAL_BEAM", origin, 0x5F41003L, beam);
            frame = PaletteResolver.pick(world, paletteId, "FRAME", origin, 0x5F41004L, frame);
            decor = PaletteResolver.pick(world, paletteId, "DECOR_DETAIL", origin, 0x5F41005L, decor);
            roofTile = PaletteResolver.pick(world, paletteId, "ROOF_TILE", origin, 0x5F41006L, roofTile);
            roofSlope = PaletteResolver.pick(world, paletteId, "ROOF_SLOPE", origin, 0x5F41007L, roofSlope);
            floor = PaletteResolver.pick(world, paletteId, "FLOORING", origin, 0x5F41008L, floor);
            window = PaletteResolver.pick(world, paletteId, "WINDOW", origin, 0x5F41009L, window);
            light = PaletteResolver.pick(world, paletteId, "LIGHTING", origin, 0x5F4100AL, light);
        }

        // 1) Floor + foundation ring (slight plinth)
        for (int x = 0; x < width; x++) {
            for (int z = 0; z < depth; z++) {
                blocks.add(new PlannedBlock(rotateLocal(origin, x, 0, z, facing), floor));
                // foundation under outer ring
                if (x == 0 || z == 0 || x == width - 1 || z == depth - 1) {
                    blocks.add(new PlannedBlock(rotateLocal(origin, x, -1, z, facing), foundation));
                }
            }
        }

        // 2) Walls (shell) + big industrial windows (bars-ish)
        int winY = Math.max(2, wallH / 2);
        for (int y = 1; y <= wallH; y++) {
            for (int x = 0; x < width; x++) {
                for (int z : new int[]{0, depth - 1}) {
                    BlockState s = wall;
                    if (y == winY && x % 3 == 1) s = window;
                    blocks.add(new PlannedBlock(rotateLocal(origin, x, y, z, facing), s));
                }
            }
            for (int z = 0; z < depth; z++) {
                for (int x : new int[]{0, width - 1}) {
                    BlockState s = wall;
                    if (y == winY && z % 3 == 1) s = window;
                    blocks.add(new PlannedBlock(rotateLocal(origin, x, y, z, facing), s));
                }
            }
        }

        // 3) Entrance on facing side: 3-wide door cut + small canopy frame
        int cx = width / 2;
        int doorW = Math.min(3, Math.max(2, width / 8));
        int dx0 = cx - (doorW / 2);
        int dx1 = dx0 + doorW - 1;
        int dz = depth - 1; // "front" in local +Z
        for (int x = dx0; x <= dx1; x++) {
            blocks.add(new PlannedBlock(rotateLocal(origin, x, 1, dz, facing), Blocks.AIR.getDefaultState()));
            blocks.add(new PlannedBlock(rotateLocal(origin, x, 2, dz, facing), Blocks.AIR.getDefaultState()));
        }
        // canopy
        for (int x = dx0 - 1; x <= dx1 + 1; x++) {
            blocks.add(new PlannedBlock(rotateLocal(origin, x, 3, dz, facing), frame));
        }
        blocks.add(new PlannedBlock(rotateLocal(origin, dx0 - 1, 2, dz, facing), light));
        blocks.add(new PlannedBlock(rotateLocal(origin, dx1 + 1, 2, dz, facing), light));

        // 4) Exposed bay frames (vertical truss hints) along sides
        int bayStep = Math.max(5, depth / bays);
        for (int i = 1; i < bays; i++) {
            int z = i * bayStep;
            if (z <= 1 || z >= depth - 2) continue;
            for (int y = 1; y <= wallH; y++) {
                blocks.add(new PlannedBlock(rotateLocal(origin, 0, y, z, facing), beam));
                blocks.add(new PlannedBlock(rotateLocal(origin, width - 1, y, z, facing), beam));
                if ((y & 1) == 0) {
                    blocks.add(new PlannedBlock(rotateLocal(origin, 1, y, z, facing), decor));
                    blocks.add(new PlannedBlock(rotateLocal(origin, width - 2, y, z, facing), decor));
                }
            }
            // horizontal tie beam
            for (int x = 1; x < width - 1; x++) {
                if (x % 3 == 0) blocks.add(new PlannedBlock(rotateLocal(origin, x, wallH, z, facing), frame));
            }
        }

        // 5) Sawtooth roof: repeating units along depth
        // Each unit is 4 blocks deep: slope up + vertical glass + small flat cap.
        int unit = 4;
        int roofBaseY = wallH + 1;
        for (int z0 = 0; z0 < depth; z0 += unit) {
            int z1 = Math.min(depth - 1, z0 + unit - 1);
            // skip last partial to avoid ugly truncation
            if (z1 - z0 < 2) break;

            // slope direction: always towards -X (gives consistent “tooth” silhouette)
            // Place slope rows for y=0..roofH-1
            for (int ry = 0; ry < roofH; ry++) {
                int xMin = 1 + ry;
                int xMax = width - 2;
                for (int z = z0; z <= z1; z++) {
                    // slope skin
                    for (int x = xMin; x <= xMax; x++) {
                        if (ry == 0 && (x == xMin || x == xMax) && (z % 2 == 0)) continue;
                        blocks.add(new PlannedBlock(rotateLocal(origin, x, roofBaseY + ry, z, facing), roofTile));
                    }
                    // ridge cap band
                    if (ry == roofH - 1 && (z == z0 || z == z1)) {
                        for (int x = xMin; x <= xMin + 2; x++) {
                            blocks.add(new PlannedBlock(rotateLocal(origin, x, roofBaseY + ry + 1, z, facing), roofTile));
                        }
                    }
                }
            }

            // clerestory: a vertical glass strip at x=1 (inside roof)
            int glassX = 1;
            for (int z = z0 + 1; z <= z1 - 1; z++) {
                blocks.add(new PlannedBlock(rotateLocal(origin, glassX, roofBaseY + roofH, z, facing), window));
                blocks.add(new PlannedBlock(rotateLocal(origin, glassX, roofBaseY + roofH + 1, z, facing), frame));
            }
        }

        // 6) Interior trusses (simple A-frame hints) per bay
        if (interiorTruss) {
            for (int i = 1; i < bays; i++) {
                int z = i * bayStep;
                if (z <= 2 || z >= depth - 3) continue;
                for (int ry = 0; ry < Math.min(roofH, 6); ry++) {
                    int xl = 2 + ry;
                    int xr = (width - 3) - ry;
                    if (xl >= xr) break;
                    blocks.add(new PlannedBlock(rotateLocal(origin, xl, roofBaseY + ry, z, facing), beam));
                    blocks.add(new PlannedBlock(rotateLocal(origin, xr, roofBaseY + ry, z, facing), beam));
                    if ((ry & 1) == 1) {
                        blocks.add(new PlannedBlock(rotateLocal(origin, xl + 1, roofBaseY + ry, z, facing), decor));
                        blocks.add(new PlannedBlock(rotateLocal(origin, xr - 1, roofBaseY + ry, z, facing), decor));
                    }
                }
                // bottom tie
                for (int x = 2; x <= width - 3; x += 2) {
                    blocks.add(new PlannedBlock(rotateLocal(origin, x, roofBaseY, z, facing), frame));
                }
            }
        }

        // 7) Chimneys (roof edge) with smoke hint
        for (int i = 0; i < chimneyCount; i++) {
            int z = 3 + (i * (depth - 6) / chimneyCount);
            int x = (i % 2 == 0) ? (width - 3) : 2;
            int h = 3 + (i % 3);
            for (int y = 0; y < h; y++) {
                blocks.add(new PlannedBlock(rotateLocal(origin, x, roofBaseY + roofH + y, z, facing), decor));
            }
            // top: campfire smoke (best-effort, low intrusion)
            blocks.add(new PlannedBlock(rotateLocal(origin, x, roofBaseY + roofH + h, z, facing), Blocks.CAMPFIRE.getDefaultState()));
        }

        String desc = String.format("SteampunkFactory (w=%d,d=%d,wallH=%d,roofH=%d,bays=%d,facing=%s)", width, depth, wallH, roofH, bays, facing.asString());
        return new GeneratedStructure(null, origin, desc, blocks);
    }

    private static BlockPos rotateLocal(BlockPos origin, int lx, int ly, int lz, Direction facing) {
        if (origin == null) return null;
        if (facing == null) facing = Direction.SOUTH;
        // local: forward = +Z
        return switch (facing) {
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
                        case "E", "EAST", "东", "朝东" -> Direction.EAST;
                        case "W", "WEST", "西", "朝西" -> Direction.WEST;
                        default -> Direction.SOUTH;
                    };
                }
            }
        } catch (Throwable ex) { LOG.debug("best-effort step failed", ex); }
        return Direction.SOUTH;
    }

    private static boolean getBoolExtra(BuildingSpec spec) {
        if (spec == null) return true;
        Map<String, Object> extra = spec.getExtra();
        if (extra == null) return true;
        Object v = extra.get("truss");
        if (v == null) return true;
        if (v instanceof Boolean b) return b;
        String s = String.valueOf(v).trim().toLowerCase();
        if (s.isEmpty()) return true;
        return s.equals("1") || s.equals("true") || s.equals("yes") || s.equals("y") || s.equals("on");
    }

    private static int getIntExtra(BuildingSpec spec, String key, int def) {
        return StructureSpecParsers.extraInt(spec, key, def);
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }
}


