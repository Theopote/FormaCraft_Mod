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
    void malformedCsgDoesNotEmitPartialOrFallbackGeometry() {
        for (var entry : Map.<String, Map<String, Object>>of(
                "operations[1]", Map.of("operations", Arrays.asList(Map.of("kind", "box"), null)),
                "operations must", Map.of("operations", "subtract"),
                "subtract must", Map.of("subtract", "cylinder")).entrySet()) {
            AssemblyCompileDiagnostics.clear();
            var shape = new Component("PRIMITIVE", "main", new Vec3i(0, 0, 0), new Dimensions(7, 5, 4),
                    List.of(), entry.getValue());
            var components = List.of(shape);
            var plan = LlmPlanTestFixtures.builder().mode(LlmPlan.Mode.build).components(components).build();
            var patches = new ArrayList<BlockPatch>();
            ComponentEmitPass.compileComponents(plan, null, null, false, components, Set.of(), Map.of(), patches,
                    new ArrayList<>(), new ArrayList<>(), new HashSet<>(), new ArrayList<>(), new HashSet<>(),
                    new HashSet<>(), new HashMap<>());
            var gap = AssemblyCompileDiagnostics.get();
            assertNotNull(gap);
            assertEquals("E_PRIMITIVE_CSG_INVALID", gap.code());
            assertTrue(gap.message().contains(entry.getKey()));
            assertTrue(patches.isEmpty());
        }
    }

    @Test
    void unknownExtrusionModeDoesNotEmitASolidVolume() {
        var shape = new Component("PRIMITIVE", "main", new Vec3i(0, 0, 0), new Dimensions(7, 5, 4),
                List.of(), Map.of("kind", "box", "extrudeMode", "ribbon_shell"));
        var components = List.of(shape);
        var plan = LlmPlanTestFixtures.builder().mode(LlmPlan.Mode.build).components(components).build();
        var patches = new ArrayList<BlockPatch>();
        ComponentEmitPass.compileComponents(plan, null, null, false, components, Set.of(), Map.of(), patches,
                new ArrayList<>(), new ArrayList<>(), new HashSet<>(), new ArrayList<>(), new HashSet<>(),
                new HashSet<>(), new HashMap<>());
        var gap = AssemblyCompileDiagnostics.get();
        assertNotNull(gap);
        assertEquals("E_PRIMITIVE_EXTRUDE_MODE_UNSUPPORTED", gap.code());
        assertTrue(gap.message().contains("ribbon_shell"));
        assertTrue(patches.isEmpty());
    }

    @Test
    void unsupportedCsgOperationDoesNotEmitAUnion() {
        var shape = new Component("PRIMITIVE", "main", new Vec3i(0, 0, 0), new Dimensions(7, 5, 4),
                List.of(), Map.of("operations", List.of(Map.of("op", "union", "kind", "box"),
                        Map.of("op", "symmetric_difference", "kind", "cylinder"))));
        var components = List.of(shape);
        var plan = LlmPlanTestFixtures.builder().mode(LlmPlan.Mode.build).components(components).build();
        var patches = new ArrayList<BlockPatch>();
        ComponentEmitPass.compileComponents(plan, null, null, false, components, Set.of(), Map.of(), patches,
                new ArrayList<>(), new ArrayList<>(), new HashSet<>(), new ArrayList<>(), new HashSet<>(),
                new HashSet<>(), new HashMap<>());
        var gap = AssemblyCompileDiagnostics.get();
        assertNotNull(gap);
        assertEquals("E_PRIMITIVE_CSG_OP_UNSUPPORTED", gap.code());
        assertTrue(gap.message().contains("symmetric_difference"));
        assertTrue(patches.isEmpty());
        assertEquals(com.formacraft.common.geometry.shape.CsgOp.UNION,
                com.formacraft.common.geometry.shape.CsgOp.parse(null));
    }

    @Test
    void unknownPrimitiveShapesFailInsteadOfEmittingABox() {
        for (var params : List.<Map<String, Object>>of(
                Map.of("kind", "unseen_ribbon_shell"),
                Map.of("kind", "box", "subtract", Map.of("kind", "unseen_ribbon_shell")))) {
            AssemblyCompileDiagnostics.clear();
            var shape = new Component("PRIMITIVE", "main", new Vec3i(0, 0, 0),
                    new Dimensions(7, 5, 4), List.of(), params);
            var components = List.of(shape);
            var plan = LlmPlanTestFixtures.builder().mode(LlmPlan.Mode.build).components(components).build();
            var patches = new ArrayList<BlockPatch>();
            ComponentEmitPass.compileComponents(plan, null, null, false, components, Set.of(), Map.of(), patches,
                    new ArrayList<>(), new ArrayList<>(), new HashSet<>(), new ArrayList<>(), new HashSet<>(),
                    new HashSet<>(), new HashMap<>());
            var gap = AssemblyCompileDiagnostics.get();
            assertNotNull(gap);
            assertEquals("E_PRIMITIVE_SHAPE_UNSUPPORTED", gap.code());
            assertTrue(gap.message().contains("unseen_ribbon_shell"));
            assertTrue(patches.isEmpty());
        }
        assertEquals(com.formacraft.common.geometry.shape.ShapeKind.BOX,
                com.formacraft.common.geometry.shape.ShapeKind.parse(null));
        assertEquals(com.formacraft.common.geometry.shape.ShapeKind.BOX,
                com.formacraft.common.geometry.shape.ShapeKind.parse("rectangle"));
    }

    @Test
    void unsupportedStructureReportsItsIdentityAndGeometryRepairOptions() {
        var structure = new Component("STRUCTURE", "main", new Vec3i(2, 4, 2), new Dimensions(3, 1, 1),
                List.of(), Map.of("component_id", "pipe_run", "structure_type", "unsupported_pipe"));
        var components = List.of(structure);
        var plan = LlmPlanTestFixtures.builder().mode(LlmPlan.Mode.build).components(components).build();
        var patches = new ArrayList<BlockPatch>();
        ComponentEmitPass.compileComponents(plan, null, null, false, components, Set.of(), Map.of(), patches,
                new ArrayList<>(), new ArrayList<>(), new HashSet<>(), new ArrayList<>(), new HashSet<>(),
                new HashSet<>(), new HashMap<>());
        var gap = AssemblyCompileDiagnostics.get();
        assertNotNull(gap);
        assertTrue(patches.isEmpty());
        assertEquals("E_COMPONENT_GENERATION_FAILED", gap.code());
        assertEquals("pipe_run", gap.details().get("component_id"));
        assertEquals("required_component_empty", gap.details().get("generator_code"));
        assertTrue(gap.suggestions().stream().anyMatch(s -> s.contains("PRIMITIVE")));
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
