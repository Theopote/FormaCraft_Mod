package com.formacraft.server.build;

import com.formacraft.common.build.PlannedBlock;
import com.formacraft.common.model.request.FormaRequest;
import com.formacraft.server.assembly.AssemblyCirculationConstraints;
import com.formacraft.server.build.quality.BuildQualityReport;
import com.formacraft.test.MinecraftRegistryTestBootstrap;
import net.minecraft.block.Blocks;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class BuildPreviewCirculationTest {
    @BeforeAll static void initialize() { MinecraftRegistryTestBootstrap.initialize(); }
    private static final BlockPos TREAD = new BlockPos(-10, 64, 20), CLEAR = TREAD.up();
    private final AssemblyCirculationConstraints.Flight flight = new AssemblyCirculationConstraints.Flight(Set.of(TREAD), Set.of(CLEAR));
    private List<PlannedBlock> structure() {
        return List.of(new PlannedBlock(TREAD, Blocks.STONE_BRICK_STAIRS.getDefaultState()), new PlannedBlock(CLEAR, Blocks.AIR.getDefaultState()));
    }
    @Test void prependedTerrainIsOverriddenByStructureAndSurvivesDeduplication() {
        var blocks = new ArrayList<PlannedBlock>();
        blocks.add(new PlannedBlock(TREAD, Blocks.AIR.getDefaultState()));
        blocks.add(new PlannedBlock(CLEAR, Blocks.COBBLESTONE.getDefaultState()));
        blocks.addAll(structure());
        var repair = BuildAutoRepair.apply(null, Optional.empty(), blocks);
        var report = new BuildQualityReport();
        assertTrue(BuildPreviewPipeline.validateCirculation(repair.blocks(), List.of(flight), report));
        assertFalse(report.hasFatal()); assertEquals(2, repair.blocks().size());
    }
    @Test void laterRepairSupportBlockingClearanceIsFatal() {
        var blocks = new ArrayList<>(structure()); blocks.add(new PlannedBlock(CLEAR, Blocks.COBBLESTONE.getDefaultState()));
        var report = new BuildQualityReport();
        assertFalse(BuildPreviewPipeline.validateCirculation(BuildAutoRepair.apply(null, Optional.empty(), blocks).blocks(), List.of(flight), report));
        assertTrue(report.hasFatal()); assertFalse(report.allowPreview());
        assertEquals("E_PREVIEW_CIRCULATION_CONFLICT", report.issues().getFirst().code());
    }
    @Test void selectionClippingAwayRequiredAirCannotBeDelivered() {
        var request = new FormaRequest("test", TREAD, "SOUTH", "minecraft:overworld", null, TREAD, TREAD);
        var clipped = BuildConstraintClipper.clipPlannedBlocks(structure(), request);
        assertEquals(1, clipped.size());
        var report = new BuildQualityReport();
        assertFalse(BuildPreviewPipeline.validateCirculation(clipped, List.of(flight), report)); assertTrue(report.hasFatal());
    }
    @Test void selectionClippingAwayTreadCannotBeDelivered() {
        var request = new FormaRequest("test", TREAD, "SOUTH", "minecraft:overworld", null, CLEAR, CLEAR);
        var clipped = BuildConstraintClipper.clipPlannedBlocks(structure(), request);
        var report = new BuildQualityReport();
        assertFalse(BuildPreviewPipeline.validateCirculation(clipped, List.of(flight), report)); assertTrue(report.hasFatal());
    }
    @Test void planOriginIsAddedOnceAndUnrelatedFoundationIsAllowed() {
        var local = new AssemblyCirculationConstraints.Flight(Set.of(new BlockPos(2, 3, -1)), Set.of(new BlockPos(2, 4, -1)));
        var origin = new BlockPos(-12, 61, 21);
        var shifted = AssemblyCirculationConstraints.shift(local, origin);
        assertEquals(flight, shifted);
        var blocks = new ArrayList<>(structure()); blocks.add(new PlannedBlock(new BlockPos(30, 60, 20), Blocks.COBBLESTONE.getDefaultState()));
        assertTrue(BuildPreviewPipeline.validateCirculation(blocks, List.of(shifted), new BuildQualityReport()));
    }
    @Test void callersWithoutCirculationMetadataKeepExistingBehavior() {
        assertTrue(BuildPreviewPipeline.validateCirculation(List.of(), List.of(), new BuildQualityReport()));
    }
}
