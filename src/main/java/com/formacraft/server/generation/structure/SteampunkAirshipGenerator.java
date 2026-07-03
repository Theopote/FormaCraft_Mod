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
 * SteampunkAirshipGenerator (v1)
 * 气囊（椭球壳）+ 吊舱（木/铜/铁）+ 少量烟囱/螺旋桨提示。
 *
 * 目标：给 Steampunk / Fantasy 概念类请求一个稳定的“强原型入口”，并完全吃 paletteId 语义。
 */
public class SteampunkAirshipGenerator implements StructureGenerator {

    private static final FcaLog LOG = FcaLog.of("SteampunkAirshipGenerator");

    @Override
    public GeneratedStructure generate(BuildingSpec spec, BlockPos origin, ServerWorld world) {
        List<PlannedBlock> blocks = new ArrayList<>();

        int len = clamp(getIntExtra(spec, "hullLength", 33), 19, 59); // 主轴长度
        int radX = clamp(getIntExtra(spec, "hullRadius", 8), 5, 14);
        int radY = clamp(getIntExtra(spec, "hullHeight", 6), 4, 10);
        int gondolaLen = clamp(getIntExtra(spec, "gondolaLength", Math.max(9, len / 3)), 7, 19);
        int gondolaW = clamp(getIntExtra(spec, "gondolaWidth", 7), 5, 11);
        int gondolaH = clamp(getIntExtra(spec, "gondolaHeight", 4), 3, 6);

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
        BlockState hull = Blocks.CUT_COPPER.getDefaultState();
        BlockState rib = Blocks.IRON_BARS.getDefaultState();
        BlockState floor = Blocks.SPRUCE_PLANKS.getDefaultState();
        BlockState beam = Blocks.SPRUCE_LOG.getDefaultState();
        BlockState glass = Blocks.GLASS_PANE.getDefaultState();
        BlockState light = Blocks.LANTERN.getDefaultState();
        BlockState chain = getStateOrDefault(world, "minecraft:chain", Blocks.IRON_BARS.getDefaultState());

        if (paletteId != null && !paletteId.isBlank() && world != null) {
            hull = PaletteResolver.pick(world, paletteId, "FRAME", origin, 0x5A11001L, hull);
            hull = PaletteResolver.pick(world, paletteId, "DECOR_DETAIL", origin, 0x5A11002L, hull);
            rib = PaletteResolver.pick(world, paletteId, "DECOR_DETAIL", origin, 0x5A11003L, rib);
            floor = PaletteResolver.pick(world, paletteId, "FLOORING", origin, 0x5A11004L, floor);
            beam = PaletteResolver.pick(world, paletteId, "STRUCTURAL_BEAM", origin, 0x5A11005L, beam);
            glass = PaletteResolver.pick(world, paletteId, "WINDOW", origin, 0x5A11006L, glass);
            light = PaletteResolver.pick(world, paletteId, "LIGHTING", origin, 0x5A11007L, light);
            chain = PaletteResolver.pick(world, paletteId, "DECOR_DETAIL", origin, 0x5A11008L, chain);
        }

        // Coordinate frame: treat +Z as "forward", rotate by facing.
        // Balloon center above origin
        BlockPos center = origin.up(radY + gondolaH + 2);

        // 1) Hull shell (ellipsoid)
        // Equation: (x^2/a^2) + (y^2/b^2) + (z^2/c^2) <= 1
        // Use a=radX, b=radY, c=len/2.
        double a = radX;
        double b = radY;
        double c = Math.max(8.0, len / 2.0);
        double innerScale = 0.78; // shell thickness control
        for (int lx = -(radX + 1); lx <= (radX + 1); lx++) {
            for (int ly = -(radY + 1); ly <= (radY + 1); ly++) {
                for (int lz = -(len / 2 + 1); lz <= (len / 2 + 1); lz++) {
                    double nx = (lx / a);
                    double ny = (ly / b);
                    double nz = (lz / c);
                    double v = nx * nx + ny * ny + nz * nz;
                    if (v > 1.02) continue;
                    double nx2 = (lx / (a * innerScale));
                    double ny2 = (ly / (b * innerScale));
                    double nz2 = (lz / (c * innerScale));
                    double v2 = nx2 * nx2 + ny2 * ny2 + nz2 * nz2;
                    boolean isShell = v <= 1.02 && v2 >= 1.02;
                    if (!isShell) continue;

                    BlockPos p = rotateLocal(center, lx, ly, lz, facing);
                    BlockState s = hull;
                    // add sparse ribs
                    if ((Math.abs(lz) % 6 == 0 && (Math.abs(lx) == radX || Math.abs(ly) == radY))
                            || (Math.abs(lx) == radX && Math.abs(ly) <= 1 && (Math.abs(lz) % 5 == 0))) {
                        s = rib;
                    }
                    blocks.add(new PlannedBlock(p, s));
                }
            }
        }

        // 2) Gondola under hull
        // Place gondola centered below, slightly towards back for silhouette
        int backBias = Math.max(2, gondolaLen / 4);
        BlockPos gondolaOrigin = rotateLocal(center, -gondolaW / 2, -(radY + gondolaH + 1), -(backBias), facing);

        // floor + walls
        for (int x = 0; x < gondolaW; x++) {
            for (int z = 0; z < gondolaLen; z++) {
                BlockPos fp = rotateLocal(gondolaOrigin, x, 0, z, facing);
                blocks.add(new PlannedBlock(fp, floor));
                // low roof rim
                blocks.add(new PlannedBlock(fp.up(gondolaH), floor));
                // corner posts
                if ((x == 0 || x == gondolaW - 1) && (z == 0 || z == gondolaLen - 1)) {
                    for (int y = 1; y <= gondolaH; y++) blocks.add(new PlannedBlock(fp.up(y), beam));
                }
            }
        }
        // side walls (simple frame + windows)
        for (int y = 1; y < gondolaH; y++) {
            for (int z = 1; z < gondolaLen - 1; z++) {
                BlockPos left = rotateLocal(gondolaOrigin, 0, y, z, facing);
                BlockPos right = rotateLocal(gondolaOrigin, gondolaW - 1, y, z, facing);
                BlockState w = (y == 2 && (z % 3 == 1)) ? glass : beam;
                blocks.add(new PlannedBlock(left, w));
                blocks.add(new PlannedBlock(right, w));
            }
        }

        // 3) Entrance (front of gondola) + lights
        int doorX0 = gondolaW / 2 - 1;
        int doorX1 = gondolaW / 2;
        BlockPos doorBase = rotateLocal(gondolaOrigin, doorX0, 1, gondolaLen - 1, facing);
        blocks.add(new PlannedBlock(doorBase, Blocks.AIR.getDefaultState()));
        blocks.add(new PlannedBlock(doorBase.up(1), Blocks.AIR.getDefaultState()));
        blocks.add(new PlannedBlock(rotateLocal(gondolaOrigin, doorX1, 1, gondolaLen - 1, facing), Blocks.AIR.getDefaultState()));
        blocks.add(new PlannedBlock(rotateLocal(gondolaOrigin, doorX1, 2, gondolaLen - 1, facing), Blocks.AIR.getDefaultState()));
        // lanterns
        blocks.add(new PlannedBlock(rotateLocal(gondolaOrigin, doorX0 - 1, 2, gondolaLen - 1, facing), light));
        blocks.add(new PlannedBlock(rotateLocal(gondolaOrigin, doorX1 + 1, 2, gondolaLen - 1, facing), light));

        // 4) Ropes (chains) connecting gondola to hull
        int ropeZ0 = gondolaLen / 4;
        int ropeZ1 = gondolaLen - gondolaLen / 4;
        for (int z : new int[]{ropeZ0, ropeZ1}) {
            BlockPos aL = rotateLocal(gondolaOrigin, 0, gondolaH, z, facing);
            BlockPos aR = rotateLocal(gondolaOrigin, gondolaW - 1, gondolaH, z, facing);
            BlockPos bL = rotateLocal(center, -radX + 1, -radY + 1, z - backBias - len / 4, facing);
            BlockPos bR = rotateLocal(center, radX - 1, -radY + 1, z - backBias - len / 4, facing);
            drawVerticalChain(blocks, aL, bL, chain);
            drawVerticalChain(blocks, aR, bR, chain);
        }

        // 5) Tiny chimney hint on gondola roof
        BlockPos stack = rotateLocal(gondolaOrigin, 1, gondolaH + 1, 2, facing);
        blocks.add(new PlannedBlock(stack, rib));
        blocks.add(new PlannedBlock(stack.up(1), rib));

        String desc = String.format("SteampunkAirship (len=%d, r=%d, h=%d, facing=%s)", len, radX, radY, facing.asString());
        return new GeneratedStructure(null, origin, desc, blocks);
    }

    private static void drawVerticalChain(List<PlannedBlock> blocks, BlockPos a, BlockPos b, BlockState chain) {
        if (a == null || b == null) return;
        int minY = Math.min(a.getY(), b.getY());
        int maxY = Math.max(a.getY(), b.getY());
        if (maxY - minY > 24) return;
        int x = a.getX();
        int z = a.getZ();
        for (int y = minY; y <= maxY; y++) blocks.add(new PlannedBlock(new BlockPos(x, y, z), chain));
    }

    private static BlockPos rotateLocal(BlockPos origin, int lx, int ly, int lz, Direction facing) {
        if (origin == null) return null;
        if (facing == null) facing = Direction.SOUTH;
        // local coords assume forward = +Z
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

    private static int getIntExtra(BuildingSpec spec, String key, int def) {
        return StructureSpecParsers.extraInt(spec, key, def);
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    private static BlockState getStateOrDefault(ServerWorld world, String id, BlockState def) {
        if (id == null || id.isBlank()) return def;
        try {
            var ident = net.minecraft.util.Identifier.tryParse(id);
            if (ident == null) return def;
            return net.minecraft.registry.Registries.BLOCK.get(ident).getDefaultState();
        } catch (Exception e) {
            return def;
        }
    }
}


