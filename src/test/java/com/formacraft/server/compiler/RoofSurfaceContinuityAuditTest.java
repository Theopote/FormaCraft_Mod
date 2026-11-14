package com.formacraft.server.compiler;

import com.formacraft.common.compiler.semantic.SemanticComponent;
import com.formacraft.common.generation.component.impl.RoofGenerator;
import com.formacraft.common.generation.component.util.GeneratedSurfaceCapture;
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

    @Test void camelCaseRoofTypeAliasEntersSameContinuityPath() {
        var roof = new Component("ROOF","chapel",new Vec3i(0,12,0),new Dimensions(23,11,8),List.of(),
                Map.of("roofType","gable","roof_height",8,"overhang",1,"roof_block","minecraft:dark_oak_planks"));
        var semantic = new SemanticComponent("ROOF",null,roof);
        var patches = new RoofGenerator().generate(semantic);
        assertTrue(RoofSurfaceContinuityAudit.inspect(semantic,patches,BlockPos.ORIGIN,Set.of()).isEmpty());
        var tops = new HashMap<BlockPos,BlockPatch>();
        for (var p : patches) {
            var col = new BlockPos(p.dx(),0,p.dz()); var old=tops.get(col);
            if (old==null || old.dy()<p.dy()) tops.put(col,p);
        }
        var gap = RoofSurfaceContinuityAudit.inspect(semantic,new ArrayList<>(tops.values()),BlockPos.ORIGIN,Set.of());
        assertEquals("E_ROOF_SURFACE_DISCONNECTED",gap.orElseThrow().code(),
                "roofType=gable must audit continuity like roof_type=gable");
    }

    @Test void hipRoofMissingColumnFailsCoverage() {
        var roof = new Component("ROOF","hall",new Vec3i(0,10,0),new Dimensions(12,10,5),List.of(),
                Map.of("roof_type","hip","overhang",0,"roof_block","minecraft:dark_oak_planks"));
        var semantic = new SemanticComponent("ROOF",null,roof);
        var patches = new RoofGenerator().generate(semantic);
        assertTrue(RoofSurfaceContinuityAudit.inspect(semantic,patches,BlockPos.ORIGIN,Set.of()).isEmpty());
        var damaged = patches.stream().filter(p -> !(p.dx()==5 && p.dz()==5)).toList();
        assertTrue(RoofSurfaceContinuityAudit.inspect(semantic,damaged,BlockPos.ORIGIN,Set.of()).isPresent(),
                "hip footprint columns must be covered");
    }

    @Test void mansardRoofMissingColumnFailsCoverage() {
        var roof = new Component("ROOF","villa",new Vec3i(0,10,0),new Dimensions(14,12,6),List.of(),
                Map.of("roof_type","mansard","overhang",0,"roof_block","minecraft:dark_oak_planks"));
        var semantic = new SemanticComponent("ROOF",null,roof);
        var patches = new RoofGenerator().generate(semantic);
        assertTrue(RoofSurfaceContinuityAudit.inspect(semantic,patches,BlockPos.ORIGIN,Set.of()).isEmpty());
        var damaged = patches.stream().filter(p -> !(p.dx()==6 && p.dz()==6)).toList();
        assertTrue(RoofSurfaceContinuityAudit.inspect(semantic,damaged,BlockPos.ORIGIN,Set.of()).isPresent(),
                "mansard footprint columns must be covered");
    }

    @Test void domeRoofNeighborDiscontinuityIsRejected() {
        var roof = new Component("ROOF","rotunda",new Vec3i(0,10,0),new Dimensions(11,11,6),List.of(),
                Map.of("roof_type","dome","roof_block","minecraft:smooth_quartz"));
        var semantic = new SemanticComponent("ROOF",null,roof);
        assertTrue(RoofSurfaceContinuityAudit.inspect(semantic,new RoofGenerator().generate(semantic),
                BlockPos.ORIGIN,Set.of()).isEmpty());
        // Two adjacent footprint columns with no shared Y and a jump > 1 — no joining surface.
        var broken = List.of(
                new BlockPatch(BlockPatch.PLACE, 5, 12, 5, "minecraft:smooth_quartz"),
                new BlockPatch(BlockPatch.PLACE, 6, 20, 5, "minecraft:smooth_quartz"));
        assertTrue(RoofSurfaceContinuityAudit.inspect(semantic,broken,BlockPos.ORIGIN,Set.of()).isPresent(),
                "dome slope columns must stay vertically joined");
    }

    @Test void roofContinuityUsesFinalRemoveState() {
        var roof = new Component("ROOF","hall",new Vec3i(0,8,0),new Dimensions(7,11,5),List.of(),
                Map.of("roof_type","gable","roof_block","minecraft:oak_stairs"));
        var semantic = new SemanticComponent("ROOF",null,roof);
        var generated = new ArrayList<>(new RoofGenerator().generate(semantic));
        assertTrue(RoofSurfaceContinuityAudit.inspect(semantic, generated, BlockPos.ORIGIN, Set.of()).isEmpty());
        BlockPatch surface = generated.stream()
                .filter(p -> p.dx() == 3 && p.dz() == 5 && GeneratedSurfaceCapture.occupied(p))
                .findFirst().orElseThrow();
        generated.add(new BlockPatch(BlockPatch.REMOVE, surface.dx(), surface.dy(), surface.dz(), "minecraft:air"));
        // Last-write REMOVE must drop the cell — compiler collapses before audit; audit itself
        // also last-writes, so a trailing REMOVE clears the earlier PLACE at the same coordinate.
        Map<BlockPos, BlockPatch> finals = new LinkedHashMap<>();
        for (var p : generated) finals.put(new BlockPos(p.dx(), p.dy(), p.dz()), p);
        var collapsed = finals.values().stream().filter(GeneratedSurfaceCapture::occupied).toList();
        assertTrue(collapsed.stream().noneMatch(p ->
                p.dx() == surface.dx() && p.dy() == surface.dy() && p.dz() == surface.dz()));
    }
}
