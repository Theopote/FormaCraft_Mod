package com.formacraft.server.compiler;

import com.formacraft.common.llm.parser.LlmPlanParser;
import com.formacraft.common.patch.*;
import com.formacraft.server.assembly.AssemblyCompileDiagnostics;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.*;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class Oct18BuildingGeometryTest {
    @BeforeAll static void bootstrap() { com.formacraft.test.MinecraftRegistryTestBootstrap.initialize(); }
    private Map<BlockPos,BlockPatch> compile(int index) throws Exception {
        var plan=LlmPlanParser.parse(Files.readString(Path.of("python_backend/tests/fixtures/complex_build_retest/oct18_"+index+".normalized.json")));
        var output=ComponentPlanCompiler.compile(plan,BlockPos.ORIGIN,null,null);
        assertFalse(output.isEmpty(),String.valueOf(AssemblyCompileDiagnostics.get()));
        var blocks=new HashMap<BlockPos,BlockPatch>();
        for(var p:output) blocks.put(new BlockPos(p.dx(),p.dy(),p.dz()),p);
        return blocks;
    }
    private boolean solid(Map<BlockPos,BlockPatch> blocks,int x,int y,int z) {
        var p=blocks.get(new BlockPos(x,y,z));
        return p!=null && BlockPatch.PLACE.equals(p.action()) && !BlockPatchTargetResolver.resolve(p).isAir();
    }
    @Test void raisedTowerDoorAndResearchLandingRemainUsableAfterPostprocessing() throws Exception {
        var blocks=compile(3);
        assertTrue(solid(blocks,0,8,-26),"Destination stair landing must have a floor");
        for(int x=-1;x<=1;x++) for(int y=9;y<=10;y++)
            assertFalse(solid(blocks,x,y,-26),"Upper doorway must retain full stair-width headroom");
        assertTrue(solid(blocks,-7,4,-13),"Research doorway must have a receiving platform");
        assertFalse(solid(blocks,-6,5,-13));
        assertFalse(solid(blocks,-6,6,-13));
    }
    @Test void chapelRoofJoinsAcrossSteepRowsAndSealsGableWithoutFillingAtticOrSiteCourt() throws Exception {
        var blocks=compile(6);
        assertTrue(solid(blocks,17,18,5),"Chapel ridge must cover the former inherited courtyard hole");
        assertTrue(solid(blocks,17,17,5),"Steep roof rows need a joining riser");
        assertTrue(solid(blocks,6,16,5),"Gable end must be enclosed");
        assertFalse(solid(blocks,17,15,5),"Attic must remain hollow");
        assertFalse(solid(blocks,24,10,18),"The shared courtyard must stay open to the sky");
    }
}
