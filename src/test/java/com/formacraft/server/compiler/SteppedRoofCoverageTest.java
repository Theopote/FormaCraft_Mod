package com.formacraft.server.compiler;

import com.formacraft.common.compiler.semantic.SemanticComponent;
import com.formacraft.common.generation.component.impl.MassMainGenerator;
import com.formacraft.common.generation.component.util.ResolvedFacadeLayers;
import com.formacraft.common.llm.dto.*;
import com.formacraft.common.patch.BlockPatch;
import com.formacraft.test.MinecraftRegistryTestBootstrap;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class SteppedRoofCoverageTest {
    @Test void actualSteppedWallsRemainSolidAndTopRoofUsesSameLayerRectangle() {
        MinecraftRegistryTestBootstrap.initialize();
        var body = new Component("MASS_MAIN","house",new Vec3i(0,0,0),new Dimensions(15,13,12),
                List.of("stepped_facade","hollow"),Map.of("component_id","house","anchor_mode","min_corner",
                        "floor_height",4,"setbackRatio","0.2","hollow",true,"suppress_windows",true,"suppress_doors",true));
        var semantic = new SemanticComponent("MASS_MAIN",null,body);
        var layers = ResolvedFacadeLayers.resolve(semantic,15,13,12);
        var cells = new HashMap<BlockPos,BlockPatch>();
        for (var p : new MassMainGenerator().generate(semantic)) cells.put(new BlockPos(p.dx(),p.dy(),p.dz()),p);
        for (int y : List.of(4,8)) {
            var layer = layers[y];
            assertTrue(layer.xOffset > 0);
            assertNotNull(cells.get(new BlockPos(layer.xOffset,y,layer.zOffset+1)),"Retreated wall must not be hollowed away");
            assertFalse(cells.containsKey(new BlockPos(layer.xOffset+2,y,layer.zOffset+2)),"Interior remains hollow");
        }
        var top = layers[11];
        var roof = new Component("ROOF","house",new Vec3i(top.xOffset,11,top.zOffset),
                new Dimensions(top.width,top.depth,1),List.of(),Map.of("roof_type","flat","host_id","house"));
        var resolved = FlatRoofCoverageValidator.resolve(new SemanticComponent("ROOF",null,roof),BlockPos.ORIGIN).orElseThrow();
        var patches = resolved.core().stream().map(p -> new BlockPatch(BlockPatch.PLACE,p.getX(),p.getY(),p.getZ(),"minecraft:stone_bricks")).toList();
        assertTrue(FlatRoofCoverageValidator.checkHost(semantic,List.of(resolved),BlockPos.ORIGIN,patches,Set.of()).isEmpty());
        var shortRoof = new ArrayList<>(patches); shortRoof.removeFirst();
        assertEquals(1,FlatRoofCoverageValidator.checkHost(semantic,List.of(resolved),BlockPos.ORIGIN,shortRoof,Set.of()).orElseThrow().count());
    }
    @Test void ratioAlonePreservesLegacyActivationAndUppercaseFeaturesActivateStepping() {
        for (var features : List.of(List.<String>of(),List.of("STEPPED_FACADE"))) {
            var c = new Component("MASS_MAIN",null,new Vec3i(0,0,0),new Dimensions(15,13,12),features,
                    Map.of("floor_height",4,"setback_ratio",0.2));
            var layers = ResolvedFacadeLayers.resolve(new SemanticComponent("MASS_MAIN",null,c),15,13,12);
            assertEquals(features.isEmpty() ? 15 : 9,layers[11].width);
        }
    }
}
