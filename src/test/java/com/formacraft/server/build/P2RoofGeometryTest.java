package com.formacraft.server.build;

import com.formacraft.common.compiler.semantic.SemanticComponent;
import com.formacraft.common.generation.component.impl.RoofGenerator;
import com.formacraft.common.generation.component.util.*;
import com.formacraft.common.llm.dto.*;
import org.junit.jupiter.api.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class P2RoofGeometryTest {
    @BeforeAll static void bootstrap() { com.formacraft.test.MinecraftRegistryTestBootstrap.initialize(); }

    private Component roof(String type, int span, int height) {
        return new Component("ROOF", "hall", new Vec3i(0,20,0), new Dimensions(span,span+4,height),
                List.of(), Map.of("roof_type",type,"roof_block","minecraft:hay_block","gable_walls",false));
    }

    @Test void allSpecialtyRoofsExecuteAtTwoScalesWithoutFillingInterior() {
        for (String type : List.of("gambrel","barrel_vault","onion_dome","steep_thatch")) {
            for (int span : List.of(13,21)) {
                int height=span;
                var patches=new RoofGenerator().generate(new SemanticComponent("ROOF",null,roof(type,span,height)));
                assertFalse(patches.isEmpty(),type);
                assertEquals(20+height-1,patches.stream().mapToInt(p->p.dy()).max().orElseThrow(),type);
                assertFalse(patches.stream().anyMatch(p->p.dx()==span/2 && p.dz()==(span+4)/2 && p.dy()==20),type);
                assertTrue(patches.stream().allMatch(p->p.dx()>=0 && p.dx()<span && p.dz()>=0 && p.dz()<span+4),type);
            }
        }
    }

    @Test void profilesAreDistinctAndOnionBulgesAboveItsNeck() {
        var profiles=new HashSet<Set<?>>();
        for (String type:List.of("gambrel","barrel_vault","onion_dome","steep_thatch"))
            profiles.add(new HashSet<>(new RoofGenerator().generate(new SemanticComponent("ROOF",null,roof(type,21,21)))));
        assertEquals(4,profiles.size());
        var onion=SpecialtyRoofSurfaces.generate(RoofTypeKind.ONION_DOME,0,0,0,21,21,21,"minecraft:hay_block",null,0);
        int neck=onion.stream().filter(p->p.dy()==0).mapToInt(p->p.dx()).max().orElseThrow();
        assertTrue(onion.stream().filter(p->p.dy()>0).mapToInt(p->p.dx()).max().orElseThrow()>neck);
    }

    @Test void steepThatchRejectsShallowGeometryAndAliasesRemainExecutable() {
        assertThrows(IllegalArgumentException.class,()->SpecialtyRoofSurfaces.generate(
                RoofTypeKind.STEEP_THATCH,0,0,0,21,25,4,"minecraft:hay_block",null,0));
        for(String alias:List.of("折线双坡顶","筒拱屋顶","洋葱穹顶","陡坡茅草顶"))
            assertTrue(RoofTypeCatalog.parseExplicit(alias).isPresent(),alias);
    }

    @Test void authoredPlansCompileThroughTheComponentPipeline() throws Exception {
        var mapper=new com.fasterxml.jackson.databind.ObjectMapper();
        var manifest=mapper.readTree(java.nio.file.Path.of("python_backend/eval/fixtures/p2_roofs.json").toFile());
        int count=0;
        for(var entry:manifest) for(var fixture:entry.path("plans")) {
            var plan=mapper.readValue(java.nio.file.Path.of("python_backend/eval/fixtures/plans",fixture.asText()).toFile(),LlmPlan.class);
            var patches=com.formacraft.server.compiler.ComponentPlanCompiler.compile(plan,
                    new net.minecraft.util.math.BlockPos(0,64,0),null,null,false);
            assertFalse(patches.isEmpty(),fixture.asText());
            assertTrue(patches.stream().anyMatch(p->p.targetBlock()!=null && p.targetBlock().contains("hay_block")),fixture.asText());
            var origin=new net.minecraft.util.math.BlockPos(0,64,0);
            var min=origin.add(0,-32,-32);
            var max=origin.add(6,128,64);
            var bounds=new BuildConstraints(min,max,null,List.of());
            var expected=patches.stream().filter(p->bounds.allow(origin.add(p.dx(),p.dy(),p.dz()))).toList();
            assertFalse(expected.isEmpty());
            assertTrue(expected.size()<patches.size());
            assertEquals(expected,com.formacraft.server.patch.ServerPatchFilter.filter(
                    origin,patches,List.of(),null,true,min,max).accepted);
            var snapshot=new com.formacraft.common.tool.ToolConstraintSnapshot(
                    new com.formacraft.common.buildcontext.SelectionBox(min,max),null,List.of(),List.of(),
                    com.formacraft.common.tool.ToolConstraintSnapshot.SymmetryConstraint.none());
            assertEquals(expected,com.formacraft.common.patch.filter.PatchFilterPipeline.createDefault().apply(
                    patches,origin,new com.formacraft.common.patch.filter.PatchFilterContext(snapshot)));
            count++;
        }
        assertEquals(8,count);
    }
}
