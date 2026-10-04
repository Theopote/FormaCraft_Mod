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
    @Test void exposedTerracesUseFloorMaterialWithoutFillingRoomsOrFootprintVoids() {
        MinecraftRegistryTestBootstrap.initialize();
        for (String pattern : List.of("rectangle", "l_shape", "courtyard")) {
            var params = new HashMap<String,Object>();
            params.putAll(Map.of("anchor_mode","min_corner","floor_height",4,"setback_ratio",0.2,
                    "hollow",true,"suppress_windows",true,"suppress_doors",true,
                    "floor_block","minecraft:oak_planks","plan_type",pattern));
            var body = new Component("MASS_MAIN",null,new Vec3i(20,10,30),new Dimensions(15,13,12),
                    List.of("stepped_facade","hollow"),params);
            var semantic = new SemanticComponent("MASS_MAIN",null,body);
            var layers = ResolvedFacadeLayers.resolve(semantic,15,13,12);
            var mask = com.formacraft.common.generation.component.util.ComponentFootprintMask.from(semantic,params,15,13);
            var declarations = new ArrayList<com.formacraft.common.generation.component.util.GeneratedSurfaceCapture.Cell>();
            List<BlockPatch> patches;
            try (var capture = com.formacraft.common.generation.component.util.GeneratedSurfaceCapture.captureTo(declarations::add)) {
                patches = new MassMainGenerator().generate(semantic);
            }
            var cells = new HashMap<BlockPos,BlockPatch>();
            for (var p : patches) cells.put(new BlockPos(p.dx(),p.dy(),p.dz()),p);
            int checked = 0;
            for (int y : List.of(3,7)) for (int x=0;x<15;x++) for(int z=0;z<13;z++) {
                var position = new BlockPos(20+x,10+y,30+z);
                if (!mask.contains(x,z)) assertFalse(cells.containsKey(position),"Footprint void must remain open");
                else if (ResolvedFacadeLayers.exposedAbove(layers,y,x,z)) {
                    assertNotNull(cells.get(position),"Exposed shoulder must be sealed");
                    assertEquals("minecraft:oak_planks",cells.get(position).targetBlock());
                    assertTrue(declarations.stream().anyMatch(c -> c.position().equals(position)
                            && c.role() == com.formacraft.common.generation.component.util.GeneratedSurfaceCapture.Role.ROOF));
                    checked++;
                }
            }
            assertTrue(checked>0);
            assertFalse(cells.containsKey(new BlockPos(27,13,36)),"Unexposed room must remain hollow");
            assertTrue(com.formacraft.common.generation.component.util.GeneratedSurfaceCapture.missing(declarations,patches).isEmpty());
            assertFalse(ResolvedFacadeLayers.exposedAbove(layers,11,7,6),"Final roof is handled separately");
        }
    }
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
