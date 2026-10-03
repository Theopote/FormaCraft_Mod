package com.formacraft.server.generation.component.adaptor;

import com.formacraft.common.build.*;
import com.formacraft.common.patch.*;
import com.formacraft.common.model.build.*;
import com.formacraft.server.assembly.AssemblyCirculationConstraints;
import com.formacraft.server.generation.structure.TowerGenerator;
import com.formacraft.test.*;
import net.minecraft.block.*;
import net.minecraft.block.enums.*;
import net.minecraft.state.property.Properties;
import net.minecraft.util.math.*;
import org.junit.jupiter.api.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class StructurePatchBridgeTest {
    @BeforeAll static void initialize() { MinecraftRegistryTestBootstrap.initialize(); }
    @Test void fullStateAndRequiredAirSubtractSameOriginAndApplyOnce() {
        var origin = new BlockPos(100, 64, -200); var tread = origin.add(2, 3, 1); var clear = tread.up();
        var state = Blocks.OAK_STAIRS.getDefaultState().with(Properties.HORIZONTAL_FACING, Direction.WEST)
            .with(Properties.BLOCK_HALF, BlockHalf.TOP).with(Properties.WATERLOGGED, true);
        var structure = new GeneratedStructure(null, origin, "test", List.of(new PlannedBlock(tread, state),
            new PlannedBlock(clear, Blocks.AIR.getDefaultState()), new PlannedBlock(origin.add(9, 9, 9), Blocks.AIR.getDefaultState())));
        var result = StructurePatchBridge.convert(structure, origin,
            List.of(new AssemblyCirculationConstraints.Flight(Set.of(tread), Set.of(clear))));
        assertEquals(2, result.patches().size()); assertEquals(Set.of(new BlockPos(2, 3, 1)), result.circulation().getFirst().occupied());
        assertEquals(Set.of(new BlockPos(2, 4, 1)), result.circulation().getFirst().clearance());
        var access = new FakeBlockMutationAccess(); access.states.put(new BlockPos(12, 6, 1), Blocks.STONE.getDefaultState());
        assertEquals(2, PatchExecutor.applyToAccess(access, new BlockPos(10, 2, 0), result.patches()).applied());
        assertEquals(state, access.states.get(new BlockPos(12, 5, 1))); assertTrue(access.states.get(new BlockPos(12, 6, 1)).isAir());
    }
    @Test void actualTowerPublishesWorldRequirementsWhichBridgeReturnsLocally() {
        var spec = new BuildingSpec(); spec.setType(BuildingType.TOWER); spec.setHeight(9); spec.setFloors(3); spec.setFeatures(new Features());
        var origin = new BlockPos(-30, 64, 20); var flights = new ArrayList<AssemblyCirculationConstraints.Flight>();
        GeneratedStructure structure;
        try (var scope = AssemblyCirculationConstraints.captureTo(flights::addAll)) { structure = new TowerGenerator().generate(spec, origin, null); }
        assertEquals(1, flights.size());
        var bridged = StructurePatchBridge.convert(structure, origin, flights);
        assertDoesNotThrow(() -> AssemblyCirculationConstraints.validatePatches(bridged.patches(), bridged.circulation()));
        assertTrue(bridged.patches().stream().anyMatch(p -> p.action().equals("remove") && p.dx() == 0 && p.dy() == 3 && p.dz() == -1));
        assertTrue(bridged.patches().stream().anyMatch(p -> p.targetBlock().contains("oak_stairs") && p.targetBlock().contains("facing=east")));
        assertEquals(new BlockPos(-1, 0, -1), bridged.circulation().getFirst().occupied().iterator().next());
    }
    @Test void missingRequiredAirFailsBridgeInsteadOfDroppingRequirement() {
        var pos = new BlockPos(1, 2, 3);
        var structure = new GeneratedStructure(null, BlockPos.ORIGIN, "test", List.of(new PlannedBlock(pos, Blocks.STONE.getDefaultState())));
        assertThrows(AssemblyCirculationConstraints.Conflict.class, () -> StructurePatchBridge.convert(structure, BlockPos.ORIGIN,
            List.of(new AssemblyCirculationConstraints.Flight(Set.of(pos), Set.of(pos.up())))));
    }
}
