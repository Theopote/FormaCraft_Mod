package com.formacraft.server.compiler;

import com.formacraft.common.llm.dto.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class HostedFloorBoundaryTest {
    @Test void hostedFloorsPreserveThickExteriorWallsAndExplicitExposedEdges() throws Exception {
        for(boolean exposed:List.of(false,true)) {
            var mass=new Component("MASS_MAIN","main",new Vec3i(0,0,0),new Dimensions(25,17,11),List.of(),
                    Map.of("component_id","house","anchor_mode","min_corner","floor_height",5,"floor_count",2,"wall_thickness",2));
            var plate=new Component("MASS_SECONDARY","main",new Vec3i(0,5,0),new Dimensions(25,17,1),List.of(),
                    Map.of("component_id","floor","host_id","house","anchor_mode","min_corner","extrude_mode","plate","material","minecraft:spruce_planks","exposed_floor_edges",exposed));
            var plan=LlmPlanTestFixtures.builder().mode(LlmPlan.Mode.build).components(List.of(mass,plate)).build();
            var method=ComponentPlanCompiler.class.getDeclaredMethod("prepareComponents",LlmPlan.class,Map.class,boolean.class);
            method.setAccessible(true);
            var prepared=method.invoke(null,plan,Map.of(),false);
            var getter=prepared.getClass().getDeclaredMethod("components"); getter.setAccessible(true);
            @SuppressWarnings("unchecked") var components=(List<Component>)getter.invoke(prepared);
            var result=components.stream().filter(c->"floor".equals(c.params().get("component_id"))).findFirst().orElseThrow();
            assertEquals(new Vec3i(exposed?0:2,5,exposed?0:2),result.relativePosition());
            assertEquals(new Dimensions(exposed?25:21,exposed?17:13,1),result.dimensions());
        }
    }
}
