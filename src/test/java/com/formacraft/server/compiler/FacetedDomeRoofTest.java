package com.formacraft.server.compiler;

import com.formacraft.common.compiler.semantic.SemanticComponent;
import com.formacraft.common.generation.component.impl.RoofGenerator;
import com.formacraft.common.generation.component.util.*;
import com.formacraft.common.llm.dto.*;
import org.junit.jupiter.api.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class FacetedDomeRoofTest {
    @BeforeAll static void bootstrap() { com.formacraft.test.MinecraftRegistryTestBootstrap.initialize(); }
    private Component roof(String type,int segments) {
        return new Component("ROOF","tower",new Vec3i(0,20,0),new Dimensions(17,17,7),List.of(),
                Map.of("roof_type",type,"segments",segments,"roof_block","minecraft:black_concrete"));
    }
    @Test void acceptedDomeNamesGenerateRaisedHollowCaps() {
        for(var type:List.of("polyhedral_dome","faceted_dome","polygonal_dome")) {
            var c=roof(type,8);
            assertEquals(RoofTypeKind.DOME,RoofTypeCatalog.parseExplicit(type).orElseThrow());
            assertTrue(RoofDesignValidator.check(LlmPlanTestFixtures.builder().components(List.of(c)).build(),List.of(c)).isEmpty());
            var patches=new RoofGenerator().generate(new SemanticComponent("ROOF",null,c));
            assertEquals(26,patches.stream().mapToInt(p -> p.dy()).max().orElseThrow());
            assertTrue(patches.stream().anyMatch(p -> p.dx()==8 && p.dz()==8 && p.dy()==26));
            assertFalse(patches.stream().anyMatch(p -> p.dx()==8 && p.dz()==8 && p.dy()==20));
        }
    }
    @Test void explicitFacetCountChangesGeometry() {
        var generator=new RoofGenerator();
        var four=generator.generate(new SemanticComponent("ROOF",null,roof("polyhedral_dome",4)));
        var eight=generator.generate(new SemanticComponent("ROOF",null,roof("polyhedral_dome",8)));
        assertNotEquals(new HashSet<>(four),new HashSet<>(eight));
    }
}
