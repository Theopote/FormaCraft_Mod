package com.formacraft.server.terrain;

import com.formacraft.common.build.PlannedBlock;
import com.formacraft.common.model.constraint.ProtectedZone;
import com.formacraft.common.model.request.FormaRequest;
import com.formacraft.server.build.BuildConstraintContext;
import com.formacraft.test.MinecraftRegistryTestBootstrap;
import net.minecraft.block.Blocks;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TerrainShaperConstraintTest {
    @BeforeAll
    static void bootstrap() {
        MinecraftRegistryTestBootstrap.initialize();
    }

    @Test
    void emitSkipsBlocksOutsideSelection() {
        var req = new FormaRequest();
        req.setSelectionMin(new BlockPos(0, 0, 0));
        req.setSelectionMax(new BlockPos(2, 8, 2));
        var ops = new ArrayList<PlannedBlock>();
        var air = Blocks.AIR.getDefaultState();
        BuildConstraintContext.withRequest(req, () -> {
            TerrainShaper.emit(ops, new BlockPos(1, 4, 1), air);
            TerrainShaper.emit(ops, new BlockPos(9, 4, 1), air);
        });
        assertEquals(1, ops.size());
        assertEquals(new BlockPos(1, 4, 1), ops.getFirst().getPos());
    }

    @Test
    void emitSkipsProtectedZones() {
        var req = new FormaRequest();
        req.setSelectionMin(new BlockPos(-10, 0, -10));
        req.setSelectionMax(new BlockPos(10, 20, 10));
        req.setProtectedZones(List.of(new ProtectedZone(new BlockPos(0, 0, 0), new BlockPos(2, 5, 2))));
        var ops = new ArrayList<PlannedBlock>();
        var fill = Blocks.DIRT.getDefaultState();
        BuildConstraintContext.withRequest(req, () -> {
            TerrainShaper.emit(ops, new BlockPos(1, 2, 1), fill);
            TerrainShaper.emit(ops, new BlockPos(5, 2, 5), fill);
        });
        assertEquals(1, ops.size());
        assertEquals(new BlockPos(5, 2, 5), ops.getFirst().getPos());
        assertEquals(fill, ops.getFirst().getTargetState());
    }

    @Test
    void emitAllowsAllWhenNoConstraintContext() {
        var ops = new ArrayList<PlannedBlock>();
        TerrainShaper.emit(ops, new BlockPos(3, 4, 5), Blocks.STONE.getDefaultState());
        assertEquals(1, ops.size());
    }
}
