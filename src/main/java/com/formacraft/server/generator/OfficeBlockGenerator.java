package com.formacraft.server.generator;

import com.formacraft.common.model.build.BuildingSpec;
import com.formacraft.server.build.GeneratedStructure;
import com.formacraft.server.build.PlannedBlock;
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

        BlockState wall = getStateOrDefault(world, spec != null && spec.getMaterials() != null ? spec.getMaterials().getWall() : null,
                Blocks.LIGHT_GRAY_CONCRETE.getDefaultState());
        BlockState glass = getStateOrDefault(world, spec != null && spec.getMaterials() != null ? spec.getMaterials().getWindow() : null,
                Blocks.GLASS_PANE.getDefaultState());
        BlockState floor = getStateOrDefault(world, spec != null && spec.getMaterials() != null ? spec.getMaterials().getFloor() : null,
                Blocks.SMOOTH_STONE.getDefaultState());
        BlockState roof = getStateOrDefault(world, spec != null && spec.getMaterials() != null ? spec.getMaterials().getRoof() : null,
                Blocks.SMOOTH_STONE.getDefaultState());

        List<PlannedBlock> blocks = new ArrayList<>(Math.max(4000, w * d * h / 2));

        int halfW = w / 2;
        int halfD = d / 2;

        // shell
        for (int y = 0; y <= h; y++) {
            boolean windowBand = (y % 4 == 2) && y >= 2 && y <= h - 2;
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

        // simple door opening (south side)
        blocks.add(new PlannedBlock(origin.add(0, 1, halfD), Blocks.AIR.getDefaultState()));
        blocks.add(new PlannedBlock(origin.add(0, 2, halfD), Blocks.AIR.getDefaultState()));
        blocks.add(new PlannedBlock(origin.add(0, 1, halfD), Blocks.IRON_DOOR.getDefaultState()));

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


