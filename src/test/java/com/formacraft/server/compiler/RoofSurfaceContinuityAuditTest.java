package com.formacraft.server.compiler;

import com.formacraft.common.compiler.semantic.SemanticComponent;
import com.formacraft.common.generation.component.impl.RoofGenerator;
import com.formacraft.common.llm.dto.*;
import com.formacraft.common.patch.BlockPatch;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class RoofSurfaceContinuityAuditTest {
    @BeforeAll static void bootstrap() { com.formacraft.test.MinecraftRegistryTestBootstrap.initialize(); }

    @Test void steepGableHasJoinedRowsAndRemovingRisersIsRejected() {
        var roof = new Component("ROOF","chapel",new Vec3i(0,12,0),new Dimensions(23,11,8),List.of(),
                Map.of("roof_type","gable","roof_height",8,"overhang",1,"roof_block","minecraft:dark_oak_planks"));
        var semantic = new SemanticComponent("ROOF",null,roof);
        var patches = new RoofGenerator().generate(semantic);
        assertTrue(RoofSurfaceContinuityAudit.inspect(semantic,patches,BlockPos.ORIGIN,Set.of()).isEmpty());
        var tops = new HashMap<BlockPos,BlockPatch>();
        for (var p : patches) {
            var col = new BlockPos(p.dx(),0,p.dz()); var old=tops.get(col);
            if (old==null || old.dy()<p.dy()) tops.put(col,p);
        }
        var gap = RoofSurfaceContinuityAudit.inspect(semantic,new ArrayList<>(tops.values()),BlockPos.ORIGIN,Set.of());
        assertEquals("E_ROOF_SURFACE_DISCONNECTED",gap.orElseThrow().code());
    }

    @Test void northLightRoofHonorsAxisCountAndGlazingAndMissingColumnFails() {
        var roof = new Component("ROOF","factory",new Vec3i(0,10,0),new Dimensions(24,12,4),List.of(),
                Map.of("roof_type","sawtooth","roof_block","minecraft:deepslate_tiles",
                        "sawtooth_axis","x","sawtooth_count",4,"glazing_block","minecraft:gray_stained_glass"));
        var semantic = new SemanticComponent("ROOF",null,roof);
        var patches = new RoofGenerator().generate(semantic);
        assertTrue(RoofSurfaceContinuityAudit.inspect(semantic,patches,BlockPos.ORIGIN,Set.of()).isEmpty());
        for (int bay=0;bay<4;bay++) {
            final int x=bay*6;
            assertTrue(patches.stream().anyMatch(p -> p.dx()==x && p.dz()==5 && p.dy()==13
                    && "minecraft:gray_stained_glass".equals(p.targetBlock())));
        }
        assertFalse(patches.stream().anyMatch(p -> "minecraft:glass".equals(p.targetBlock())));
        var damaged=patches.stream().filter(p -> p.dx()!=8 || p.dz()!=5).toList();
        assertTrue(RoofSurfaceContinuityAudit.inspect(semantic,damaged,BlockPos.ORIGIN,Set.of()).isPresent());
    }

    @Test void authoredCourtyardHoleRemainsOpenWhileOccupiedSlopeCellsAreChecked() {
        var roof = new Component("ROOF","court",new Vec3i(0,10,0),new Dimensions(19,15,5),List.of(),
                Map.of("roof_type","gable","plan_type","courtyard","courtyard_ratio",0.3,
                        "roof_block","minecraft:dark_oak_planks"));
        var semantic = new SemanticComponent("ROOF",null,roof);
        var patches = new RoofGenerator().generate(semantic);
        assertFalse(patches.stream().anyMatch(p -> p.dx()==9 && p.dz()==7));
        assertTrue(RoofSurfaceContinuityAudit.inspect(semantic,patches,BlockPos.ORIGIN,Set.of()).isEmpty());
    }
}
