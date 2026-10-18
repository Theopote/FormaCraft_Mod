package com.formacraft.server.compiler;

import com.formacraft.common.compiler.semantic.SemanticComponent;
import com.formacraft.common.generation.component.impl.EntranceGenerator;
import com.formacraft.common.generation.component.impl.FacadeWindowsGenerator;
import com.formacraft.common.generation.component.util.*;
import com.formacraft.common.llm.dto.*;
import com.formacraft.common.patch.BlockPatch;
import org.junit.jupiter.api.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class Oct18NightCompatibilityTest {
    @BeforeAll static void bootstrap() { com.formacraft.test.MinecraftRegistryTestBootstrap.initialize(); }
    @Test void stairDoorInfersSideFromThinOpeningWhenFacingIsOmitted() throws Exception {
        var mass=new Component("MASS_MAIN","research",new Vec3i(0,0,0),new Dimensions(13,11,6),List.of(),
                Map.of("component_id","research","anchor_mode","min_corner","floor_height",3,"floor_count",2));
        var door=new Component("ENTRANCE","research",new Vec3i(12,0,2),new Dimensions(1,3,3),List.of(),
                Map.of("host_id","research","stair_connection","stair","door_width",3,"door_height",4));
        var method=ComponentPlanCompiler.class.getDeclaredMethod("alignStairEntrance",Component.class,Component.class); method.setAccessible(true);
        var aligned=(Component)method.invoke(null,door,mass);
        assertEquals("WEST",aligned.params().get("facing"));
        assertEquals(1,aligned.dimensions().width()); assertEquals(5,aligned.dimensions().depth());
        var patches=new EntranceGenerator().generate(new SemanticComponent("ENTRANCE",null,aligned));
        assertEquals(3,patches.stream().filter(p->BlockPatch.REMOVE.equals(p.action()) && p.dy()==0).count());
    }
    @Test void tallHallWindowsUseClearHeightWithoutShrinkingExplicitOpening() throws Exception {
        var method=ComponentPlanCompiler.class.getDeclaredMethod("alignFacadeToMass",Component.class,Component.class,LlmPlan.class,GlobalConstraints.Facing.class);
        method.setAccessible(true);
        for(boolean explicitFloors:List.of(false,true)) {
            var params=new HashMap<String,Object>(Map.of("component_id","chapel","anchor_mode","min_corner","floor_height",3));
            if(explicitFloors) params.put("floor_count",4);
            var mass=new Component("MASS_MAIN","chapel",new Vec3i(0,0,0),new Dimensions(23,11,12),List.of(),params);
            var windows=new Component("FACADE_WINDOWS","chapel",mass.relativePosition(),mass.dimensions(),List.of("wrap"),
                    Map.of("host_id","chapel","window_width",1,"window_height",8,"sill_height",2,"glass_block","minecraft:glass"));
            var plan=LlmPlanTestFixtures.builder().components(List.of(mass,windows)).build();
            var aligned=(Component)method.invoke(null,windows,mass,plan,GlobalConstraints.Facing.SOUTH);
            assertEquals(8,aligned.params().get("window_height"));
            assertEquals(explicitFloors?4:1,aligned.params().get("floor_count"));
            if(!explicitFloors) assertFalse(new FacadeWindowsGenerator().generate(new SemanticComponent("FACADE_WINDOWS",null,aligned)).isEmpty());
        }
    }
    @Test void narrowReceivingDeckHasGoalsOnlyWhereItIsStandable() {
        var bank=new Component("MASS_ANCILLARY","s",new Vec3i(-6,1,6),new Dimensions(2,5,1),List.of(),
                Map.of("anchor_mode","min_corner","extrude_mode","plate","plate_role","terrace"));
        var patches=new ArrayList<BlockPatch>();
        for(int x=-6;x<-4;x++) for(int z=6;z<11;z++) patches.add(new BlockPatch(BlockPatch.PLACE,x,1,z,"minecraft:stone_bricks"));
        var goals=BridgeWalkability.storeyGoalsPreferringInterior(PlannedOccupancy.fromPatches(patches),bank,1,new Vec3i(0,0,0));
        assertEquals(1,goals.goals().size());
        assertTrue(BridgeWalkability.storeyGoalsPreferringInterior(PlannedOccupancy.fromPatches(List.of()),bank,1,new Vec3i(0,0,0)).isEmpty());
    }
}
