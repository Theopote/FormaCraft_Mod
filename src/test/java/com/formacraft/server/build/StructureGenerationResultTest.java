package com.formacraft.server.build;

import com.formacraft.common.build.*;
import com.formacraft.common.model.build.*;
import com.formacraft.common.model.composite.CompositeSpec;
import com.formacraft.common.model.request.FormaRequest;
import com.formacraft.server.assembly.AssemblyCirculationConstraints;
import com.formacraft.server.build.quality.BuildQualityReport;
import com.formacraft.server.generation.structure.TowerGenerator;
import com.formacraft.server.generation.structure.composite.CompositeStructureGenerator;
import com.formacraft.test.MinecraftRegistryTestBootstrap;
import net.minecraft.block.Blocks;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class StructureGenerationResultTest {
    @BeforeAll static void initialize() { MinecraftRegistryTestBootstrap.initialize(); }
    private static BuildingSpec tower() {
        var spec = new BuildingSpec(); spec.setType(BuildingType.TOWER);
        spec.setHeight(9); spec.setFloors(3); spec.setFeatures(new Features()); return spec;
    }
    private static StructureGenerationResult generate(BlockPos origin) {
        return StructureGenerationResult.capture(() -> new TowerGenerator().generate(tower(), origin, null));
    }
    @Test void independentTowerKeepsEmittedCoordinatesThroughRepair() {
        var origin = new BlockPos(-30, 64, 20); var result = generate(origin);
        assertEquals(1, result.circulation().size());
        assertTrue(result.circulation().getFirst().occupied().contains(origin.add(-1, 0, -1)));
        var repair = BuildAutoRepair.apply(null, Optional.empty(), result.structure().getBlocks());
        assertTrue(BuildPreviewPipeline.validateCirculation(repair.blocks(), result.circulation(), new BuildQualityReport()));
        assertThrows(UnsupportedOperationException.class, () -> result.circulation().clear());
    }
    @Test void clippingIndependentTowerHeadroomIsFatal() {
        var origin = new BlockPos(-30, 64, 20); var result = generate(origin);
        var request = new FormaRequest("test", origin, "SOUTH", "minecraft:overworld", null,
                origin.add(-10, 0, -10), origin.add(10, 6, 10));
        var repaired = BuildAutoRepair.apply(null, Optional.empty(), result.structure().getBlocks());
        var clipped = BuildConstraintClipper.clipPlannedBlocks(repaired.blocks(), request);
        var report = new BuildQualityReport();
        assertFalse(BuildPreviewPipeline.validateCirculation(clipped, result.circulation(), report));
        assertTrue(report.hasFatal());
    }
    @Test void compositeSubOriginIsAlreadyIncludedExactlyOnce() {
        var origin = new BlockPos(100, 60, -200);
        var sub = new CompositeSpec.SubStructure(); sub.setType("TOWER"); sub.setSpec(tower());
        sub.setOffset(new CompositeSpec.Offset(15, 4, -8));
        var composite = new CompositeSpec(); composite.setStructures(List.of(sub));
        var result = StructureGenerationResult.capture(() -> new CompositeStructureGenerator().generate(composite, origin, null));
        assertEquals(1, result.circulation().size());
        assertTrue(result.circulation().getFirst().occupied().contains(origin.add(14, 4, -9)));
        assertDoesNotThrow(() -> AssemblyCirculationConstraints.validate(result.structure().getBlocks(), result.circulation()));
    }
    @Test void laterMergedBuildingCannotEraseCapturedTowerTread() {
        var origin = new BlockPos(12, 64, 30); var result = generate(origin);
        var blocks = new ArrayList<>(result.structure().getBlocks());
        var tread = result.circulation().getFirst().occupied().iterator().next();
        blocks.add(new PlannedBlock(tread, Blocks.AIR.getDefaultState()));
        var report = new BuildQualityReport();
        assertFalse(BuildPreviewPipeline.validateCirculation(BuildAutoRepair.apply(null, Optional.empty(), blocks).blocks(), result.circulation(), report));
        assertTrue(report.hasFatal());
    }
    @Test void failureRestoresOuterCaptureAndRetryHasNoResidualRequirements() {
        var outer = new ArrayList<AssemblyCirculationConstraints.Flight>();
        var flight = new AssemblyCirculationConstraints.Flight(Set.of(BlockPos.ORIGIN), Set.of(BlockPos.ORIGIN.up()));
        try (var scope = AssemblyCirculationConstraints.captureTo(outer::addAll)) {
            assertThrows(IllegalStateException.class, () -> StructureGenerationResult.capture(() -> {
                AssemblyCirculationConstraints.publish(List.of(flight)); throw new IllegalStateException("failed generation");
            }));
            assertTrue(outer.isEmpty());
            var retry = StructureGenerationResult.capture(() -> new GeneratedStructure(null, BlockPos.ORIGIN, "retry", List.of()));
            assertTrue(retry.circulation().isEmpty());
            AssemblyCirculationConstraints.publish(List.of(flight));
            assertEquals(List.of(flight), outer);
        }
    }
    @Test void nestedSuccessfulCaptureOwnsOnlyItsRequirements() {
        var outer = new ArrayList<AssemblyCirculationConstraints.Flight>();
        try (var scope = AssemblyCirculationConstraints.captureTo(outer::addAll)) {
            assertEquals(1, generate(new BlockPos(20, 50, -20)).circulation().size());
            assertTrue(outer.isEmpty());
        }
        var single = tower(); single.setFloors(1);
        assertTrue(StructureGenerationResult.capture(() -> new TowerGenerator().generate(single, BlockPos.ORIGIN, null)).circulation().isEmpty());
    }
}
