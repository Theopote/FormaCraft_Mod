package com.formacraft.server.terrain;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TerrainPadHeightTest {
    @org.junit.jupiter.api.BeforeAll static void bootstrap() {
        com.formacraft.test.MinecraftRegistryTestBootstrap.initialize();
    }
    @Test void fillStartsAtFirstAirWithoutLeavingAHoleAboveTheSurface() {
        assertEquals(64,TerrainFit.padFillStart(64,65,4));
        assertEquals(64,TerrainFit.padFillStart(64,64,4));
        assertEquals(69,TerrainFit.padFillStart(64,73,4)); // Retain the shallow-fill budget.
    }

    @Test void adaptivePadTouchesOnlyBearingColumnsAndRespectsSelection() {
        var footprint = java.util.List.of(new net.minecraft.util.math.BlockPos(0,4,0),new net.minecraft.util.math.BlockPos(12,4,0));
        java.util.function.Function<net.minecraft.util.math.BlockPos,net.minecraft.block.BlockState> terrain =
                p -> p.getY() <= 0 ? net.minecraft.block.Blocks.STONE.getDefaultState() : net.minecraft.block.Blocks.AIR.getDefaultState();
        var pad = TerrainFit.adaptivePadOnTerrain(terrain,footprint,4,net.minecraft.block.Blocks.COBBLESTONE.getDefaultState(),4,0,true,true);
        assertEquals(8,pad.size());
        assertTrue(pad.stream().allMatch(b -> b.getPos().getX()==0 || b.getPos().getX()==12));
        assertTrue(com.formacraft.server.build.StructureSupportAnalysis.unsupportedBases(pad,terrain).isEmpty());
        var req = new com.formacraft.common.model.request.FormaRequest();
        req.setSelectionMin(new net.minecraft.util.math.BlockPos(0,0,0));
        req.setSelectionMax(new net.minecraft.util.math.BlockPos(0,8,0));
        var clipped = com.formacraft.server.build.BuildConstraintContext.withRequest(req, () ->
                TerrainFit.adaptivePadOnTerrain(terrain,footprint,4,net.minecraft.block.Blocks.COBBLESTONE.getDefaultState(),4,0,true,true));
        assertEquals(4,clipped.size());
        assertTrue(clipped.stream().allMatch(b -> b.getPos().getX()==0));
    }
}
