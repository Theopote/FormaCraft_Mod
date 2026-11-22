package com.formacraft.server.assembly;

import com.formacraft.common.dsl.FbdException;
import com.formacraft.common.dsl.FbdLimits;
import com.formacraft.server.dsl.FbdExecutionBudget;
import com.formacraft.test.MinecraftRegistryTestBootstrap;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * HARD_CONSTRAINT: plain ASSEMBLY (null caller budget) must still meter writes/samples
 * so oversized CLEAR_BOX / surface grids fail with FbdException instead of OOM.
 */
class MetaAssemblyBudgetGuardTest {
    @BeforeAll
    static void initialize() {
        MinecraftRegistryTestBootstrap.initialize();
    }

    private static MetaAssemblyEngine.Context plainCtx() {
        return new MetaAssemblyEngine.Context(null, BlockPos.ORIGIN, Direction.SOUTH, null);
    }

    @Test
    void plainAssemblyClearBoxRejectsVolumeAboveDefaultWriteBudget() {
        var op = new HashMap<String, Object>();
        op.put("op", "CLEAR_BOX");
        op.put("x0", 0);
        op.put("y0", 0);
        op.put("z0", 0);
        op.put("x1", 499);
        op.put("y1", 99);
        op.put("z1", 499); // 500*100*500 = 25_000_000 >> 200_000
        var spec = AssemblySpec.of(null, null, List.of(op));
        var ex = assertThrows(FbdException.class,
                () -> new MetaAssemblyEngine().executeGeometry(spec, plainCtx()));
        assertEquals("budget_exceeded", ex.code);
        assertEquals("candidate_writes", ex.diagnostic().get("layer"));
        assertTrue((Long) ex.diagnostic().get("actual") > FbdLimits.DEFAULT.writes());
    }

    @Test
    void bezierSurfaceRejectsJointGridAboveCapWithoutAllocating() {
        List<Map<String, Object>> points = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            for (int j = 0; j < 4; j++) {
                points.add(Map.of("x", i * 4, "y", 0, "z", j * 4));
            }
        }
        var op = new HashMap<String, Object>();
        op.put("op", "BEZIER_SURFACE");
        op.put("points", points);
        op.put("uSamples", 512);
        op.put("vSamples", 512);
        op.put("thickness", 1);
        op.put("connectSamples", false);
        op.put("material", "minecraft:quartz_block");
        var spec = AssemblySpec.of(null, null, List.of(op));
        var ex = assertThrows(FbdException.class,
                () -> new MetaAssemblyEngine().executeGeometry(spec, plainCtx()));
        assertEquals("budget_exceeded", ex.code);
        assertEquals("surface_grid", ex.diagnostic().get("layer"));
        assertEquals((long) MetaAssemblyEngine.MAX_SURFACE_GRID_CELLS, ex.diagnostic().get("limit"));
    }

    @Test
    void modestBezierSurfaceExecutesUnderDefaultBudget() {
        List<Map<String, Object>> points = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            for (int j = 0; j < 4; j++) {
                points.add(Map.of("x", i * 3, "y", (i + j) % 2, "z", j * 3));
            }
        }
        var op = new HashMap<String, Object>();
        op.put("op", "BEZIER_SURFACE");
        op.put("points", points);
        op.put("uSamples", 8);
        op.put("vSamples", 8);
        op.put("thickness", 1);
        op.put("connectSamples", false);
        op.put("material", "minecraft:quartz_block");
        var blocks = new MetaAssemblyEngine().executeGeometry(AssemblySpec.of(null, null, List.of(op)), plainCtx());
        assertFalse(blocks.isEmpty());
        assertTrue(blocks.size() <= FbdLimits.DEFAULT.writes());
    }

    @Test
    void withWriteBudgetIsIdempotentWhenBudgetAlreadyPresent() {
        var budget = new FbdExecutionBudget(FbdLimits.DEFAULT);
        var ctx = new MetaAssemblyEngine.Context(null, BlockPos.ORIGIN, Direction.SOUTH, null, budget);
        assertSame(ctx, MetaAssemblyEngine.withWriteBudget(ctx));
        assertNotNull(MetaAssemblyEngine.withWriteBudget(plainCtx()).fbdBudget());
    }

    @Test
    void connectedBezierSurfaceDoesNotDuplicateWorldPositions() {
        List<Map<String, Object>> points = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            for (int j = 0; j < 4; j++) {
                points.add(Map.of("x", i * 4, "y", 0, "z", j * 4));
            }
        }
        var op = new HashMap<String, Object>();
        op.put("op", "BEZIER_SURFACE");
        op.put("points", points);
        op.put("uSamples", 12);
        op.put("vSamples", 12);
        op.put("thickness", 3);
        op.put("connectSamples", true);
        op.put("material", "minecraft:quartz_block");
        var blocks = new MetaAssemblyEngine().executeGeometry(AssemblySpec.of(null, null, List.of(op)), plainCtx());
        assertFalse(blocks.isEmpty());
        long unique = blocks.stream().map(b -> b.getPos()).distinct().count();
        assertEquals(unique, blocks.size(), "connect+thickness must not re-append the same local voxel");
    }

    @Test
    void connectedRevolveSurfaceDoesNotDuplicateWorldPositions() {
        var op = new HashMap<String, Object>();
        op.put("op", "REVOLVE_SURFACE");
        op.put("profilePoints", List.of(
                Map.of("x", 4, "y", 0),
                Map.of("x", 6, "y", 4),
                Map.of("x", 4, "y", 8)));
        op.put("segments", 24);
        op.put("thickness", 3);
        op.put("connectSamples", true);
        op.put("material", "minecraft:quartz_block");
        var blocks = new MetaAssemblyEngine().executeGeometry(AssemblySpec.of(null, null, List.of(op)), plainCtx());
        assertFalse(blocks.isEmpty());
        long unique = blocks.stream().map(b -> b.getPos()).distinct().count();
        assertEquals(unique, blocks.size());
    }
}
