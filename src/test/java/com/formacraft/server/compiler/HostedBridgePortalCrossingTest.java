package com.formacraft.server.compiler;

import com.formacraft.common.generation.component.util.HostedPlateOccupancy;
import com.formacraft.common.llm.dto.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class HostedBridgePortalCrossingTest {
    @Test void overlappingDeckOpensTheCrossedFaceOnBothAxes() {
        com.formacraft.test.MinecraftRegistryTestBootstrap.initialize();
        for(boolean rotate:List.of(false,true)) {
            var mass=new Component("MASS_MAIN","s",new Vec3i(rotate?23:8,0,rotate?8:23),
                    new Dimensions(rotate?9:11,rotate?11:9,10),List.of(),Map.of("anchor_mode","min_corner"));
            var bridge=new Component("MASS_SECONDARY","s",new Vec3i(rotate?17:12,5,rotate?12:17),
                    new Dimensions(rotate?10:3,rotate?3:10,1),List.of(),Map.of("anchor_mode","min_corner","extrude_mode","plate","plate_role","bridge"));
            var patches=HostedPlateOccupancy.bridgePortalRemovals(bridge,mass);
            assertEquals(6,patches.size());
            assertTrue(patches.stream().allMatch(p->(rotate?p.dx():p.dz())==23));
            assertTrue(patches.stream().allMatch(p->p.dy()==6 || p.dy()==7));
            assertEquals(3,patches.stream().map(p->rotate?p.dz():p.dx()).distinct().count());
        }
    }
}
