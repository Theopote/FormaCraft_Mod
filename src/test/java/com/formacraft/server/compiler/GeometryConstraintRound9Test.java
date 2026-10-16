package com.formacraft.server.compiler;

import com.formacraft.common.compiler.semantic.SemanticComponent;
import com.formacraft.common.generation.component.impl.FacadeWindowsGenerator;
import com.formacraft.common.llm.dto.*;
import com.formacraft.common.patch.BlockPatch;
import com.formacraft.server.assembly.AssemblyCompileDiagnostics;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class GeometryConstraintRound9Test {
    @BeforeAll
    static void bootstrap() {
        com.formacraft.test.MinecraftRegistryTestBootstrap.initialize();
    }

    @BeforeEach
    void clearDiag() {
        AssemblyCompileDiagnostics.clear();
    }

    @AfterEach
    void clearAfter() {
        AssemblyCompileDiagnostics.clear();
    }

    @Test
    void facadeScopeLimitsWrapToListedFaceAndFloor() {
        var params = new HashMap<String, Object>();
        params.put("component_id", "windows");
        params.put("faces", List.of("SOUTH"));
        params.put("min_floor", 0);
        params.put("max_floor", 0);
        params.put("window_width", 2);
        params.put("window_height", 2);
        params.put("sill_offset", 1);
        params.put("floor_height", 5);
        params.put("glass_block", "minecraft:glass");
        params.put("reserve_entrance_bay", false);
        var c = new Component("FACADE_WINDOWS", "house", new Vec3i(0, 0, 0),
                new Dimensions(15, 13, 10), List.of("wrap"), params);
        var slot = new Slot("house", new Vec3i(0, 0, 0), GlobalConstraints.Facing.SOUTH, null, null, null);
        var patches = new FacadeWindowsGenerator().generate(new SemanticComponent("FACADE_WINDOWS", slot, c, "DEFAULT"));
        var glass = new HashSet<BlockPos>();
        for (var p : patches) {
            if ("minecraft:glass".equals(p.targetBlock())) glass.add(new BlockPos(p.dx(), p.dy(), p.dz()));
        }
        assertFalse(glass.isEmpty());
        assertTrue(glass.stream().allMatch(p -> p.getZ() == 0), "only SOUTH (z=0)");
        assertTrue(glass.stream().noneMatch(p -> p.getZ() == 12), "no NORTH");
        assertTrue(glass.stream().allMatch(p -> p.getY() < 5), "only floor 0");
    }

    @Test
    void geometryConstraintCodePrefersDedicatedPrefixes() {
        assertEquals("E_ENTRANCE_DOOR_WIDTH",
                ComponentPlanCompiler.geometryConstraintCode("E_ENTRANCE_DOOR_WIDTH: requested=3"));
        assertEquals("E_WINDOW_COUNT_UNFIT",
                ComponentPlanCompiler.geometryConstraintCode("E_WINDOW_COUNT_UNFIT: requested=6"));
        assertEquals("E_FACADE_SCOPE_INVALID",
                ComponentPlanCompiler.geometryConstraintCode("E_FACADE_SCOPE_INVALID: faces"));
        assertEquals("E_BRIDGE_ENDPOINT_LEVEL_MISMATCH",
                ComponentPlanCompiler.geometryConstraintCode("E_BRIDGE_ENDPOINT_LEVEL_MISMATCH: y"));
        assertEquals("E_BRIDGE_LEVEL_CONFLICT",
                ComponentPlanCompiler.geometryConstraintCode("E_BRIDGE_LEVEL_CONFLICT: y vs level"));
        assertEquals("E_BRIDGE_PATH_BLOCKED",
                ComponentPlanCompiler.geometryConstraintCode("E_BRIDGE_PATH_BLOCKED: no path"));
        assertEquals("E_WALKABILITY_SEARCH_LIMIT",
                ComponentPlanCompiler.geometryConstraintCode("E_WALKABILITY_SEARCH_LIMIT: budget"));
        assertEquals("E_COMPONENT_GENERATION_FAILED",
                ComponentPlanCompiler.geometryConstraintCode("other"));
    }

    @Test
    void irregularGableAddsSoftWarning() {
        var params = Map.<String, Object>of(
                "component_id", "roof_l",
                "roof_type", "gable",
                "plan_type", "l_shape");
        var roof = new Component("ROOF", "house", new Vec3i(0, 10, 0),
                new Dimensions(12, 10, 4), List.of(), params);
        var semantic = new SemanticComponent("ROOF", null, roof, "DEFAULT");
        ComponentPlanCompiler.maybeWarnIrregularGableSeal(roof, semantic);
        assertTrue(AssemblyCompileDiagnostics.hasSoftWarnings());
        assertEquals("ROOF_GABLE_SEAL_SKIPPED", AssemblyCompileDiagnostics.softWarnings().get(0).code());
    }

    @Test
    void rectangularGableDoesNotWarn() {
        var params = Map.<String, Object>of(
                "component_id", "roof_r",
                "roof_type", "gable");
        var roof = new Component("ROOF", "house", new Vec3i(0, 10, 0),
                new Dimensions(9, 7, 4), List.of(), params);
        var semantic = new SemanticComponent("ROOF", null, roof, "DEFAULT");
        ComponentPlanCompiler.maybeWarnIrregularGableSeal(roof, semantic);
        assertFalse(AssemblyCompileDiagnostics.hasSoftWarnings());
    }
}
