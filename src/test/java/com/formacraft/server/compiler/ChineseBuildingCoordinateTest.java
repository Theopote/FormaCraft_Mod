package com.formacraft.server.compiler;

import com.formacraft.server.generation.typology.builder.TailiangTimberHallBuilder;
import com.formacraft.server.generation.typology.builder.CourtyardCompoundBuilder;
import com.formacraft.common.model.build.BuildingSpec;
import net.minecraft.util.math.BlockPos;
import net.minecraft.block.BlockState;
import org.junit.jupiter.api.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ChineseBuildingCoordinateTest {
    @BeforeAll static void bootstrap() { com.formacraft.test.MinecraftRegistryTestBootstrap.initialize(); }
    @Test void hallCenterAndInternalCornerHaveIdenticalTranslatedGeometry() {
        for(String facing:List.of("SOUTH","NORTH","EAST","WEST")) for(boolean sub:List.of(false,true)) {
            var anchor=new BlockPos(80,64,-120);
            int hx=(25+(sub?6:0))/2,hz=(15+(sub?6:0))/2;
            var corner=switch(facing) {
                case "NORTH" -> anchor.add(hx,0,hz);
                case "EAST" -> anchor.add(-hz,0,hx);
                case "WEST" -> anchor.add(hz,0,-hx);
                default -> anchor.add(-hx,0,-hz);
            };
            var params=new HashMap<String,Object>(Map.of("width",25,"depth",15,"facing",facing,"includeSubEaves",sub));
            var centered=TailiangTimberHallBuilder.generate(params,anchor,null,null);
            params.put("anchorMode","corner");
            var internal=TailiangTimberHallBuilder.generate(params,corner,null,null);
            var a=new HashMap<BlockPos,BlockState>(); var b=new HashMap<BlockPos,BlockState>();
            centered.getBlocks().forEach(p->a.put(p.getPos(),p.getTargetState()));
            internal.getBlocks().forEach(p->b.put(p.getPos(),p.getTargetState()));
            assertEquals(a,b); assertEquals(anchor,centered.getOrigin());
            assertNotNull(a.get(anchor)); assertFalse(a.get(anchor).isAir(),"anchor on foundation center");
        }
    }
    @Test void courtyardUsesExplicitDimensionsAndBoundsThem() {
        var spec=new BuildingSpec();
        spec.setExtra(Map.of("width",45,"depth",61));
        assertArrayEquals(new int[]{45,61},CourtyardCompoundBuilder.resolvedDimensions(spec));
        spec.setExtra(Map.of("width",2,"depth",200));
        assertArrayEquals(new int[]{16,96},CourtyardCompoundBuilder.resolvedDimensions(spec));
    }

    @Test void hallLandingUsesActualFoundationEnvelopeAcrossFacings() {
        for(String facing:List.of("SOUTH","NORTH","EAST","WEST")) for(boolean sub:List.of(false,true)) {
            var params=Map.<String,Object>of("width",25,"depth",15,"facing",facing,"includeSubEaves",sub);
            var component=new com.formacraft.common.llm.dto.Component("STRUCTURE",null,
                    new com.formacraft.common.llm.dto.Vec3i(0,0,0),
                    new com.formacraft.common.llm.dto.Dimensions(90,90,90),
                    List.of("typology:tailiang_timber_hall"),params);
            var plan=com.formacraft.common.llm.dto.LlmPlanTestFixtures.builder()
                    .mode(com.formacraft.common.llm.dto.LlmPlan.Mode.build).components(List.of(component)).build();
            var anchor=new BlockPos(80,64,-120);
            var sites=com.formacraft.server.terrain.BuildingLandingPlanner.sites(plan,anchor);
            assertEquals(1,sites.size());
            var bounds=sites.getFirst().body();
            var generated=TailiangTimberHallBuilder.generate(params,anchor,null,null);
            var states=new HashMap<BlockPos,BlockState>();
            generated.getBlocks().forEach(b->states.put(b.getPos(),b.getTargetState()));
            long foundation=states.entrySet().stream().filter(e->e.getKey().getY()==64 && !e.getValue().isAir()
                    && e.getKey().getX()>=bounds.minX() && e.getKey().getX()<=bounds.maxX()
                    && e.getKey().getZ()>=bounds.minZ() && e.getKey().getZ()<=bounds.maxZ()).count();
            assertEquals((long)bounds.width()*bounds.depth(),foundation);
            for(int x=bounds.minX();x<=bounds.maxX();x++) for(int z=bounds.minZ();z<=bounds.maxZ();z++)
                assertFalse(states.get(new BlockPos(x,64,z)).isAir());
            assertEquals(anchor.getX(),(bounds.minX()+bounds.maxX())/2);
            assertEquals(anchor.getZ(),(bounds.minZ()+bounds.maxZ())/2);
        }
    }
}
