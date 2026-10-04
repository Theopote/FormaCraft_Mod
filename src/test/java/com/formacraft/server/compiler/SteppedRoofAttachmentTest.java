package com.formacraft.server.compiler;

import com.formacraft.common.llm.dto.*;
import com.formacraft.common.compiler.semantic.SemanticComponent;
import com.formacraft.common.generation.component.impl.RoofGenerator;
import com.formacraft.test.MinecraftRegistryTestBootstrap;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class SteppedRoofAttachmentTest {
    @Test void nestedAnnexUsesRootLayerPolicyAndKeepsItsOwnTopFrame() throws Exception {
        MinecraftRegistryTestBootstrap.initialize();
        var params = new HashMap<>(body("rectangle").params());
        params.put("masses",List.of(Map.of("offset",Map.of("x",15,"y",0,"z",2),
                "dimensions",Map.of("width",10,"depth",8,"height",8),"setback_ratio",0.9)));
        var base = new Component("MASS_MAIN","house",new Vec3i(0,0,0),new Dimensions(15,13,12),
                List.of("stepped_facade","hollow"),params);
        var plan = LlmPlanTestFixtures.builder().mode(LlmPlan.Mode.build).components(List.of(base)).build();
        var components = prepared(plan);
        var roof = components.stream().filter(c -> c.params()!=null && "house#mass_1".equals(c.params().get("host_part_id")))
                .findFirst().orElseThrow();
        assertEquals(new Vec3i(16,7,3),roof.relativePosition());
        assertEquals(8,roof.dimensions().width()); assertEquals(6,roof.dimensions().depth());
        var patches = new RoofGenerator().generate(new SemanticComponent("ROOF",null,roof));
        assertTrue(patches.stream().anyMatch(p -> p.dx()==16 && p.dy()==7 && p.dz()==3));
        assertFalse(patches.stream().anyMatch(p -> p.dx()<16 || p.dx()>23 || p.dz()<3 || p.dz()>8));
        assertFalse(ComponentPlanCompiler.compile(plan,BlockPos.ORIGIN,null,null,false).isEmpty(),
                String.valueOf(com.formacraft.server.assembly.AssemblyCompileDiagnostics.get()));
        var markerCheck = ComponentPlanCompiler.class.getDeclaredMethod("isResolvedPartRoof",Component.class,List.class,LlmPlan.class);
        markerCheck.setAccessible(true);
        assertEquals(true,markerCheck.invoke(null,roof,components,plan));
        var badSize = new Component("ROOF",roof.slotId(),roof.relativePosition(),new Dimensions(10,8,1),roof.features(),roof.params());
        assertEquals(false,markerCheck.invoke(null,badSize,components,plan));
        var badHost = new HashMap<>(roof.params()); badHost.put("host_id","other_house");
        assertEquals(false,markerCheck.invoke(null,new Component("ROOF",roof.slotId(),roof.relativePosition(),roof.dimensions(),roof.features(),badHost),components,plan));
    }
    private Component body(String shape) {
        return new Component("MASS_MAIN","house",new Vec3i(20,0,30),new Dimensions(15,13,12),
                List.of("stepped_facade","hollow"),Map.of("component_id","house","anchor_mode","min_corner",
                        "floor_height",4,"setback_ratio",0.2,"shape",shape,"roof_type","flat","overhang",0,
                        "suppress_windows",true,"suppress_doors",true));
    }
    @SuppressWarnings("unchecked")
    private List<Component> prepared(LlmPlan plan) throws Exception {
        var method = ComponentPlanCompiler.class.getDeclaredMethod("prepareComponents",LlmPlan.class,Map.class,boolean.class);
        method.setAccessible(true);
        var result = method.invoke(null,plan,Map.of(),false);
        var accessor = result.getClass().getDeclaredMethod("components"); accessor.setAccessible(true);
        return (List<Component>) accessor.invoke(result);
    }
    @Test void inferredRoofUsesRetreatedTopFrameAndActualRoofBlocks() throws Exception {
        MinecraftRegistryTestBootstrap.initialize();
        var plan = LlmPlanTestFixtures.builder().mode(LlmPlan.Mode.build).components(List.of(body("rectangle"))).build();
        var roof = prepared(plan).stream().filter(c -> "ROOF".equals(c.componentType())).findFirst().orElseThrow();
        assertEquals(new Vec3i(23,11,32),roof.relativePosition());
        assertEquals(9,roof.dimensions().width()); assertEquals(8,roof.dimensions().depth());
        var patches = new RoofGenerator().generate(new SemanticComponent("ROOF",null,roof));
        assertTrue(patches.stream().anyMatch(p -> p.dx()==23 && p.dy()==11 && p.dz()==32));
        assertFalse(patches.stream().anyMatch(p -> p.dx()<23 || p.dx()>31 || p.dz()<32 || p.dz()>39));
        assertFalse(ComponentPlanCompiler.compile(plan,BlockPos.ORIGIN,null,null,false).isEmpty());
    }
    @Test void explicitOverhangHeightAndLegacyWideRoofIntentSurviveAlignment() throws Exception {
        for (var dims : List.of(new Dimensions(9,8,4),new Dimensions(15,13,4))) {
            var authored = new Component("ROOF","house",new Vec3i(0,0,0),dims,List.of(),
                    Map.of("host_id","house","roof_type","flat","overhang",2));
            var plan = LlmPlanTestFixtures.builder().mode(LlmPlan.Mode.build).components(List.of(body("rectangle"),authored)).build();
            var roof = prepared(plan).stream().filter(c -> "ROOF".equals(c.componentType())).findFirst().orElseThrow();
            assertEquals(new Vec3i(23,11,32),roof.relativePosition());
            assertEquals(4,roof.dimensions().height());
            assertEquals(dims.width()==9 ? 2 : 3, ((Number)roof.params().get("overhang")).intValue());
        }
    }
    @Test void curvedFootprintIsNotRebasedIntoSmallerMask() throws Exception {
        var plan = LlmPlanTestFixtures.builder().mode(LlmPlan.Mode.build).components(List.of(body("circle"))).build();
        var roof = prepared(plan).stream().filter(c -> "ROOF".equals(c.componentType())).findFirst().orElseThrow();
        assertEquals(new Vec3i(20,11,30),roof.relativePosition());
        assertEquals(15,roof.dimensions().width()); assertEquals(13,roof.dimensions().depth());
    }
}
