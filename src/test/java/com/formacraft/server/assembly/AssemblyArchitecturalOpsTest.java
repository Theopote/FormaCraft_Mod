package com.formacraft.server.assembly;

import com.formacraft.common.build.PlannedBlock;
import com.formacraft.common.dsl.FbdException;
import com.formacraft.common.dsl.FbdLimits;
import com.formacraft.server.assembly.schema.AssemblyComponentSchemaRegistry;
import com.formacraft.server.dsl.FbdExecutionBudget;
import com.formacraft.test.MinecraftRegistryTestBootstrap;
import net.minecraft.block.Blocks;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class AssemblyArchitecturalOpsTest {

    @BeforeAll
    static void initialize() {
        MinecraftRegistryTestBootstrap.initialize();
    }

    private MetaAssemblyEngine.Context ctx() {
        return new MetaAssemblyEngine.Context(null, BlockPos.ORIGIN, Direction.SOUTH, null);
    }

    private List<PlannedBlock> run(Map<String, Object> op) {
        return new MetaAssemblyEngine().executeGeometry(AssemblySpec.of(null, null, List.of(op)), ctx());
    }

    private static Map<String, Object> op(String name, Object... kv) {
        Map<String, Object> m = new HashMap<>();
        m.put("op", name);
        for (int i = 0; i + 1 < kv.length; i += 2) {
            m.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return m;
    }

    @Test
    void arcadeSeriesProducesClosedSpandrelDeckAndStartEndPorts() {
        var blocks = run(op("ARCADE_SERIES",
                "length", 25, "pierHeight", 6, "baySpan", 4,
                "archProfile", "pointed", "archRise", 4, "depth", 2,
                "material", "minecraft:stone_bricks"));
        assertFalse(blocks.isEmpty());
        Set<BlockPos> positions = new HashSet<>();
        for (var b : blocks) positions.add(b.getPos());
        assertTrue(positions.stream().anyMatch(p -> p.getY() >= 10), "spandrel/deck should rise above piers+arch");

        Map<String, Object> sample = new HashMap<>();
        sample.put("type", "ARCADE_SERIES");
        sample.put("length", 25);
        sample.put("pierHeight", 6);
        sample.put("baySpan", 4);
        sample.put("depth", 2);
        sample.put("axis", "X");
        var ports = AssemblyPortResolver.portIds(sample);
        assertTrue(ports.contains("deck_top"));
        assertTrue(ports.contains("start"));
        assertTrue(ports.contains("end"));
        assertTrue(ports.contains("bottom_center"));
    }

    @Test
    void arcadeRejectsBaySpanLargerThanLength() {
        var ex = assertThrows(AssemblyArchitecturalOps.InvalidArchitecturalParam.class,
                () -> run(op("ARCADE_SERIES", "length", 5, "pierHeight", 4, "baySpan", 8)));
        assertTrue(ex.getMessage().contains("baySpan"));
    }

    @Test
    void drumDomeProducesShellRibsAndApexPorts() {
        var blocks = run(op("DRUM_DOME",
                "radius", 9, "domeHeight", 10, "drumHeight", 5,
                "profile", "pointed", "ribCount", 8, "drumWindows", true,
                "shellMaterial", "minecraft:oxidized_copper",
                "ribMaterial", "minecraft:deepslate_bricks",
                "drumMaterial", "minecraft:stone_bricks"));
        assertFalse(blocks.isEmpty());
        assertTrue(blocks.stream().anyMatch(b -> b.getTargetState().isOf(Blocks.OXIDIZED_COPPER)));
        assertTrue(blocks.stream().anyMatch(b -> b.getTargetState().isOf(Blocks.DEEPSLATE_BRICKS)));
        assertTrue(blocks.stream().anyMatch(b -> b.getTargetState().isOf(Blocks.STONE_BRICKS)));

        Map<String, Object> sample = new HashMap<>();
        sample.put("type", "DRUM_DOME");
        sample.put("radius", 9);
        sample.put("domeHeight", 10);
        sample.put("drumHeight", 5);
        sample.put("lanternHeight", 3);
        sample.put("lanternRadius", 2);
        var ports = AssemblyPortResolver.portIds(sample);
        assertTrue(ports.contains("apex"));
        assertTrue(ports.contains("drum_top"));
        assertTrue(ports.contains("lantern_top"));
    }

    @Test
    void louverSkinEmitsWaveFinsAndRejectsMissingFacing() {
        var blocks = run(op("LOUVER_SKIN",
                "w", 12, "h", 10, "facing", "SOUTH",
                "orientation", "vertical", "spacing", 2, "finDepth", 2,
                "waveAmplitude", 1.5, "waveFrequency", 0.3,
                "material", "minecraft:iron_bars",
                "backingMaterial", "minecraft:light_gray_stained_glass"));
        assertFalse(blocks.isEmpty());
        assertTrue(blocks.stream().anyMatch(b -> b.getTargetState().isOf(Blocks.IRON_BARS)));
        assertTrue(blocks.stream().anyMatch(b -> b.getTargetState().isOf(Blocks.LIGHT_GRAY_STAINED_GLASS)));

        // Backing fills the full interior panel (not only behind fins): spacing=2 → glass between fin columns
        long glassBetweenFins = blocks.stream()
                .filter(b -> b.getTargetState().isOf(Blocks.LIGHT_GRAY_STAINED_GLASS))
                .filter(b -> {
                    BlockPos p = b.getPos();
                    // facing SOUTH → backing at z=-1; fin columns at odd u from 1
                    return p.getZ() == -1 && p.getY() >= 1 && p.getY() <= 8
                            && ((p.getX() + 6) % 2 == 0); // even local u between fins
                })
                .count();
        assertTrue(glassBetweenFins > 0, "backing must fill gaps between louvers");

        assertThrows(AssemblyArchitecturalOps.InvalidArchitecturalParam.class,
                () -> run(op("LOUVER_SKIN", "w", 12, "h", 10)));
    }

    @Test
    void buttressArrayCountSpreadsEvenlyWithoutStacking() {
        var blocks = run(op("BUTTRESS_ARRAY",
                "length", 10, "height", 8, "outward", "SOUTH", "reach", 3,
                "count", 5, "style", "stepped", "axis", "X",
                "material", "minecraft:stone_bricks"));
        // Outer pier bases at reach=3 south of axis — distinct X positions for count=5
        Set<Integer> alongXs = new HashSet<>();
        for (var b : blocks) {
            BlockPos p = b.getPos();
            if (p.getZ() == 3 && p.getY() == 0) alongXs.add(p.getX());
        }
        assertEquals(5, alongXs.size(), "count=5 must place five distinct buttress stations, got " + alongXs);
    }

    @Test
    void buttressArrayRejectsOutwardParallelToAxis() {
        var ex = assertThrows(AssemblyArchitecturalOps.InvalidArchitecturalParam.class,
                () -> run(op("BUTTRESS_ARRAY",
                        "length", 20, "height", 12, "outward", "EAST", "reach", 4, "axis", "X")));
        assertTrue(ex.getMessage().toLowerCase().contains("perpendicular"));
    }

    @Test
    void buttressArrayAndCantileverProduceGeometryAndPorts() {
        var flying = run(op("BUTTRESS_ARRAY",
                "length", 25, "height", 14, "outward", "SOUTH", "reach", 5,
                "spacing", 5, "style", "flying", "flyerTiers", 2, "pinnacleHeight", 3,
                "material", "minecraft:stone_bricks"));
        assertFalse(flying.isEmpty());

        Map<String, Object> buttressSample = new HashMap<>();
        buttressSample.put("type", "BUTTRESS_ARRAY");
        buttressSample.put("length", 25);
        buttressSample.put("height", 14);
        buttressSample.put("outward", "SOUTH");
        buttressSample.put("reach", 5);
        buttressSample.put("axis", "X");
        var bPorts = AssemblyPortResolver.portIds(buttressSample);
        assertTrue(bPorts.contains("wall_attach"));
        assertTrue(bPorts.contains("outer_base"));

        var deck = run(op("CANTILEVER_TIER",
                "w", 12, "d", 10, "slabThickness", 2,
                "overhangNorth", 3, "overhangSouth", 2,
                "supportStyle", "recessed_core", "supportHeight", 5,
                "parapetHeight", 1,
                "slabMaterial", "minecraft:smooth_stone",
                "supportMaterial", "minecraft:stone_bricks"));
        assertFalse(deck.isEmpty());
        assertTrue(deck.stream().anyMatch(b -> b.getTargetState().isOf(Blocks.SMOOTH_STONE)
                || b.getTargetState().isOf(Blocks.SMOOTH_QUARTZ)));

        Map<String, Object> deckSample = new HashMap<>();
        deckSample.put("type", "CANTILEVER_TIER");
        deckSample.put("w", 12);
        deckSample.put("d", 10);
        deckSample.put("slabThickness", 2);
        deckSample.put("supportHeight", 5);
        deckSample.put("parapetHeight", 1);
        var dPorts = AssemblyPortResolver.portIds(deckSample);
        assertTrue(dPorts.contains("deck_top"));
        assertTrue(dPorts.contains("core_bottom"));
    }

    @Test
    void writeBudgetCapsOversizedDrumDome() {
        var tiny = new FbdLimits(32, 10, 4, 100, 10, 50, 100, 32);
        var budget = new FbdExecutionBudget(tiny);
        var ctx = new MetaAssemblyEngine.Context(null, BlockPos.ORIGIN, Direction.SOUTH, null, budget);
        var dome = op("DRUM_DOME", "radius", 20, "domeHeight", 20, "drumHeight", 8, "ribCount", 12,
                "shellMaterial", "minecraft:copper_block");
        var ex = assertThrows(FbdException.class,
                () -> new MetaAssemblyEngine().executeGeometry(AssemblySpec.of(null, null, List.of(dome)), ctx));
        assertEquals("budget_exceeded", ex.code);
    }

    @Test
    void schemaRegistryExposesAllFiveArchitecturalOps() {
        for (String type : List.of("ARCADE_SERIES", "BUTTRESS_ARRAY", "DRUM_DOME", "LOUVER_SKIN", "CANTILEVER_TIER")) {
            assertNotNull(AssemblyComponentSchemaRegistry.findByType(type), type);
            assertTrue(AssemblyComponentSchemaRegistry.engineOps().contains(type), type);
        }
        assertNotNull(AssemblyComponentSchemaRegistry.findByType("COLONNADE"));
        assertNotNull(AssemblyComponentSchemaRegistry.findByType("BRISE_SOLEIL"));
    }
}
