package com.formacraft.server.compiler;

import com.formacraft.common.compiler.semantic.SemanticComponent;
import com.formacraft.common.generation.component.impl.MassMainGenerator;
import com.formacraft.common.generation.component.impl.RoofGenerator;
import com.formacraft.common.generation.component.util.*;
import com.formacraft.common.llm.dto.*;
import org.junit.jupiter.api.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class Oct18LateCompatibilityTest {
    @BeforeAll static void bootstrap() { com.formacraft.test.MinecraftRegistryTestBootstrap.initialize(); }
    @Test void pyramidalAliasRetainsPyramidGeometry() {
        var generator=new RoofGenerator();
        var outputs=new ArrayList<Object>();
        for(String type:List.of("pyramid","pyramidal")) {
            var roof=new Component("ROOF","s",new Vec3i(0,20,0),new Dimensions(7,7,5),List.of(),
                    Map.of("roof_type",type,"roof_block","minecraft:dark_oak_planks","anchor_mode","min_corner"));
            assertEquals(RoofTypeKind.PYRAMID,RoofTypeCatalog.parseExplicit(type).orElseThrow());
            var patches=generator.generate(new SemanticComponent("ROOF",null,roof));
            assertFalse(patches.isEmpty());
            outputs.add(patches);
        }
        assertEquals(outputs.get(0),outputs.get(1));
    }
    @Test void waterChannelRemainsAFluidPlate() {
        var channel=new Component("MASS_SECONDARY","s",new Vec3i(0,0,0),new Dimensions(4,18,1),List.of(),
                Map.of("extrude_mode","plate","plate_role","water_channel","material","minecraft:water","anchor_mode","min_corner"));
        var patches=new MassMainGenerator().generate(new SemanticComponent("MASS_SECONDARY",null,channel));
        assertEquals(72,patches.size());
        assertTrue(patches.stream().allMatch(p->p.targetBlock().startsWith("minecraft:water")));
    }
}
