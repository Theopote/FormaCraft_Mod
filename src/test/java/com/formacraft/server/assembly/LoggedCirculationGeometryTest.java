package com.formacraft.server.assembly;

import com.formacraft.common.build.PlannedBlock;
import com.formacraft.server.assembly.validation.*;
import com.formacraft.test.MinecraftRegistryTestBootstrap;
import net.minecraft.block.*;
import net.minecraft.util.math.*;
import org.junit.jupiter.api.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class LoggedCirculationGeometryTest {
    @BeforeAll static void initialize() { MinecraftRegistryTestBootstrap.initialize(); }
    @SuppressWarnings("unchecked") private Map<String,Object> fixture(String name) throws Exception {
        try (var in = getClass().getResourceAsStream("/regressions/"+name+"-stair-ops.json")) {
            return new com.google.gson.Gson().fromJson(new String(Objects.requireNonNull(in).readAllBytes(), StandardCharsets.UTF_8), Map.class);
        }
    }
    private Map<BlockPos,BlockState> generate(String name) throws Exception {
        var payload = fixture(name);
        assertTrue(AssemblySpecValidator.validate(payload).stream().noneMatch(i -> i.severity()==AssemblyValidationIssue.Severity.ERROR));
        var flights = new ArrayList<AssemblyCirculationConstraints.Flight>();
        List<PlannedBlock> blocks;
        try (var scope = AssemblyCirculationConstraints.captureTo(flights::addAll)) {
            blocks = new MetaAssemblyEngine().executeGeometry(AssemblySpec.fromExtra(payload),
                new MetaAssemblyEngine.Context(null, BlockPos.ORIGIN, Direction.SOUTH, null));
        }
        assertFalse(flights.isEmpty());
        AssemblyCirculationConstraints.validate(blocks, flights);
        var cells = new HashMap<BlockPos,BlockState>();
        for (var b : blocks) cells.put(b.getPos(), b.getTargetState());
        for (var f : flights) for (var p : f.clearance()) assertTrue(cells.get(p).isAir(), "Final clearance at "+p);
        return cells;
    }
    @Test void switchbackHasThreeWideFlightsAndFaceAdjacentPlatform() throws Exception {
        var cells = generate("switchback");
        for (int x=0;x<=6;x++) for (int z=4;z<=5;z++)
            assertEquals(Blocks.OAK_PLANKS, cells.get(new BlockPos(x,3,z)).getBlock(), "Landing must bridge both lanes");
        for (int x=0;x<3;x++) assertInstanceOf(StairsBlock.class,cells.get(new BlockPos(x,3,3)).getBlock(), "Last tread must not become a full-block jump");
        for (int x=4;x<=6;x++) {
            assertInstanceOf(StairsBlock.class,cells.get(new BlockPos(x,6,1)).getBlock());
            assertEquals(Blocks.OAK_PLANKS,cells.get(new BlockPos(x,6,0)).getBlock());
        }
    }
    @Test void ringHasOneBlockFloorSlabsAndStopsAtHighestOccupiedFloor() throws Exception {
        var cells = generate("ring");
        for (int y : new int[]{5,10}) assertEquals(Blocks.SMOOTH_STONE,cells.get(new BlockPos(0,y,0)).getBlock());
        assertFalse(cells.containsKey(new BlockPos(0,6,0)), "One-block cylinder plate must not fill three storey layers");
        assertFalse(cells.containsKey(new BlockPos(0,11,0)));
        assertTrue(cells.entrySet().stream().filter(e -> e.getValue().getBlock() instanceof StairsBlock).allMatch(e -> e.getKey().getY() <= 10));
        assertTrue(cells.entrySet().stream().anyMatch(e -> e.getKey().getY()==5 && e.getValue().isAir()), "Stairs must carve the first slab");
        assertTrue(cells.entrySet().stream().anyMatch(e -> e.getKey().getY()==10 && e.getValue().isAir()), "Stairs must carve the second slab");
    }
    @Test void pureGeometryRejectsTerrainOperations() {
        var spec = AssemblySpec.of(null,null,List.of(Map.of("op","ANCHOR_FOOTPRINT")));
        assertThrows(IllegalArgumentException.class, () -> new MetaAssemblyEngine().executeGeometry(spec,
            new MetaAssemblyEngine.Context(null,BlockPos.ORIGIN,Direction.SOUTH,null)));
    }
}
