package com.formacraft.server.build;

import com.formacraft.common.generation.component.util.StyleWallPattern;
import com.formacraft.common.generation.component.impl.MassMainGenerator;
import com.formacraft.common.compiler.semantic.SemanticComponent;
import com.formacraft.common.llm.dto.*;
import org.junit.jupiter.api.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class StyleWallPatternTest {
    @BeforeAll static void bootstrap() { com.formacraft.test.MinecraftRegistryTestBootstrap.initialize(); }

    @Test void timberFrameHasPostsRailsAndDiagonalsWithInfill() {
        var params=Map.<String,Object>of("wall_pattern","timber_frame");
        assertEquals("log",StyleWallPattern.apply("wall","log",params,0,2,0,13,13,4));
        assertEquals("log",StyleWallPattern.apply("wall","log",params,1,1,0,13,13,4));
        assertEquals("wall",StyleWallPattern.apply("wall","log",params,1,2,0,13,13,4));
        assertEquals("wall",StyleWallPattern.apply("wall","log",Map.of("wall_pattern","smooth"),1,4,0,13,13,4));
    }

    @Test void emissionIsExplicitAndRejectsNonLuminousOrUnknownBlocks() {
        var params=new HashMap<String,Object>(Map.of("wall_pattern","neon_bands","emissive_enabled",true));
        assertEquals("minecraft:sea_lantern",StyleWallPattern.apply("wall","trim",params,1,4,0,13,13,4));
        assertEquals("wall",StyleWallPattern.apply("wall","trim",params,1,3,0,13,13,4));
        params.put("emissive_enabled",false);
        assertEquals("wall",StyleWallPattern.apply("wall","trim",params,1,4,0,13,13,4));
        params.put("emissive_enabled",true);
        for(String material:List.of("minecraft:cyan_concrete","minecraft:does_not_exist")) {
            params.put("emissive_block",material);
            assertThrows(IllegalArgumentException.class,()->StyleWallPattern.apply("wall","trim",params,1,4,0,13,13,4));
        }
    }

    @Test void actualWallsKeepWindowsWhenEmissionIsEnabled() {
        var params=new HashMap<String,Object>(Map.of("component_id","hall","anchor_mode","min_corner",
                "wall_block","minecraft:black_concrete","trim_block","minecraft:cyan_concrete",
                "window_block","minecraft:glass","window_ratio",.4,"roof_type","none","floor_height",4));
        var generator=new MassMainGenerator();
        var base=new Component("MASS_MAIN",null,new Vec3i(0,0,0),new Dimensions(13,17,10),List.of(),Map.copyOf(params));
        var baseline=generator.generate(new SemanticComponent("MASS_MAIN",null,base));
        params.put("wall_pattern","neon_bands"); params.put("emissive_enabled",true);
        var neon=new Component("MASS_MAIN",null,base.relativePosition(),base.dimensions(),List.of(),params);
        var output=generator.generate(new SemanticComponent("MASS_MAIN",null,neon));
        var windows=baseline.stream().filter(p->p.targetBlock().equals("minecraft:glass")).toList();
        assertFalse(windows.isEmpty()); assertTrue(output.containsAll(windows));
        assertTrue(output.stream().anyMatch(p->p.targetBlock().equals("minecraft:sea_lantern")));
    }

    @Test void fullCompilerPreservesNeonToggleAndSideGear() throws Exception {
        var mapper=new com.fasterxml.jackson.databind.ObjectMapper();
        var plan=mapper.readValue(java.nio.file.Path.of("python_backend/eval/fixtures/plans/p2_cyberpunk_block_13.json").toFile(),LlmPlan.class);
        var enabled=com.formacraft.server.compiler.ComponentPlanCompiler.compile(plan,net.minecraft.util.math.BlockPos.ORIGIN,null,null,false);
        assertTrue(enabled.stream().anyMatch(p->p.targetBlock().equals("minecraft:sea_lantern")));
        plan.components().getFirst().params().put("emissive_enabled",false);
        var disabled=com.formacraft.server.compiler.ComponentPlanCompiler.compile(plan,net.minecraft.util.math.BlockPos.ORIGIN,null,null,false);
        assertFalse(disabled.isEmpty());
        assertFalse(disabled.stream().anyMatch(p->p.targetBlock().equals("minecraft:sea_lantern")));
        var steam=mapper.readValue(java.nio.file.Path.of("python_backend/eval/fixtures/plans/p2_steampunk_hall_13.json").toFile(),LlmPlan.class);
        var patches=com.formacraft.server.compiler.ComponentPlanCompiler.compile(steam,net.minecraft.util.math.BlockPos.ORIGIN,null,null,false);
        assertTrue(patches.stream().anyMatch(p->p.dx()>=18 && p.targetBlock().equals("minecraft:waxed_cut_copper")));
    }
}
