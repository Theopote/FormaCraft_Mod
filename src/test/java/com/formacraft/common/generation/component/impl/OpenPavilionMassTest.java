package com.formacraft.common.generation.component.impl;

import com.formacraft.common.compiler.semantic.SemanticComponent;
import com.formacraft.common.llm.dto.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class OpenPavilionMassTest {
    @Test void openPavilionHasActualPitchedRoofAboveClearBody() {
        var mass=new Component("MASS_MAIN",null,new Vec3i(0,0,0),new Dimensions(5,5,4),List.of(),
                Map.of("anchor_mode","min_corner","open",true,"wall_thickness",0,
                        "roof_type","gable","roof_block","minecraft:spruce_planks"));
        var blocks=new MassMainGenerator().generate(new SemanticComponent("MASS_MAIN",null,mass));
        assertTrue(blocks.stream().anyMatch(b->b.dy()>=4 && b.targetBlock().contains("spruce")));
        assertTrue(blocks.stream().filter(b->b.dy()>=4 && !b.targetBlock().equals("minecraft:air"))
                .map(b->b.dy()).distinct().count()>1,"Pitched roof must occupy several heights");
        assertTrue(blocks.stream().anyMatch(b->b.dx()==2 && b.dz()==0 && b.dy()==2
                && b.targetBlock().equals("minecraft:air")));
    }
    @Test void loggedZeroThicknessOpenMassHasFloorPostsAndClearSides() {
        var mass = new Component("MASS_MAIN",null,new Vec3i(0,0,0),new Dimensions(5,5,5),List.of(),
                Map.of("anchor_mode","min_corner","open",true,"wall_thickness",0,"void_ratio",1.0,
                        "roof_type","none","wall_block","minecraft:oak_log","floor_block","minecraft:oak_planks"));
        var blocks = new MassMainGenerator().generate(new SemanticComponent("MASS_MAIN",null,mass));
        assertFalse(blocks.isEmpty());
        for (int y=1;y<4;y++) {
            final int level=y;
            for (int[] p : new int[][]{{2,0},{2,4},{0,2},{4,2},{2,2}}) {
                assertTrue(blocks.stream().anyMatch(b->b.dx()==p[0] && b.dz()==p[1]
                        && b.dy()==level && "minecraft:air".equals(b.targetBlock())),"Clear passage");
            }
            assertTrue(blocks.stream().anyMatch(b->b.dx()==0 && b.dz()==0 && b.dy()==level
                    && b.targetBlock().contains("oak_log")),"Corner post");
        }
        assertTrue(blocks.stream().anyMatch(b->b.dx()==2 && b.dz()==2 && b.dy()==0
                && b.targetBlock().contains("oak_planks")),"Paved floor");
    }
}
