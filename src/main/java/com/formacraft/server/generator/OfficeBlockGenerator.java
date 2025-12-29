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
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.List;

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
            if (wallId == null || wallId.isBlank()) {
                wall = PaletteResolver.pick(world, paletteId, "WALL_BASE", origin, 0x0FF1CEBL, wall);
            }
            if (windowId == null || windowId.isBlank()) {
                // Modern: prefer WINDOW; if the palette uses curtain-wall, it can still map WINDOW to panes.
                glass = PaletteResolver.pick(world, paletteId, "WINDOW", origin, 0x0FF1CEB1L, glass);
            }
            if (floorId == null || floorId.isBlank()) {
                floor = PaletteResolver.pick(world, paletteId, "FLOORING", origin, 0x0FF1CEF0L, floor);
            }
            if (roofId == null || roofId.isBlank()) {
                roof = PaletteResolver.pick(world, paletteId, "ROOF_TILE", origin, 0x0FF1CE0FL, roof);
                roof = PaletteResolver.pick(world, paletteId, "FLOORING", origin, 0x0FF1CE10L, roof);
            }
        }

        List<PlannedBlock> blocks = new ArrayList<>(Math.max(4000, w * d * h / 2));

        int halfW = w / 2;
        int halfD = d / 2;

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
                    blocks.add(new PlannedBlock(origin.add(x, y, z), floor));
                }
            }
        }

        // flat roof
        for (int x = -halfW; x <= halfW; x++) {
            for (int z = -halfD; z <= halfD; z++) {
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

        // simple door opening (south side)
        blocks.add(new PlannedBlock(origin.add(0, 1, halfD), Blocks.AIR.getDefaultState()));
        blocks.add(new PlannedBlock(origin.add(0, 2, halfD), Blocks.AIR.getDefaultState()));
        blocks.add(new PlannedBlock(origin.add(0, 1, halfD), Blocks.IRON_DOOR.getDefaultState()));

        // ornamentProfile: signage / banners near entrance
        String ornament = details != null ? details.ornamentProfile : null;
        if (ornament != null && !ornament.isBlank()) {
            String op = ornament.trim().toLowerCase(java.util.Locale.ROOT);
            if (op.contains("cyber") || op.contains("sign")) {
                BlockPos signPos = origin.add(1, 3, halfD + 1);
                BlockPos lightPos = origin.add(1, 4, halfD + 1);
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
            } else if (op.contains("banner")) {
                BlockPos b1p = origin.add(1, 2, halfD + 1);
                BlockPos b2p = origin.add(-1, 2, halfD + 1);
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


