package com.formacraft.server.compiler;
import com.formacraft.common.llm.parser.LlmPlanParser;
import com.formacraft.server.assembly.AssemblyCompileDiagnostics;
import org.junit.jupiter.api.*;
import java.nio.file.*;
import java.util.stream.*;
import static org.junit.jupiter.api.Assertions.*;
class Oct19ReplayTest {
    @BeforeAll static void setup() { com.formacraft.test.MinecraftRegistryTestBootstrap.initialize(); }
    @TestFactory Stream<DynamicTest> newPlans() {
        return IntStream.rangeClosed(1,5).mapToObj(i -> DynamicTest.dynamicTest("oct19 plan "+i,()-> {
            var plan=LlmPlanParser.parse(Files.readString(Path.of("python_backend/tests/fixtures/complex_build_retest/oct19_crash_"+i+".normalized.json")));
            var patches=ComponentPlanCompiler.compile(plan);
            assertFalse(patches.isEmpty(),String.valueOf(AssemblyCompileDiagnostics.get()));
            assertFalse(AssemblyCompileDiagnostics.hasGap(),String.valueOf(AssemblyCompileDiagnostics.get()));
        }));
    }
    @Test void grottoParsedDimensionsRemainAuthored() throws Exception {
        var plan=LlmPlanParser.parse(Files.readString(Path.of("python_backend/tests/fixtures/complex_build_retest/oct18_landmark_7.normalized.json")));
        assertEquals(30,plan.components().getFirst().dimensions().width());
        assertEquals(30,plan.components().getFirst().dimensions().depth());
        assertFalse(ComponentPlanCompiler.compile(plan).isEmpty(),String.valueOf(AssemblyCompileDiagnostics.get()));
    }

    @Test void explicitTulouModuleHasAnExecutableRouteAndNativeGeometry() throws Exception {
        var plan=LlmPlanParser.parse(Files.readString(Path.of("python_backend/tests/fixtures/complex_build_retest/oct19_crash_7.normalized.json")));
        assertEquals(3,plan.components().size());
        var component=plan.components().getFirst();
        assertTrue(com.formacraft.common.generation.routing.GenerationFallbackMatrix.effectiveStructureEmpty(
                com.formacraft.common.generation.routing.GenerationAuthority.COMPONENTS,null,component,null));
        assertFalse(com.formacraft.common.generation.routing.GenerationFallbackMatrix.effectiveStructureEmpty(
                com.formacraft.common.generation.routing.GenerationAuthority.COMPONENTS,null,component,java.util.Map.of("generation_authority","COMPONENTS")));
        var spec=new com.formacraft.common.model.build.BuildingSpec();
        spec.setType(com.formacraft.common.model.build.BuildingType.CUSTOM);
        var footprint=new com.formacraft.common.model.build.Footprint();
        footprint.setShape("circle"); footprint.setRadius(18); spec.setFootprint(footprint);
        spec.setFloors(4);spec.setExtra(new java.util.HashMap<>(java.util.Map.of("landmark","tulou")));
        var generator=com.formacraft.server.generation.structure.router.GeneratorRouter.route(spec);
        assertInstanceOf(com.formacraft.server.generation.structure.TulouGenerator.class,generator);
        var structure=generator.generate(spec,net.minecraft.util.math.BlockPos.ORIGIN,null);
        var patches=com.formacraft.server.generation.component.adaptor.StructurePatchBridge.convert(structure,net.minecraft.util.math.BlockPos.ORIGIN,java.util.List.of()).patches();
        assertTrue(patches.size()>1000);
        assertEquals(patches.size(),patches.stream().map(p->new net.minecraft.util.math.BlockPos(p.dx(),p.dy(),p.dz())).distinct().count());
    }

    @Test void gateJoinsOnlyItsBoundTowerAndWallKeepsExistingBuilding() {
        var tower = new com.formacraft.common.llm.dto.Component("TOWER", "slot", null, null,
                java.util.List.of(), java.util.Map.of("component_id", "tower"));
        var gate = new com.formacraft.common.llm.dto.Component("GATE", "slot", null, null,
                java.util.List.of(), java.util.Map.of("host_id", "tower"));
        var foreignGate = new com.formacraft.common.llm.dto.Component("GATE", "slot", null, null,
                java.util.List.of(), java.util.Map.of("host_id", "other"));
        var wall = new com.formacraft.common.llm.dto.Component("WALL", "slot", null, null,
                java.util.List.of(), java.util.Map.of());
        var index = new PatchOccupancyIndex();
        var stone = new com.formacraft.common.patch.BlockPatch("place", 0, 0, 0, "minecraft:stone");
        var brick = new com.formacraft.common.patch.BlockPatch("place", 0, 0, 0, "minecraft:bricks");
        assertTrue(index.accept(tower, java.util.List.of(stone)).isEmpty());
        assertTrue(index.validate(gate, java.util.List.of(brick)).isEmpty());
        assertTrue(index.validate(foreignGate, java.util.List.of(brick)).isPresent());
        var patches = new java.util.ArrayList<>(java.util.List.of(brick,
                new com.formacraft.common.patch.BlockPatch("place", 1, 0, 0, "minecraft:bricks"),
                new com.formacraft.common.patch.BlockPatch("place", 2, 0, 0, "minecraft:bricks")));
        assertEquals(1, index.trimWallTowerJoins(wall, patches));
        assertTrue(index.validate(wall, patches).isEmpty());
        var covered = new java.util.ArrayList<>(java.util.List.of(brick));
        assertEquals(0, index.trimWallTowerJoins(wall, covered));
        assertTrue(index.validate(wall, covered).isPresent());
    }

    @Test void stackedBodiesKeepHostGeometryAndDoNotDeclareMissingWalls() throws Exception {
        for(int i:java.util.List.of(1,5)) {
            var plan=LlmPlanParser.parse(Files.readString(Path.of("python_backend/tests/fixtures/complex_build_retest/oct19_crash_"+i+".normalized.json")));
            var body=plan.components().stream().filter(c->"MASS_MAIN".equals(c.componentType())).findFirst().orElseThrow();
            var cells=new java.util.ArrayList<com.formacraft.common.generation.component.util.GeneratedSurfaceCapture.Cell>();
            com.formacraft.common.generation.component.util.BuildingGeometryContext.begin();
            try (var capture=com.formacraft.common.generation.component.util.GeneratedSurfaceCapture.captureTo(cells::add)) {
                com.formacraft.common.generation.component.util.BuildingGeometryContext.putMass(body);
                var patches=new com.formacraft.common.generation.component.impl.MassMainGenerator().generate(
                        new com.formacraft.common.compiler.semantic.SemanticComponent("MASS_MAIN",null,body));
                assertTrue(com.formacraft.common.generation.component.util.GeneratedSurfaceCapture.missing(cells,patches).isEmpty());
                var cached=com.formacraft.common.generation.component.util.BuildingGeometryContext.get(String.valueOf(body.params().get("component_id")));
                assertEquals(body.dimensions().width(),cached.geometry().footprint().width());
                assertEquals(body.dimensions().depth(),cached.geometry().footprint().depth());
                var parts=com.formacraft.common.generation.component.util.ResolvedMassPart.resolve(body);
                assertEquals(i==1?5:6,parts.size());
                assertTrue(parts.getFirst().dimensions().height()<body.dimensions().height());
            } finally { com.formacraft.common.generation.component.util.BuildingGeometryContext.end(); }
        }
    }
}
