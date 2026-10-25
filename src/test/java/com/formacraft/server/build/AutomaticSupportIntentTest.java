package com.formacraft.server.build;

import com.formacraft.common.model.request.FormaRequest;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AutomaticSupportIntentTest {
    @org.junit.jupiter.api.BeforeAll static void initialize() {
        com.formacraft.test.MinecraftRegistryTestBootstrap.initialize();
    }
    @Test void refusedSupportsDoNotGrowTenFencesIntoTwentyFiveBlocks() {
        var request=new FormaRequest(); request.setUserMessage("不要石台、地基、屋顶或额外支撑柱");
        var blocks=new java.util.ArrayList<com.formacraft.common.build.PlannedBlock>();
        for(int x=0;x<10;x++) blocks.add(new com.formacraft.common.build.PlannedBlock(
                new net.minecraft.util.math.BlockPos(x,64,0),net.minecraft.block.Blocks.OAK_FENCE.getDefaultState()));
        var result=BuildAutoRepair.applyToTerrain(
                p->p.getY()<62?net.minecraft.block.Blocks.STONE.getDefaultState():net.minecraft.block.Blocks.AIR.getDefaultState(),
                0,net.minecraft.block.Blocks.COBBLESTONE.getDefaultState(),blocks,
                BuildPreviewPipeline.allowsAutomaticSupports(request));
        assertEquals(10,result.blocks().size());
        assertEquals(0,result.supportBlocksAdded());
    }
    @Test void loggedFenceRefusalSurvivesQualityRepair() {
        var request=new FormaRequest();
        request.setUserMessage("使用橡木栅栏方块，直接立在现有地面上，\n不要石台、地基、\n屋顶或额外支撑柱。");
        assertFalse(BuildPreviewPipeline.allowsAutomaticSupports(request));
        request.setUserMessage("建一座木屋，用橡木柱支撑");
        assertTrue(BuildPreviewPipeline.allowsAutomaticSupports(request));
        assertTrue(BuildPreviewPipeline.allowsAutomaticSupports(null));
    }
}
