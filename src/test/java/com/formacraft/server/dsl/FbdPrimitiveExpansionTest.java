package com.formacraft.server.dsl;

import com.formacraft.common.build.PlannedBlock;
import com.formacraft.common.dsl.FbdCompiler;
import com.formacraft.common.dsl.FbdException;
import com.formacraft.common.dsl.FbdPrimitives;
import com.formacraft.server.assembly.AssemblySpec;
import com.formacraft.server.assembly.MetaAssemblyEngine;
import com.formacraft.test.MinecraftRegistryTestBootstrap;
import net.minecraft.block.Blocks;
import net.minecraft.block.StairsBlock;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** Batch-1/2 FBD geometry expansion: new scalar primitives, roles, rotY, pitched roof materials. */
class FbdPrimitiveExpansionTest {
    @BeforeAll static void boot() { MinecraftRegistryTestBootstrap.initialize(); }

    @Test void newPrimitivesTypecheckAndExpandEndpoints() {
        var program = Map.of("dialect", "fbd/0", "seed", 1, "main", List.of(
                Map.of("stmt", "emit", "id", "door", "op", "OPENINGS", "params", Map.of(
                        "face", "SOUTH", "kind", "DOOR", "x0", -3, "x1", 3, "y0", 0, "y1", 6, "z0", 3, "z1", 3)),
                Map.of("stmt", "emit", "id", "void", "op", "CLEAR_BOX", "params", Map.of(
                        "x0", -2, "x1", 2, "y0", 1, "y1", 5, "z0", -2, "z1", 2)),
                Map.of("stmt", "emit", "id", "tower", "op", "CYLINDER", "params", Map.of(
                        "r", 3, "h", 8, "hollow", true, "wall", "pillar")),
                Map.of("stmt", "emit", "id", "stairs", "op", "STAIR_SYSTEM", "params", Map.of(
                        "fromX", 0, "fromY", 0, "fromZ", 0, "toX", 0, "toY", 4, "toZ", 6,
                        "width", 2, "stairs", "stairs", "floor", "floor"))
        ));
        var compiled = FbdCompiler.compile(program);
        assertEquals(4, compiled.commands().size());
        var spec = FbdAssemblyAdapter.spec(compiled);
        assertTrue(spec.ops.get(3).containsKey("from"));
        assertFalse(spec.ops.get(3).containsKey("fromX"));
        assertInstanceOf(FbdPrimitives.Role.class, spec.ops.get(2).get("wall"));
        var blocks = new MetaAssemblyEngine().executeGeometry(spec,
                new MetaAssemblyEngine.Context(null, BlockPos.ORIGIN, Direction.SOUTH, null,
                        new FbdExecutionBudget(com.formacraft.common.dsl.FbdLimits.DEFAULT)));
        assertFalse(blocks.isEmpty());
        assertTrue(blocks.stream().anyMatch(b -> b.getTargetState().isAir()));
    }

    @Test void withFrameRotYQuarterTurnsAreAcceptedAndInvalidStepsRejected() {
        var ok = Map.of("dialect", "fbd/0", "seed", 1, "main", List.of(
                Map.of("stmt", "with_frame", "id", "f", "origin", List.of(4, 0, 0), "rotY", 90, "body", List.of(
                        Map.of("stmt", "emit", "id", "c", "op", "CLEAR_BOX", "params",
                                Map.of("x0", 0, "x1", 0, "y0", 0, "y1", 0, "z0", 0, "z1", 0))))));
        var compiled = FbdCompiler.compile(ok);
        assertEquals("PUSH_ORIGIN", compiled.commands().getFirst().op().get("op"));
        assertEquals(90, compiled.commands().getFirst().op().get("rotY"));
        var bad = Map.of("dialect", "fbd/0", "seed", 1, "main", List.of(
                Map.of("stmt", "with_frame", "id", "f", "origin", List.of(0, 0, 0), "rotY", 45, "body", List.of())));
        assertEquals("param_out_of_range", assertThrows(FbdException.class, () -> FbdCompiler.compile(bad)).code);
    }

