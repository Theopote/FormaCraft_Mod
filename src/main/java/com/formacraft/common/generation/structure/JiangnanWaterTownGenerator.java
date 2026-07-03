package com.formacraft.common.generation.structure;

import com.formacraft.common.generation.structure.util.StructureSpecParsers;
import com.formacraft.common.logging.FcaLog;
import com.formacraft.common.model.build.BuildingSpec;
import com.formacraft.common.model.build.BuildingStyle;
import com.formacraft.common.model.build.BuildingType;
import com.formacraft.common.model.build.Features;
import com.formacraft.common.model.build.Footprint;
import com.formacraft.common.model.build.Materials;
import com.formacraft.common.model.build.StyleOptions;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * JiangnanWaterTownGenerator (v1):
 * A compact Jiangnan "water town" vignette:
 * - a straight canal
 * - stone embankments + waterside lanes
 * - one small bridge
 * - several compact waterside houses (delegates to HouseGenerator)
 *
 * Trigger:
 * - extra.template contains "jiangnan_water_town" / "water_town"
 * - OR extra.styleProfileId == "Chinese_Vernacular_Jiangnan_WaterTown"
 *
 * Anchor: origin is the center of the canal + bridge.
 */
public class JiangnanWaterTownGenerator implements StructureGenerator {

