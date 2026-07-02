package com.formacraft.common.generation.structure;

import com.formacraft.common.model.build.BuildingSpec;
import com.formacraft.common.model.build.BuildingStyle;
import com.formacraft.common.style.profile.DetailPreferences;
import com.formacraft.common.style.profile.StyleProfile;
import com.formacraft.common.style.profile.StyleProfileRegistry;
import com.formacraft.server.build.GeneratedStructure;
import com.formacraft.server.build.PlannedBlock;
import com.formacraft.server.interior.BspFloorPlanGenerator;
import com.formacraft.server.interior.FloorPlanConfig;
import com.formacraft.server.material.PaletteResolver;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.enums.DoubleBlockHalf;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.state.property.Properties;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * OfficeBlockGenerator (v1):
 * Simple modern rectangular office building (flat roof, window bands).
 *
 * Triggered by template routing: spec.extra.template == "office_block".
 */
public class OfficeBlockGenerator implements StructureGenerator {
    @Override
    public GeneratedStructure generate(BuildingSpec spec, BlockPos origin, ServerWorld world) {
        int w = (spec != null && spec.getFootprint() != null) ? Math.max(7, spec.getFootprint().getWidth()) : 9;
        int d = (spec != null && spec.getFootprint() != null) ? Math.max(7, spec.getFootprint().getDepth()) : 9;
        int h = (spec != null) ? Math.max(10, spec.getHeight()) : 18;

        BuildingStyle style = (spec != null && spec.getStyle() != null) ? spec.getStyle() : BuildingStyle.MODERN;
        StyleProfile profile = (spec != null) ? StyleProfileRegistry.resolve(spec) : StyleProfileRegistry.forStyle(style);
        DetailPreferences details = profile != null ? profile.details() : null;

        // paletteId: explicit extra.paletteId wins, else styleProfile.details.paletteId
        String paletteId = null;
        if (spec != null && spec.getExtra() != null && spec.getExtra().get("paletteId") != null) {
            paletteId = String.valueOf(spec.getExtra().get("paletteId")).trim();
        }
        if ((paletteId == null || paletteId.isBlank()) && details != null && details.paletteId != null && !details.paletteId.isBlank()) {
            paletteId = details.paletteId.trim();
        }

        String wallId = (spec != null && spec.getMaterials() != null) ? spec.getMaterials().getWall() : null;
        String windowId = (spec != null && spec.getMaterials() != null) ? spec.getMaterials().getWindow() : null;
        String floorId = (spec != null && spec.getMaterials() != null) ? spec.getMaterials().getFloor() : null;
        String roofId = (spec != null && spec.getMaterials() != null) ? spec.getMaterials().getRoof() : null;

        BlockState wall = getStateOrDefault(world, wallId,
                Blocks.LIGHT_GRAY_CONCRETE.getDefaultState());
        BlockState glass = getStateOrDefault(world, windowId,
                Blocks.GLASS_PANE.getDefaultState());
        BlockState floor = getStateOrDefault(world, floorId,
                Blocks.SMOOTH_STONE.getDefaultState());
        BlockState roof = getStateOrDefault(world, roofId,
                Blocks.SMOOTH_STONE.getDefaultState());

        // windowStyle from style profile (StyleOptions still can override via spec.materials.window / explicit)
        String windowStyle = (details != null && details.windowStyle != null) ? details.windowStyle : null;
        if (spec != null && spec.getStyleOptions() != null && spec.getStyleOptions().getWindowStyle() != null) {
            windowStyle = spec.getStyleOptions().getWindowStyle();
        }
        if (windowStyle != null) {
            String ws = windowStyle.trim().toLowerCase(java.util.Locale.ROOT);
            if (ws.contains("shoji")) glass = Blocks.WHITE_STAINED_GLASS_PANE.getDefaultState();
            else if (ws.contains("stained")) glass = Blocks.LIGHT_BLUE_STAINED_GLASS_PANE.getDefaultState();
            else if (ws.contains("curtain")) glass = Blocks.GLASS_PANE.getDefaultState();
            else if (ws.contains("bars") || ws.contains("slit")) glass = Blocks.IRON_BARS.getDefaultState();
        }

        // Apply palette semantics only when the caller did NOT explicitly set that material id.
        if (paletteId != null && !paletteId.isBlank()) {
            boolean curtain = windowStyle != null && windowStyle.trim().toLowerCase(java.util.Locale.ROOT).contains("curtain");
            if (wallId == null || wallId.isBlank()) {
                // Curtain wall wants a frame; otherwise use the normal wall base.
                wall = curtain
                        ? PaletteResolver.pick(world, paletteId, "FRAME", origin, 0x0FF1CEB2L, wall)
                        : PaletteResolver.pick(world, paletteId, "WALL_BASE", origin, 0x0FF1CEBL, wall);
            }
            if (windowId == null || windowId.isBlank()) {
                // Curtain wall prefers facade curtain blocks; otherwise use WINDOW.
                glass = curtain
                        ? PaletteResolver.pick(world, paletteId, "FACADE_CURTAIN", origin, 0x0FF1CEB3L, glass)
                        : PaletteResolver.pick(world, paletteId, "WINDOW", origin, 0x0FF1CEB1L, glass);
            }
            if (floorId == null || floorId.isBlank()) {
                floor = PaletteResolver.pick(world, paletteId, "FLOORING", origin, 0x0FF1CEF0L, floor);
            }
            if (roofId == null || roofId.isBlank()) {
                roof = PaletteResolver.pick(world, paletteId, "ROOF_TILE", origin, 0x0FF1CE0FL, roof);
                roof = PaletteResolver.pick(world, paletteId, "FLOOR_SLAB", origin, 0x0FF1CE10L, roof);
            }
        }

        List<PlannedBlock> blocks = new ArrayList<>(Math.max(4000, w * d * h / 2));

        int halfW = w / 2;
        int halfD = d / 2;

        // ------------------------------
        // Layout IR (extra.layout)
        // ------------------------------
        String entranceFacing = null; // NORTH/SOUTH/EAST/WEST (entrance is on that side of the building)
        String symmetry = null;       // NONE/X/Z/BOTH
        String plan = "none";         // none/front_back/left_right/ring_corridor
        boolean courtyard = false;
        double courtyardRatio = 0.0;
        if (spec != null && spec.getExtra() != null) {
            Object layoutObj = spec.getExtra().get("layout");
            if (layoutObj instanceof Map<?, ?> m) {
                Object ef = m.get("entranceFacing");
                if (ef != null) entranceFacing = String.valueOf(ef).trim().toUpperCase(java.util.Locale.ROOT);
                Object sym = m.get("symmetry");
                if (sym != null) symmetry = String.valueOf(sym).trim().toUpperCase(java.util.Locale.ROOT);
                Object pl = m.get("plan");
                if (pl != null) {
                    String p = String.valueOf(pl).trim().toLowerCase(java.util.Locale.ROOT);
                    if (p.equals("front_back") || p.equals("frontback") || p.equals("front-back") || p.equals("front/back")
                            || p.equals("前后") || p.equals("前后分区") || p.equals("前后布局") || p.equals("前厅后室")) {
                        plan = "front_back";
                    } else if (p.equals("left_right") || p.equals("leftright") || p.equals("left-right") || p.equals("left/right")
                            || p.equals("左右") || p.equals("左右分区") || p.equals("左右布局")) {
                        plan = "left_right";
                    } else if (p.equals("ring_corridor") || p.equals("ring") || p.equals("courtyard_corridor") || p.equals("gallery") || p.equals("cloister")
                            || p.equals("回廊") || p.equals("环廊") || p.equals("环形走廊") || p.equals("围绕中庭") || p.equals("回字形") || p.equals("回字布局") || p.equals("回字走廊")) {
                        plan = "ring_corridor";
                    } else if (p.equals("none") || p.equals("no") || p.equals("false") || p.equals("0") || p.equals("off")) {
                        plan = "none";
                    }
                }
                Object ct = m.get("courtyard");
                if (ct instanceof Boolean b) courtyard = b;
                else if (ct != null) {
                    String s = String.valueOf(ct).trim().toLowerCase(java.util.Locale.ROOT);
                    courtyard = s.equals("true") || s.equals("1") || s.equals("yes");
                }
                Object cr = m.get("courtyardRatio");
                if (cr != null) {
                    try {
                        courtyardRatio = Double.parseDouble(String.valueOf(cr).trim());
                    } catch (Exception ignored) {}
                }
            }
        }
        if (symmetry == null || symmetry.isBlank()) symmetry = "NONE";
        if (courtyardRatio < 0.2) courtyardRatio = 0.2;
        if (courtyardRatio > 0.8) courtyardRatio = 0.8;

        // shell
        for (int y = 0; y <= h; y++) {
            boolean windowBand = y % 4 == 2 && y <= h - 2;
            // e.g. curtain wall: more bands
            if (windowStyle != null && windowStyle.toLowerCase(java.util.Locale.ROOT).contains("curtain")) {
                windowBand = (y % 2 == 0) && y >= 2 && y <= h - 2;
            }
            for (int x = -halfW; x <= halfW; x++) {
                for (int z = -halfD; z <= halfD; z++) {
                    boolean edge = (Math.abs(x) == halfW) || (Math.abs(z) == halfD);
                    if (!edge) continue;
                    BlockState s = wall;
                    if (windowBand && (Math.abs(x) != halfW || Math.abs(z) != halfD)) {
                        s = glass;
                    }
                    blocks.add(new PlannedBlock(origin.add(x, y, z), s));
                }
            }
        }

        // floors every 4 blocks
        for (int y = 0; y <= h; y += 4) {
            for (int x = -halfW + 1; x <= halfW - 1; x++) {
                for (int z = -halfD + 1; z <= halfD - 1; z++) {
                    if (courtyard) {
                        int cx = Math.max(2, (int) Math.floor(halfW * courtyardRatio));
                        int cz = Math.max(2, (int) Math.floor(halfD * courtyardRatio));
                        if (Math.abs(x) <= cx && Math.abs(z) <= cz) {
                            continue;
                        }
                    }
                    blocks.add(new PlannedBlock(origin.add(x, y, z), floor));
                }
            }
        }

        // --------------------------------------------------------------------
        // BSP floor plan (functional building interior): core -> corridor -> rooms
        // --------------------------------------------------------------------
        FloorPlanConfig fpc = null;
        if (spec != null && spec.getExtra() != null) {
            Object fpl = spec.getExtra().get("floor_plan_logic");
            if (fpl == null) fpl = spec.getExtra().get("floorPlanLogic");
            fpc = FloorPlanConfig.fromExtra(fpl);
        }
        if (fpc != null && !courtyard && w >= 11 && d >= 11) {
            BlockState roomWall = wall;
            if (fpc.partitionStyle != null && fpc.partitionStyle.contains("OPEN")) {
                roomWall = glass;
            }
            BspFloorPlanGenerator.apply(
                    blocks,
                    origin,
                    world,
                    w,
                    d,
                    h,
                    fpc,
                    BspFloorPlanGenerator.Materials.of(wall, roomWall, Blocks.STONE_BRICK_STAIRS.getDefaultState())
            );
            // Avoid stacking legacy partition plans on top of BSP walls.
            plan = "none";
        }

        // internal partitions (Layout IR: extra.layout.plan)
        // Best-effort zoning:
        // - ring_corridor is designed to work WITH courtyard.
        // - other plans are skipped when courtyard is enabled (avoid splitting the open shaft region).
        if (plan != null && !plan.isBlank() && !"none".equalsIgnoreCase(plan)) {
            // We need enough interior space to make the partition meaningful.
            if (w >= 9 && d >= 9) {
                // entrance direction (same as door opening logic below)
                Direction out = Direction.SOUTH;
                if ("NORTH".equals(entranceFacing)) out = Direction.NORTH;
                else if ("EAST".equals(entranceFacing)) out = Direction.EAST;
                else if ("WEST".equals(entranceFacing)) out = Direction.WEST;

                boolean outNS = (out == Direction.NORTH || out == Direction.SOUTH);

                // ring corridor: build a wall rectangle around the courtyard void, leaving a 1-wide gallery,
                // plus 4 openings at midpoints for circulation.
                if ("ring_corridor".equalsIgnoreCase(plan)) {
                    if (courtyard) {
                        int t = (Math.max(w, d) >= 21) ? 2 : 1;
                        int cx = Math.max(2, (int) Math.floor(halfW * courtyardRatio));
                        int cz = Math.max(2, (int) Math.floor(halfD * courtyardRatio));

                        // courtyard void bounds are |x|<=cx and |z|<=cz.
                        // ring wall is offset by (t+1) from the void, leaving a gallery strip of width t.
                        int rx0 = -(cx + (t + 1));
                        int rx1 = +(cx + (t + 1));
                        int rz0 = -(cz + (t + 1));
                        int rz1 = +(cz + (t + 1));

                        // clamp inside interior
                        int xMin = -halfW + 1, xMax = halfW - 1;
                        int zMin = -halfD + 1, zMax = halfD - 1;
                        rx0 = Math.max(xMin + 1, rx0);
                        rx1 = Math.min(xMax - 1, rx1);
                        rz0 = Math.max(zMin + 1, rz0);
                        rz1 = Math.min(zMax - 1, rz1);

                        int midX = (rx0 + rx1) / 2;
                        int midZ = (rz0 + rz1) / 2;
                        int openW = (Math.max(w, d) >= 13) ? 2 : 1;
                        int openX0 = midX - (openW / 2);
                        int openX1 = openX0 + openW - 1;
                        int openZ0 = midZ - (openW / 2);
                        int openZ1 = openZ0 + openW - 1;

                        for (int y0 = 0; y0 <= h; y0 += 4) {
                            int yTop = Math.min(h, y0 + 3);
                            int doorH = 2;
                            for (int y = y0 + 1; y <= yTop; y++) {
                                boolean openingY = y <= y0 + doorH;
                                // north
                                for (int x = rx0; x <= rx1; x++) {
                                    boolean opening = openingY && x >= openX0 && x <= openX1;
                                    if (opening) continue;
                                    blocks.add(new PlannedBlock(origin.add(x, y, rz0), wall));
                                }
                                // south
                                for (int x = rx0; x <= rx1; x++) {
                                    boolean opening = openingY && x >= openX0 && x <= openX1;
                                    if (opening) continue;
                                    blocks.add(new PlannedBlock(origin.add(x, y, rz1), wall));
                                }
                                // west
                                for (int z = rz0; z <= rz1; z++) {
                                    boolean opening = openingY && z >= openZ0 && z <= openZ1;
                                    if (opening) continue;
                                    blocks.add(new PlannedBlock(origin.add(rx0, y, z), wall));
                                }
                                // east
                                for (int z = rz0; z <= rz1; z++) {
                                    boolean opening = openingY && z >= openZ0 && z <= openZ1;
                                    if (opening) continue;
                                    blocks.add(new PlannedBlock(origin.add(rx1, y, z), wall));
                                }
                            }
                        }
                    }
                    // ring_corridor does not combine with other partition modes
                } else if (!courtyard) {
                    // Interpret plan relative to entrance ("front/back" and "left/right" from the door perspective).
                boolean splitPerpToEntrance; // split line is perpendicular to entrance normal => separates front/back zones
                boolean splitAlongEntrance;  // split line is parallel to entrance normal => separates left/right zones
                String p = plan.trim().toLowerCase(java.util.Locale.ROOT);
                if (p.equals("front_back")) {
                    splitPerpToEntrance = true;
                    splitAlongEntrance = false;
                } else if (p.equals("left_right")) {
                    splitPerpToEntrance = false;
                    splitAlongEntrance = true;
                } else {
                    splitPerpToEntrance = false;
                    splitAlongEntrance = false;
                }

                double frontRatio = 0.42; // smaller "front" zone near entrance, larger "back" zone
                int interiorW = w - 2;
                int interiorD = d - 2;
                // interior local coordinate range:
                int xMin = -halfW + 1, xMax = halfW - 1;
                int zMin = -halfD + 1, zMax = halfD - 1;

                // opening width: larger buildings get a 2-wide opening.
                int openW = Math.max(w, d) >= 13 ? 2 : 1;

                for (int y0 = 0; y0 <= h; y0 += 4) {
                    int yTop = Math.min(h, y0 + 3);
                    // Make a 2-high opening for passage.
                    int doorH = 2;

                    for (int y = y0 + 1; y <= yTop; y++) {
                        boolean isOpeningY = (y <= y0 + doorH);

                        if (splitPerpToEntrance) {
                            // Partition axis is along the "left/right" axis; line is X const if out is E/W, else Z const.
                            if (outNS) {
                                int frontLen = Math.max(3, (int) Math.round(interiorD * frontRatio));
                                int zLine = (out == Direction.SOUTH) ? (zMax - frontLen) : (zMin + frontLen);
                                zLine = Math.max(zMin + 1, Math.min(zMax - 1, zLine));

                                int open0 = -((openW - 1) / 2);
                                int open1 = open0 + openW - 1;

                                for (int x = xMin; x <= xMax; x++) {
                                    boolean opening = isOpeningY && x >= open0 && x <= open1;
                                    if (opening) continue;
                                    blocks.add(new PlannedBlock(origin.add(x, y, zLine), wall));
                                }
                            } else {
                                int frontLen = Math.max(3, (int) Math.round(interiorW * frontRatio));
                                int xLine = (out == Direction.EAST) ? (xMax - frontLen) : (xMin + frontLen);
                                xLine = Math.max(xMin + 1, Math.min(xMax - 1, xLine));

                                int open0 = -((openW - 1) / 2);
                                int open1 = open0 + openW - 1;

                                for (int z = zMin; z <= zMax; z++) {
                                    boolean opening = isOpeningY && z >= open0 && z <= open1;
                                    if (opening) continue;
                                    blocks.add(new PlannedBlock(origin.add(xLine, y, z), wall));
                                }
                            }
                        } else if (splitAlongEntrance) {
                            // Split left/right relative to entrance; line is X const when out is N/S, else Z const.
                            if (outNS) {
                                int xLine = 0;
                                // keep a 2-wide corridor aligned to entrance axis by offsetting the wall if very narrow
                                if (interiorW <= 7) xLine = 1;
                                xLine = Math.max(xMin + 1, Math.min(xMax - 1, xLine));

                                int open0 = -((openW - 1) / 2);
                                int open1 = open0 + openW - 1;

                                for (int z = zMin; z <= zMax; z++) {
                                    boolean opening = isOpeningY && z >= open0 && z <= open1;
                                    if (opening) continue;
                                    blocks.add(new PlannedBlock(origin.add(xLine, y, z), wall));
                                }
                            } else {
                                int zLine = 0;
                                if (interiorD <= 7) zLine = 1;
                                zLine = Math.max(zMin + 1, Math.min(zMax - 1, zLine));

                                int open0 = -((openW - 1) / 2);
                                int open1 = open0 + openW - 1;

                                for (int x = xMin; x <= xMax; x++) {
                                    boolean opening = isOpeningY && x >= open0 && x <= open1;
                                    if (opening) continue;
                                    blocks.add(new PlannedBlock(origin.add(x, y, zLine), wall));
                                }
                            }
                        }
                    }
                }
                }
            }
        }

        // internal lights (low density): one light per floor, near the core
        if (paletteId != null && !paletteId.isBlank()) {
            BlockState internal = Blocks.SEA_LANTERN.getDefaultState();
            internal = PaletteResolver.pick(world, paletteId, "INTERNAL_LIGHT", origin, 0x0FF1CE19L, internal);
            internal = PaletteResolver.pick(world, paletteId, "LIGHTING", origin, 0x0FF1CE1AL, internal);
            for (int y = 2; y <= h; y += 4) {
                // If courtyard is enabled, keep lights off the open shaft.
                if (courtyard) blocks.add(new PlannedBlock(origin.add(2, y, 2), internal));
                else blocks.add(new PlannedBlock(origin.add(0, y, 0), internal));
            }
        }

        // flat roof
        for (int x = -halfW; x <= halfW; x++) {
            for (int z = -halfD; z <= halfD; z++) {
                if (courtyard) {
                    int cx = Math.max(2, (int) Math.floor(halfW * courtyardRatio));
                    int cz = Math.max(2, (int) Math.floor(halfD * courtyardRatio));
                    if (Math.abs(x) <= cx && Math.abs(z) <= cz) {
                        continue; // open roof over courtyard/atrium
                    }
                }
                blocks.add(new PlannedBlock(origin.add(x, h + 1, z), roof));
            }
        }

        // eavesProfile: parapet / neon strip
        String eavesProfile = details != null ? details.eavesProfile : null;
        if (eavesProfile != null && !eavesProfile.isBlank()) {
            String ep = eavesProfile.trim().toLowerCase(java.util.Locale.ROOT);
            if (ep.contains("parapet")) {
                for (int x = -halfW; x <= halfW; x++) {
                    blocks.add(new PlannedBlock(origin.add(x, h + 2, -halfD), wall));
                    blocks.add(new PlannedBlock(origin.add(x, h + 2, halfD), wall));
                }
                for (int z = -halfD; z <= halfD; z++) {
                    blocks.add(new PlannedBlock(origin.add(-halfW, h + 2, z), wall));
                    blocks.add(new PlannedBlock(origin.add(halfW, h + 2, z), wall));
                }
            }
            if (ep.contains("neon")) {
                BlockState light = Blocks.SEA_LANTERN.getDefaultState();
                if (paletteId != null && !paletteId.isBlank()) {
                    light = PaletteResolver.pick(world, paletteId, "LIGHTING", origin, 0x0FF1CE11L, light);
                    light = PaletteResolver.pick(world, paletteId, "ROAD_LIGHT", origin, 0x0FF1CE12L, light);
                }
                for (int x = -halfW; x <= halfW; x += 3) {
                    blocks.add(new PlannedBlock(origin.add(x, h + 2, -halfD), light));
                    blocks.add(new PlannedBlock(origin.add(x, h + 2, halfD), light));
                }
            }
        }

        // simple door opening (default: south side). layout.entranceFacing controls which side.
        Direction out = Direction.SOUTH;
        if ("NORTH".equals(entranceFacing)) out = Direction.NORTH;
        else if ("EAST".equals(entranceFacing)) out = Direction.EAST;
        else if ("WEST".equals(entranceFacing)) out = Direction.WEST;
        int ex = 0;
        int ez = halfD;
        if (out == Direction.NORTH) ez = -halfD;
        else if (out == Direction.SOUTH) ez = halfD;
        else if (out == Direction.EAST) ex = halfW;
        else if (out == Direction.WEST) ex = -halfW;

        BlockPos doorPos = origin.add(ex, 1, ez);
        blocks.add(new PlannedBlock(doorPos, Blocks.AIR.getDefaultState()));
        blocks.add(new PlannedBlock(doorPos.up(), Blocks.AIR.getDefaultState()));
        Direction doorFacing = out.getOpposite(); // door faces into the building
        BlockState doorLower = Blocks.IRON_DOOR.getDefaultState()
                .with(Properties.HORIZONTAL_FACING, doorFacing)
                .with(Properties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.LOWER);
        BlockState doorUpper = Blocks.IRON_DOOR.getDefaultState()
                .with(Properties.HORIZONTAL_FACING, doorFacing)
                .with(Properties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.UPPER);
        blocks.add(new PlannedBlock(doorPos, doorLower));
        blocks.add(new PlannedBlock(doorPos.up(), doorUpper));

        // ornamentProfile: signage / banners near entrance
        String ornament = details != null ? details.ornamentProfile : null;
        if (ornament != null && !ornament.isBlank()) {
            String op = ornament.trim().toLowerCase(java.util.Locale.ROOT);
            if (op.contains("cyber") || op.contains("sign")) {
                // place signage just outside the entrance side
                Direction side = out;
                BlockPos base = doorPos.up(2).offset(side, 1).offset(side.rotateYClockwise(), 1);
                BlockPos signPos = base;
                BlockPos lightPos = base.up();
                BlockState signBlock = Blocks.CYAN_STAINED_GLASS.getDefaultState();
                BlockState lightBlock = Blocks.SEA_LANTERN.getDefaultState();
                if (paletteId != null && !paletteId.isBlank()) {
                    signBlock = PaletteResolver.pick(world, paletteId, "ROAD_SIGNAGE", signPos, 0x0FF1CE13L, signBlock);
                    signBlock = PaletteResolver.pick(world, paletteId, "DECOR_DETAIL", signPos, 0x0FF1CE14L, signBlock);
                    lightBlock = PaletteResolver.pick(world, paletteId, "LIGHTING", lightPos, 0x0FF1CE15L, lightBlock);
                    lightBlock = PaletteResolver.pick(world, paletteId, "ROAD_LIGHT", lightPos, 0x0FF1CE16L, lightBlock);
                }
                blocks.add(new PlannedBlock(signPos, signBlock));
                blocks.add(new PlannedBlock(lightPos, lightBlock));

                // symmetry X/BOTH: mirror signage to the other side of the entrance
                if ("X".equals(symmetry) || "BOTH".equals(symmetry)) {
                    BlockPos signPos2 = doorPos.up(2).offset(side, 1).offset(side.rotateYClockwise(), -1);
                    BlockPos lightPos2 = signPos2.up();
                    BlockState sb2 = signBlock;
                    BlockState lb2 = lightBlock;
                    if (paletteId != null && !paletteId.isBlank()) {
                        sb2 = PaletteResolver.pick(world, paletteId, "ROAD_SIGNAGE", signPos2, 0x0FF1CE23L, sb2);
                        sb2 = PaletteResolver.pick(world, paletteId, "DECOR_DETAIL", signPos2, 0x0FF1CE24L, sb2);
                        lb2 = PaletteResolver.pick(world, paletteId, "LIGHTING", lightPos2, 0x0FF1CE25L, lb2);
                        lb2 = PaletteResolver.pick(world, paletteId, "ROAD_LIGHT", lightPos2, 0x0FF1CE26L, lb2);
                    }
                    blocks.add(new PlannedBlock(signPos2, sb2));
                    blocks.add(new PlannedBlock(lightPos2, lb2));
                }
            } else if (op.contains("banner")) {
                Direction side = out;
                BlockPos b1p = doorPos.up(1).offset(side, 1).offset(side.rotateYClockwise(), 1);
                BlockPos b2p = doorPos.up(1).offset(side, 1).offset(side.rotateYClockwise(), -1);
                BlockState b1 = Blocks.RED_WALL_BANNER.getDefaultState();
                BlockState b2 = Blocks.RED_WALL_BANNER.getDefaultState();
                if (paletteId != null && !paletteId.isBlank()) {
                    b1 = PaletteResolver.pick(world, paletteId, "BANNER", b1p, 0x0FF1CE17L, b1);
                    b2 = PaletteResolver.pick(world, paletteId, "BANNER", b2p, 0x0FF1CE18L, b2);
                }
                blocks.add(new PlannedBlock(b1p, b1));
                blocks.add(new PlannedBlock(b2p, b2));
            }
        }

        String desc = String.format("OfficeBlock (w=%d,d=%d,h=%d)", w, d, h);
        return new GeneratedStructure(null, origin, desc, blocks);
    }

    // (Moved) BSP floor plan logic is now a reusable "meta-assembly primitive":
    // see com.formacraft.server.interior.BspFloorPlanGenerator / FloorPlanConfig

    private BlockState getStateOrDefault(ServerWorld world, String id, BlockState defaultState) {
        if (id == null || id.isBlank()) return defaultState;
        try {
            var ident = net.minecraft.util.Identifier.tryParse(id);
            if (ident == null) return defaultState;
            return net.minecraft.registry.Registries.BLOCK.get(ident).getDefaultState();
        } catch (Exception e) {
            return defaultState;
        }
    }
}


