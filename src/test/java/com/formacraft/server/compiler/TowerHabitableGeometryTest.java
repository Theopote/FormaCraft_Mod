package com.formacraft.server.compiler;

import com.formacraft.common.compiler.semantic.SemanticComponent;
import com.formacraft.common.generation.component.impl.TowerComponentGenerator;
import com.formacraft.common.llm.dto.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class TowerHabitableGeometryTest {
    @org.junit.jupiter.api.BeforeAll static void bootstrap() {
        com.formacraft.test.MinecraftRegistryTestBootstrap.initialize();
    }

    @Test void storeyedTowerHasInteriorAndUpperFloorWithoutNarrativeFeatureTokens() {
        var params = new HashMap<String, Object>(Map.of("floor_count",2,"floor_height",4,
                "wall_thickness",1,"floor_block","minecraft:oak_planks","wall_block","minecraft:stone_bricks"));
        var c = new Component("TOWER",null,new Vec3i(0,0,0),new Dimensions(9,9,8),List.of(),params);
        var patches = new TowerComponentGenerator().generate(new SemanticComponent("TOWER",null,c,"DEFAULT"));
        assertTrue(patches.stream().anyMatch(p -> p.dx()==0 && p.dy()==4 && p.dz()==0
                && "minecraft:oak_planks".equals(p.targetBlock())));
        assertFalse(patches.stream().anyMatch(p -> p.dx()==0 && p.dy()==5 && p.dz()==0));
        assertTrue(patches.stream().anyMatch(p -> p.dx()==4 && p.dy()==5 && p.dz()==0));
        params.put("hollow",false);
        var solid = new TowerComponentGenerator().generate(new SemanticComponent("TOWER",null,c,"DEFAULT"));
        assertTrue(solid.stream().anyMatch(p -> p.dx()==0 && p.dy()==5 && p.dz()==0));
    }
}