    private static final FcaLog LOG = FcaLog.of("JiangnanWaterTownGenerator");
    @Override
    public GeneratedStructure generate(BuildingSpec spec, BlockPos origin, ServerWorld world) {
        List<PlannedBlock> blocks = new ArrayList<>();

        Map<String, Object> extra = (spec != null) ? spec.getExtra() : null;

        // overall size (best-effort): footprint width is "cross-canal span", depth is "along-canal span"
        int w = (spec != null && spec.getFootprint() != null) ? Math.max(24, spec.getFootprint().getWidth()) : 40;
        int d = (spec != null && spec.getFootprint() != null) ? Math.max(24, spec.getFootprint().getDepth()) : 56;
        w = clamp(w, 24, 160);
        d = clamp(d, 24, 200);

        // style + palette
        BuildingStyle style = (spec != null && spec.getStyle() != null) ? spec.getStyle() : BuildingStyle.ASIAN;
        StyleProfile profile = (spec != null) ? StyleProfileRegistry.resolve(spec) : StyleProfileRegistry.forStyle(style);
        DetailPreferences details = (profile != null) ? profile.details() : null;

        String paletteId = (extra != null && extra.get("paletteId") != null) ? String.valueOf(extra.get("paletteId")).trim() : null;
        if ((paletteId == null || paletteId.isBlank()) && details != null && details.paletteId != null && !details.paletteId.isBlank()) {
            paletteId = details.paletteId.trim();
        }
        String styleProfileId = (extra != null && extra.get("styleProfileId") != null) ? String.valueOf(extra.get("styleProfileId")).trim() : null;

        // layout: entranceFacing controls canal forward direction (default SOUTH)
        Direction forward = resolveEntranceFacing(spec);
        Direction right = forward.rotateYClockwise();

        // canal parameters
        int canalWidth = clamp(getInt(extra, "canalWidth", 5), 3, 9);          // water surface width
        int laneWidth = clamp(getInt(extra, "laneWidth", 3), 2, 6);            // walking lane per side
        int embankmentWidth = 1;                                              // stone edge thickness
        int halfLen = d / 2;
        int halfCross = w / 2;

        // materials (semantic picks)
        BlockState bank = Blocks.STONE_BRICKS.getDefaultState();
        BlockState lane = Blocks.COBBLESTONE.getDefaultState();
        BlockState bridgeDeck = Blocks.STONE_BRICK_SLAB.getDefaultState();
        BlockState bridgeRail = Blocks.SPRUCE_FENCE.getDefaultState();
        BlockState water = Blocks.WATER.getDefaultState();
        BlockState lantern = Blocks.LANTERN.getDefaultState();

        if (paletteId != null && !paletteId.isBlank()) {
            bank = PaletteResolver.pick(world, paletteId, "ROAD_BORDER", origin, 0x71A001L, bank);
            lane = PaletteResolver.pick(world, paletteId, "ROAD_SURFACE", origin, 0x71A002L, lane);
            bridgeDeck = PaletteResolver.pick(world, paletteId, "BRIDGE_DECK", origin, 0x71A003L, bridgeDeck);
            bridgeRail = PaletteResolver.pick(world, paletteId, "BRIDGE_RAIL", origin, 0x71A004L, bridgeRail);
            lantern = PaletteResolver.pick(world, paletteId, "ROAD_LIGHT", origin, 0x71A005L, lantern);
            lantern = PaletteResolver.pick(world, paletteId, "LIGHTING", origin, 0x71A006L, lantern);
        }

        int canalHalf = canalWidth / 2;
        int canalX0 = -canalHalf;
        int canalX1 = canalHalf;

        // derived cross-section:
        int bankL = canalX0 - embankmentWidth;
        int bankR = canalX1 + embankmentWidth;
        int laneL0 = bankL - laneWidth;
        int laneL1 = bankL - 1;
        int laneR0 = bankR + 1;
        int laneR1 = bankR + laneWidth;

        // make sure we fit inside cross span
        if (laneL0 < -halfCross + 2) {
            int shift = (-halfCross + 2) - laneL0;
            laneL0 += shift; laneL1 += shift;
            bankL += shift; bankR += shift;
            laneR0 += shift; laneR1 += shift;
            canalX0 += shift; canalX1 += shift;
        }
        if (laneR1 > halfCross - 2) {
            int shift = laneR1 - (halfCross - 2);
            laneL0 -= shift; laneL1 -= shift;
            bankL -= shift; bankR -= shift;
            laneR0 -= shift; laneR1 -= shift;
            canalX0 -= shift; canalX1 -= shift;
        }

        // --- generate canal + embankments + lanes ---
        for (int z = -halfLen; z <= halfLen; z++) {
            for (int x = -halfCross; x <= halfCross; x++) {
                BlockPos p = p(origin, right, forward, x, 0, z);

                // carve a bit of air above water/lanes (best-effort)
                for (int y = 1; y <= 6; y++) {
                    blocks.add(new PlannedBlock(p.up(y), Blocks.AIR.getDefaultState()));
                }

                if (x >= canalX0 && x <= canalX1) {
                    // canal: shallow water (y=0) + stone bottom (y=-1)
                    blocks.add(new PlannedBlock(p, water));
                    blocks.add(new PlannedBlock(p.down(), bank));
                } else if (x == bankL || x == bankR) {
                    blocks.add(new PlannedBlock(p, bank));
                } else if ((x >= laneL0 && x <= laneL1) || (x >= laneR0 && x <= laneR1)) {
                    blocks.add(new PlannedBlock(p, lane));
                }
            }
        }

        // --- small bridge at z=0 crossing the canal ---
        // bridge deck spans from laneL1 to laneR0 (inclusive)
        for (int x = laneL1; x <= laneR0; x++) {
            BlockPos dp = p(origin, right, forward, x, 0, 0);
            blocks.add(new PlannedBlock(dp, bridgeDeck));
            // simple rails outside deck edges
            if (x == laneL1 || x == laneR0) {
                blocks.add(new PlannedBlock(dp.up(), bridgeRail));
            }
        }

        // lanterns near bridge ends
        blocks.add(new PlannedBlock(p(origin, right, forward, laneL1 - 1, 1, 0), lantern));
        blocks.add(new PlannedBlock(p(origin, right, forward, laneR0 + 1, 1, 0), lantern));

        // --- place waterside houses (delegate to HouseGenerator) ---
        int houseW = clamp(getInt(extra, "houseWidth", 9), 7, 15);
        int houseD = clamp(getInt(extra, "houseDepth", 7), 6, 13);
        int houseH = clamp(getInt(extra, "houseHeight", 10), 6, 24);
        int floors = clamp(getInt(extra, "houseFloors", 2), 1, 4);

        int riverToHouseGap = 2; // lane -> house offset
        int leftHouseX = laneL0 - riverToHouseGap - houseW;     // place house "behind" left lane
        int rightHouseX = laneR1 + riverToHouseGap;             // place house "behind" right lane

        // along-canal spacing
        int step = clamp(getInt(extra, "houseStep", 14), 10, 24);
        int maxZ = halfLen - 6;
        int minZ = -halfLen + 6;

        List<Integer> zs = new ArrayList<>();
        for (int z = minZ; z <= maxZ; z += step) {
            // keep space around bridge
            if (Math.abs(z) <= 6) continue;
            zs.add(z);
        }
        // limit count for performance
        while (zs.size() > 6) zs.remove(zs.size() - 1);

        HouseGenerator houseGen = new HouseGenerator();
        for (int z : zs) {
            // Left bank house: entrance faces RIGHT (toward canal center)
            BuildingSpec hsL = makeWatertownHouseSpec(houseW, houseD, houseH, floors, styleProfileId, paletteId, right.getOpposite());
            BlockPos houseOriginL = p(origin, right, forward, leftHouseX, 0, z);
            blocks.addAll(houseGen.generate(hsL, houseOriginL, world).getBlocks());

            // Right bank house: entrance faces LEFT
            BuildingSpec hsR = makeWatertownHouseSpec(houseW, houseD, houseH, floors, styleProfileId, paletteId, right);
            BlockPos houseOriginR = p(origin, right, forward, rightHouseX, 0, z);
            blocks.addAll(houseGen.generate(hsR, houseOriginR, world).getBlocks());

            // small pier steps (visual cue) from lane into water edge
            BlockPos pierL = p(origin, right, forward, laneL0, 0, z);
            BlockPos pierR = p(origin, right, forward, laneR1, 0, z);
            blocks.add(new PlannedBlock(pierL, bridgeDeck));
            blocks.add(new PlannedBlock(pierR, bridgeDeck));
        }

        String desc = String.format("JiangnanWaterTown (w=%d,d=%d, canal=%d, houses=%d)", w, d, canalWidth, zs.size() * 2);
        return new GeneratedStructure(null, origin, desc, blocks);
    }

