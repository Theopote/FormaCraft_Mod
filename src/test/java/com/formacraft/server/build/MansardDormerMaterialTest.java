package com.formacraft.server.build;

import com.formacraft.common.compiler.semantic.SemanticComponent;
import com.formacraft.common.generation.component.impl.RoofGenerator;
import com.formacraft.common.llm.dto.*;
import com.formacraft.common.patch.BlockPatch;
import org.junit.jupiter.api.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class MansardDormerMaterialTest {
    @BeforeAll static void bootstrap() { com.formacraft.test.MinecraftRegistryTestBootstrap.initialize(); }

    private List<BlockPatch> roof(String material,String trim,String face) {
        var c=new Component("ROOF","hall",new Vec3i(0,20,0),new Dimensions(17,21,11),List.of(),
                Map.of("roof_type","mansard","roof_block",material,"wall_block",trim,
                        "mansard_break_run",2,"mansard_break_rise",7,"mansard_dormer_count",1,"dormer_face",face));
        return new RoofGenerator().generate(new SemanticComponent("ROOF",null,c));
    }

    private String base(String block) { return block.split("\\[",2)[0]; }

    @Test void materialsWithoutStairsStayInTheirAuthoredFamiliesOnEveryFace() {
        for(String material:List.of("minecraft:hay_block","minecraft:red_terracotta","minecraft:blue_concrete"))
            for(String face:List.of("north","south","east","west")) {
                var patches=roof(material,"minecraft:white_concrete",face);
                var allowed=Set.of(material,"minecraft:white_concrete","minecraft:glass_pane","minecraft:air");
                assertTrue(patches.stream().allMatch(p -> allowed.contains(base(p.targetBlock()))),material+" "+face);
                assertTrue(patches.stream().anyMatch(p -> p.dy()>30 && material.equals(base(p.targetBlock()))));
            }
    }

    @Test void roofAndLintelUseTheirOwnStairFamiliesWhenAvailable() {
        for(String face:List.of("north","south","east","west")) {
            var patches=roof("minecraft:oak_planks","minecraft:cobblestone",face);
            assertTrue(patches.stream().anyMatch(p -> p.dy()>30 && base(p.targetBlock()).equals("minecraft:oak_stairs")));
            assertTrue(patches.stream().anyMatch(p -> p.targetBlock().startsWith("minecraft:cobblestone_stairs[")
                    && p.targetBlock().contains("half=top")));
            assertFalse(patches.stream().anyMatch(p -> base(p.targetBlock()).equals("minecraft:stone_brick_stairs")));
        }
    }

    @Test void highRoofRunsHaveClosedCheeksAndCeilingsButKeepTheRoomOpen() {
        for(String face:List.of("north","south","east","west")) {
            var patches=roof("minecraft:hay_block","minecraft:white_concrete",face);
            var finalAt=new HashMap<List<Integer>,BlockPatch>();
            for(var p:patches) finalAt.put(List.of(p.dx(),p.dy(),p.dz()),p);
            var window=patches.stream().filter(p -> p.dy()==28 && base(p.targetBlock()).equals("minecraft:glass_pane"))
                    .findFirst().orElseThrow();
            int dx=switch(face) {case "west" -> 1;case "east" -> -1;default -> 0;};
            int dz=switch(face) {case "north" -> 1;case "south" -> -1;default -> 0;};
            int x=window.dx()+2*dx,z=window.dz()+2*dz;
            assertEquals("minecraft:hay_block",base(finalAt.get(List.of(x,31,z)).targetBlock()),face);
            for(int side:List.of(-1,1)) {
                int cheekX=x+(dz!=0 ? side : 0),cheekZ=z+(dx!=0 ? side : 0);
                var cheek=finalAt.get(List.of(cheekX,31,cheekZ));
                assertEquals(BlockPatch.PLACE,cheek.action());
                assertEquals("minecraft:white_concrete",base(cheek.targetBlock()),face);
            }
            var room=finalAt.get(List.of(x,29,z));
            assertEquals(BlockPatch.REMOVE,room.action(),face);
            assertEquals("minecraft:air",base(room.targetBlock()),face);
        }
    }
}