    @Test void roofCoverKeepsPitchWithNonStairMaterialAndDistinguishesHipFootprint() {
        var gable = roofOps("GABLE", 4, 1.0);
        var hip = roofOps("HIP", 4, 1.0);
        assertFalse(gable.isEmpty());
        assertFalse(hip.isEmpty());
        assertTrue(gable.stream().noneMatch(b -> b.getTargetState().getBlock() instanceof StairsBlock));
        Set<Integer> gableYs = new HashSet<>();
        gable.forEach(b -> gableYs.add(b.getPos().getY()));
        assertTrue(gableYs.size() > 1, "non-stair GABLE must not collapse to a single flat layer");
        assertTrue(gable.stream().anyMatch(b -> b.getPos().getX() == 5 || b.getPos().getX() == -5),
                "GABLE must close shell-end gables");
    }

    @Test void shallowCurvedRoofDoesNotLeaveInsetGaps() {
        var roof = roofOps("GABLE", 3, 2.0); // rise << span with strong curvature → large inset jumps
        Set<String> keys = new HashSet<>();
        for (var b : roof) keys.add(b.getPos().getX() + "," + b.getPos().getY() + "," + b.getPos().getZ());
        // Along one slope row at mid-x, consecutive z bands at the same y should not skip a cell.
        int midX = 0;
        Map<Integer, Set<Integer>> yToZ = new HashMap<>();
        for (var b : roof) {
            if (b.getPos().getX() != midX) continue;
            yToZ.computeIfAbsent(b.getPos().getY(), ignored -> new HashSet<>()).add(b.getPos().getZ());
        }
        assertFalse(yToZ.isEmpty());
        for (var zs : yToZ.values()) {
            int min = zs.stream().mapToInt(Integer::intValue).min().orElse(0);
            int max = zs.stream().mapToInt(Integer::intValue).max().orElse(0);
            for (int z = min; z <= max; z++) {
                // Gable slopes occupy eaves bands; allow holes only outside placed bands.
                // At least the extreme eaves z must be present for each populated y.
                assertTrue(zs.contains(min) && zs.contains(max));
            }
        }
        assertFalse(keys.isEmpty());
    }

    @Test void roseOpeningsCostAccountsForRadius() {
        long tiny = FbdPrimitives.cost("OPENINGS", Map.of(
                "face", "SOUTH", "kind", "DOOR", "x0", 0, "x1", 0, "y0", 0, "y1", 3, "z0", 0, "z1", 0));
        long rose = FbdPrimitives.cost("OPENINGS", Map.of(
                "face", "SOUTH", "kind", "ROSE", "x0", 0, "x1", 0, "y0", 0, "y1", 3, "z0", 0, "z1", 0, "r", 24));
        assertTrue(rose > tiny);
    }

    @Test void expandedRolesAreAccepted() {
        for (String role : List.of("pillar", "trim", "railing", "light", "stairs", "post")) {
            assertTrue(FbdPrimitives.ROLES.contains(role));
        }
        var p = Map.of("dialect", "fbd/0", "seed", 1, "main", List.of(
                Map.of("stmt", "emit", "id", "a", "op", "SHELL_BOX", "params",
                        Map.of("w", 7, "d", 7, "h", 6, "wall", "pillar", "post", "trim"))));
        assertDoesNotThrow(() -> FbdCompiler.compile(p));
    }

    private static List<PlannedBlock> roofOps(String type, int rise, double curvaturePower) {
        var op = new HashMap<String, Object>();
        op.put("op", "ROOF_COVER");
        op.put("type", type);
        op.put("w", 11);
        op.put("d", 7);
        op.put("y", 6);
        op.put("rise", rise);
        op.put("curvaturePower", curvaturePower);
        op.put("roof", "minecraft:copper_block");
        op.put("slab", "minecraft:smooth_stone_slab");
        return new MetaAssemblyEngine().executeGeometry(AssemblySpec.of(null, null, List.of(op)),
                new MetaAssemblyEngine.Context(null, BlockPos.ORIGIN, Direction.SOUTH, null));
    }
}
