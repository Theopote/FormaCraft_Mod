package com.formacraft.server.compiler;

import com.formacraft.common.llm.dto.*;
import com.formacraft.common.generation.component.util.*;
import org.junit.jupiter.api.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class AccessConnectionGeometryTest {
    @BeforeAll static void bootstrap() { com.formacraft.test.MinecraftRegistryTestBootstrap.initialize(); }

    private Component body(String id, int x, int z, int w, int d) {
        return new Component("MASS_MAIN","s",new Vec3i(x,0,z),new Dimensions(w,d,6),List.of(),
                Map.of("component_id",id,"anchor_mode","min_corner","floor_height",6,"floor_count",1,
                        "roof_type","flat","window_ratio",0));
    }

    @Test void courtyardDefaultEntrancesActuallyOpenInward() {
        var north=body("north",0,-7,11,7);
        var south=body("south",0,11,11,7);
        var west=body("west",-7,0,7,11);
        var east=body("east",11,0,7,11);
        var court=new Component("COURTYARD_SPACE","s",new Vec3i(0,0,0),new Dimensions(11,11,1),List.of(),
                Map.of("component_id","court","anchor_mode","min_corner"));
        var plan=LlmPlanTestFixtures.builder().components(List.of(north,south,west,east,court)).build();
        var patches=ComponentPlanCompiler.compile(plan);
        assertFalse(patches.isEmpty(),String.valueOf(com.formacraft.server.assembly.AssemblyCompileDiagnostics.get()));
        var occupancy=BridgeWalkability.occupancyFromPatches(patches);
        for(var door:List.of(new Vec3i(5,0,-1),new Vec3i(5,0,11),new Vec3i(-1,0,5),new Vec3i(11,0,5))) {
            assertTrue(WalkabilityGraph.canStand(occupancy,door),"Inward doorway must have floor and two-block clearance: "+door);
        }
    }

    @Test void receivingPlatformDoorKeepsUpperElevationAndSide() throws Exception {
        var mass=body("body",0,0,13,11);
        var door=new Component("ENTRANCE","s",new Vec3i(12,3,4),new Dimensions(1,3,3),List.of(),
                Map.of("host_id","body","facing","WEST","stair_connection","stairs","door_width",3,"door_height",2,"entrance_at_deck_level",true));
        var method=ComponentPlanCompiler.class.getDeclaredMethod("alignEntranceToMass",Component.class,Component.class,LlmPlan.class,GlobalConstraints.Facing.class);
        method.setAccessible(true);
        var plan=LlmPlanTestFixtures.builder().components(List.of(mass,door)).build();
        var aligned=(Component)method.invoke(null,door,mass,plan,GlobalConstraints.Facing.SOUTH);
        var patches=new com.formacraft.common.generation.component.impl.EntranceGenerator().generate(
                new com.formacraft.common.compiler.semantic.SemanticComponent("ENTRANCE",null,aligned));
        assertEquals(4,aligned.relativePosition().y());
        assertEquals("WEST",aligned.params().get("facing"));
        assertEquals(6,patches.stream().filter(p->com.formacraft.common.patch.BlockPatch.REMOVE.equals(p.action())).count());
        assertTrue(patches.stream().filter(p->com.formacraft.common.patch.BlockPatch.REMOVE.equals(p.action()))
                .allMatch(p->p.dx()==12 && p.dy()>=4 && p.dy()<=5));
    }

    @Test void bridgeEndpointsRemainPassableAfterPostProcessing() {
        var left=body("left",0,0,11,11);
        var right=body("right",18,0,11,11);
        var bridge=new Component("MASS_SECONDARY","s",new Vec3i(10,0,4),new Dimensions(9,3,1),List.of(),
                Map.of("component_id","bridge","host_id","left","target_host_id","right",
                        "anchor_mode","min_corner","extrude_mode","plate","plate_role","bridge","connection_level",0));
        var plan=LlmPlanTestFixtures.builder().components(List.of(left,right,bridge)).build();
        var patches=ComponentPlanCompiler.compile(plan,net.minecraft.util.math.BlockPos.ORIGIN,null,null);
        assertFalse(patches.isEmpty(),String.valueOf(com.formacraft.server.assembly.AssemblyCompileDiagnostics.get()));
        var occupancy=BridgeWalkability.occupancyFromPatches(patches);
        for(int x=9;x<=19;x++) {
            assertTrue(WalkabilityGraph.canStand(occupancy,new Vec3i(x,0,5)),
                    "Bridge must open both building walls and reach their interiors at x="+x);
        }
    }
}