    private static BuildingSpec makeWatertownHouseSpec(int w, int d, int h, int floors,
                                                       String styleProfileId, String paletteId, Direction entranceFacing) {
        BuildingSpec s = new BuildingSpec();
        s.setType(BuildingType.HOUSE);
        s.setStyle(BuildingStyle.ASIAN);
        s.setFootprint(new Footprint(w, d));
        s.setHeight(h);
        s.setFloors(floors);

        s.setMaterials(new Materials());
        Features f = new Features();
        f.setHasDoor(true);
        f.setHasWindows(true);
        f.setHasRoof(true);
        f.setHasRoofDecoration(true);
        f.setFloorCount(floors);
        s.setFeatures(f);

        StyleOptions so = new StyleOptions();
        so.setRoofType("gable");
        so.setDoorStyle("single");
        so.setWindowRatio(0.22);
        so.setWindowStyle("fence");
        s.setStyleOptions(so);

        HashMap<String, Object> extra = new HashMap<>();
        if (styleProfileId != null && !styleProfileId.isBlank()) extra.put("styleProfileId", styleProfileId);
        if (paletteId != null && !paletteId.isBlank()) extra.put("paletteId", paletteId);
        HashMap<String, Object> layout = new HashMap<>();
        layout.put("entranceFacing", entranceFacing.asString().toUpperCase(java.util.Locale.ROOT));
        extra.put("layout", layout);
        s.setExtra(extra);

        return s;
    }

    private static BlockPos p(BlockPos origin, Direction right, Direction forward, int xRight, int y, int zForward) {
        return origin.add(0, y, 0).offset(right, xRight).offset(forward, zForward);
    }

    private static Direction resolveEntranceFacing(BuildingSpec spec) {
        // Priority: extra.layout.entranceFacing > default SOUTH
        try {
            if (spec != null && spec.getExtra() != null) {
                Object layoutObj = spec.getExtra().get("layout");
                if (layoutObj instanceof Map<?, ?> m) {
                    Object ef = m.get("entranceFacing");
                    if (ef != null) {
                        String s = String.valueOf(ef).trim().toUpperCase(java.util.Locale.ROOT);
                        return switch (s) {
                            case "N", "NORTH", "北", "朝北" -> Direction.NORTH;
                            case "E", "EAST", "东", "朝东" -> Direction.EAST;
                            case "W", "WEST", "西", "朝西" -> Direction.WEST;
                            default -> Direction.SOUTH;
                        };
                    }
                }
            }
        } catch (Throwable ex) { LOG.debug("best-effort step failed", ex); }
        return Direction.SOUTH;
    }

    private static int getInt(Map<String, Object> extra, String key, int def) {
        return StructureSpecParsers.mapInt(extra, key, def);
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }
}


