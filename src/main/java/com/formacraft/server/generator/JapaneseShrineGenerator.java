package com.formacraft.server.generator;

import com.formacraft.common.model.build.BuildingSpec;
import com.formacraft.common.model.build.BuildingStyle;
import com.formacraft.common.model.build.BuildingType;
import com.formacraft.common.model.build.Footprint;
import com.formacraft.common.model.build.Materials;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * JapaneseShrineGenerator (v1):
 * - 入口鸟居（复用 HouseGenerator 的 portal 风格实现思路，但这里直接画一个更稳）
 * - 简单参道（ROAD_SURFACE）
 * - 拜殿/本殿：委托 HouseGenerator（Japanese_Traditional + shoji + flying_eaves + shrine_lanterns）
 *
 * 触发建议：BuildingSpec.extra.template = "japanese_shrine"
 */
public class JapaneseShrineGenerator implements StructureGenerator {

    @Override
    public GeneratedStructure generate(BuildingSpec spec, BlockPos origin, ServerWorld world) {
        List<PlannedBlock> blocks = new ArrayList<>();

        // Resolve style profile / palette (extra > style default)
        BuildingStyle style = (spec != null && spec.getStyle() != null) ? spec.getStyle() : BuildingStyle.ASIAN;
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

        int hallW = Math.max(11, spec != null && spec.getFootprint() != null ? spec.getFootprint().getWidth() : 13);
        int hallD = Math.max(11, spec != null && spec.getFootprint() != null ? spec.getFootprint().getDepth() : 15);
        int pathLen = Math.max(10, hallD / 2 + 4);

        // Materials
        BlockState path = PaletteResolver.pick(world, paletteId, "ROAD_SURFACE", origin, 0x1A51001L, Blocks.BIRCH_PLANKS.getDefaultState());
        BlockState post = PaletteResolver.pick(world, paletteId, "STRUCTURAL_BEAM", origin, 0x1A51002L, Blocks.SPRUCE_LOG.getDefaultState());
        post = PaletteResolver.pick(world, paletteId, "FRAME", origin, 0x1A51003L, post);
        BlockState beam = PaletteResolver.pick(world, paletteId, "DECOR_DETAIL", origin, 0x1A51004L, Blocks.DARK_OAK_PLANKS.getDefaultState());
        BlockState lantern = PaletteResolver.pick(world, paletteId, "LIGHTING", origin, 0x1A51005L, Blocks.LANTERN.getDefaultState());
        lantern = PaletteResolver.pick(world, paletteId, "ROAD_LIGHT", origin, 0x1A51006L, lantern);

        // Coordinate transform: make local "forward" be SOUTH
        // We'll build everything in local space, then rotate to entrance direction.
        BlockPos localOrigin = origin;

        // 1) Clear a small box (best-effort)
        for (int x = -hallW; x <= hallW; x++) {
            for (int z = -2; z <= hallD + pathLen + 4; z++) {
                for (int y = 0; y <= 12; y++) {
                    blocks.add(new PlannedBlock(rotate(localOrigin.add(x, y, z), origin, entrance), Blocks.AIR.getDefaultState()));
                }
            }
        }

        // 2) Approach path (centered)
        int pathHalf = 1;
        for (int z = 0; z <= pathLen; z++) {
            for (int x = -pathHalf; x <= pathHalf; x++) {
                blocks.add(new PlannedBlock(rotate(localOrigin.add(x, 0, z), origin, entrance), path));
            }
            // small lanterns along path
            if (z > 2 && (z % 6) == 0) {
                blocks.add(new PlannedBlock(rotate(localOrigin.add(-3, 1, z), origin, entrance), lantern));
                blocks.add(new PlannedBlock(rotate(localOrigin.add(3, 1, z), origin, entrance), lantern));
            }
        }

        // 3) Torii gate at path entrance (z=0)
        int zGate = 0;
        int gateW = 6;
        int xL = -gateW / 2;
        int xR = gateW / 2;
        for (int y = 0; y <= 4; y++) {
            blocks.add(new PlannedBlock(rotate(localOrigin.add(xL, y, zGate), origin, entrance), post));
            blocks.add(new PlannedBlock(rotate(localOrigin.add(xR, y, zGate), origin, entrance), post));
        }
        for (int x = xL - 1; x <= xR + 1; x++) {
            blocks.add(new PlannedBlock(rotate(localOrigin.add(x, 5, zGate), origin, entrance), beam));
        }
        // little caps
        blocks.add(new PlannedBlock(rotate(localOrigin.add(xL, 5, zGate), origin, entrance), beam));
        blocks.add(new PlannedBlock(rotate(localOrigin.add(xR, 5, zGate), origin, entrance), beam));

        // 4) Hall (delegated to HouseGenerator), placed at end of path
        BlockPos hallOriginLocal = localOrigin.add(-hallW / 2, 0, pathLen + 2);
        BuildingSpec hall = makeHallSpec(spec, profile, paletteId, hallW, hallD, entrance.getOpposite());
        blocks.addAll(new HouseGenerator().generate(hall, rotate(hallOriginLocal, origin, entrance), world).getBlocks());

        String desc = "Japanese Shrine (v1)";
        return new GeneratedStructure(null, origin, desc, blocks);
    }

    private static BuildingSpec makeHallSpec(BuildingSpec parent, StyleProfile profile, String paletteId,
                                             int w, int d, Direction entranceFacing) {
        BuildingSpec s = new BuildingSpec();
        s.setType(BuildingType.HOUSE);
        // StyleProfile is phenotype-only (no BuildingStyle enum attached); keep parent style or default to ASIAN.
        s.setStyle(parent != null && parent.getStyle() != null ? parent.getStyle() : BuildingStyle.ASIAN);
        s.setFootprint(new Footprint(w, d));
        s.setFloors(1);
        s.setHeight(8);

        // Let HouseGenerator resolve most materials from palette; keep explicit minimal.
        Materials m = new Materials();
        s.setMaterials(m);

        Map<String, Object> extra = new HashMap<>();
        extra.put("styleProfileId", profile != null ? profile.id() : "Japanese_Traditional");
        if (paletteId != null && !paletteId.isBlank()) extra.put("paletteId", paletteId);
        extra.put("layout", Map.of("entranceFacing", entranceFacing.asString(), "symmetry", "NONE", "plan", "none"));
        s.setExtra(extra);
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

    /**
     * Rotate a point around the base origin so that local SOUTH aligns to entrance direction.
     * local coordinate system: +Z = SOUTH, +X = EAST.
     */
    private static BlockPos rotate(BlockPos p, BlockPos base, Direction entrance) {
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
}


