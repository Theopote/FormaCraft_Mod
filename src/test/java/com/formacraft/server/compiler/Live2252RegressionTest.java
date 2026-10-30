package com.formacraft.server.compiler;

import com.formacraft.common.compiler.semantic.SemanticComponent;
import com.formacraft.common.generation.component.ComponentGeneratorRegistry;
import com.formacraft.common.generation.component.impl.RoofGenerator;
import com.formacraft.common.llm.dto.*;
import com.formacraft.server.assembly.AssemblyCompileDiagnostics;
import com.formacraft.test.MinecraftRegistryTestBootstrap;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.*;
import java.util.*;
import java.util.stream.Stream;
import static org.junit.jupiter.api.Assertions.*;

class Live2252RegressionTest {
    @BeforeAll static void init() { MinecraftRegistryTestBootstrap.initialize(); }
    @AfterEach void clear() { AssemblyCompileDiagnostics.clear(); }
    private LlmPlan load(String name) throws Exception {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper()
                .configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        try (var in = getClass().getResourceAsStream("/regressions/ingame_20261029_2252/" + name + ".json")) {
            return mapper.readValue(in, LlmPlan.class);
        }
    }
    @Test void centeredSatellitesTranslateOnceAndKeepLegacyCornerDefaults() {
        for (String type : List.of("BALCONY", "TERRACE", "PRIMITIVE", "ROOF", "GATE", "KEEP", "CHIMNEY", "SIGNAGE", "PATH", "ROAD", "ENTRANCE", "FACADE_WINDOWS")) {
            var generator = ComponentGeneratorRegistry.getGenerator(type);
            assertNotNull(generator, type);
            var p = new HashMap<String, Object>();
            p.put("roof_type", "flat"); p.put("kind", "box");
            var corner = new Component(type, null, new Vec3i(20, 10, -30), new Dimensions(8, 6, 4), List.of(), p);
            var baseline = generator.generate(new SemanticComponent(type, null, corner, "MODERN"));
            assertFalse(baseline.isEmpty(), type);
            for (String alias : List.of("anchor_mode", "anchorMode")) {
                var params = new HashMap<>(p); params.put(alias, "center");
                var centered = new Component(type, null, corner.relativePosition(), corner.dimensions(), List.of(), params);
                var actual = generator.generate(new SemanticComponent(type, null, centered, "MODERN"));
                var expected = baseline.stream().map(b -> new BlockPos(b.dx()-4, b.dy(), b.dz()-3)).collect(java.util.stream.Collectors.toSet());
                assertEquals(expected, actual.stream().map(b -> new BlockPos(b.dx(), b.dy(), b.dz())).collect(java.util.stream.Collectors.toSet()), type + alias);
            }
        }
    }
    private Map<BlockPos, Integer> roof(String style, String profile) {
        return roof(style, profile, new Dimensions(21,25,10));
    }
    private Map<BlockPos, Integer> roof(String style, String profile, Dimensions dimensions) {
        var params = new HashMap<String,Object>(); params.put("roof_type", "gable"); params.put("gable_walls", false);
        params.put("roof_block", "minecraft:stone");
        if (profile != null) params.put("roof_slope_profile", profile);
        var c = new Component("ROOF", null, new Vec3i(0, 10, 0), dimensions, List.of(), params);
        var tops = new HashMap<BlockPos,Integer>();
        for (var b : new RoofGenerator().generate(new SemanticComponent("ROOF", null, c, style)))
            tops.merge(new BlockPos(b.dx(), 0, b.dz()), b.dy(), Math::max);
        return tops;
    }
    @Test void widerFoundationUsesItsOwnCenterRatherThanTheBodyCorner() {
        var body = new Component("MASS_MAIN", null, new Vec3i(20,2,-30), new Dimensions(10,10,6), List.of(),
                Map.of("component_id","body", "anchor_mode","center", "roof_type","none", "window_style","none", "entrance_type","none"));
        var base = new Component("FOUNDATION", null, new Vec3i(20,0,-30), new Dimensions(16,14,2), List.of(),
                Map.of("component_id","base", "host_id","body", "anchor_mode","center"));
        var plan = LlmPlanTestFixtures.builder().components(List.of(base, body)).build();
        var prepared = ComponentSatelliteInference.prepareComponents(plan, Map.of(), false);
        var resolved = prepared.components().stream().filter(c -> "base".equals(c.params().get("component_id"))).findFirst().orElseThrow();
        assertEquals(new Vec3i(12,0,-37), resolved.relativePosition());
        assertEquals("min_corner", resolved.params().get("anchor_mode"));
    }
    @Test void crownRadiusKeepsTheAuthoredCenter() {
        var c = new Component("CROWN", null, new Vec3i(20,10,-30), new Dimensions(2,2,5), List.of(),
                Map.of("anchor_mode","center", "crown_radius",3, "crown_height",5));
        var blocks = new com.formacraft.common.generation.component.impl.CrownGenerator().generate(new SemanticComponent("CROWN",null,c));
        assertEquals(40, blocks.stream().mapToInt(b->b.dx()).min().orElseThrow()+blocks.stream().mapToInt(b->b.dx()).max().orElseThrow());
        assertEquals(-60, blocks.stream().mapToInt(b->b.dz()).min().orElseThrow()+blocks.stream().mapToInt(b->b.dz()).max().orElseThrow());
    }
    @Test void explicitCenteredFenceUsesTheSameFrameAsItsWall() {
        var c = new Component("WALL", null, new Vec3i(20,0,-30), new Dimensions(8,1,1), List.of(),
                Map.of("anchor_mode","center", "wall_block","minecraft:oak_fence"));
        var blocks = new com.formacraft.common.generation.component.impl.WallComponentGenerator().generate(new SemanticComponent("WALL",null,c));
        assertEquals(16, blocks.stream().mapToInt(b->b.dx()).min().orElseThrow());
        assertEquals(23, blocks.stream().mapToInt(b->b.dx()).max().orElseThrow());
    }
    @Test void pitchedStylesHaveDifferentBlockCrossSectionsWithSameMaterialAndEnvelope() {
        var chinese = roof("CHINESE_TRADITIONAL", null);
        var japanese = roof("JAPANESE", null);
        var gothic = roof("GOTHIC", null);
        assertNotEquals(chinese, japanese); assertNotEquals(japanese, gothic); assertNotEquals(chinese, gothic);
        for (var tops : List.of(chinese, japanese, gothic)) {
            assertEquals(10, Collections.min(tops.values())); assertEquals(19, Collections.max(tops.values()));
            for (int x=0; x<21; x++) assertEquals(tops.get(new BlockPos(x,0,12)), tops.get(new BlockPos(20-x,0,12)));
        }
        assertEquals(roof("CHINESE_TRADITIONAL", "linear"), roof("GOTHIC", "linear"));
        var small = new Dimensions(9,11,3);
        assertNotEquals(roof("CHINESE_TRADITIONAL",null,small), roof("JAPANESE",null,small),
                "Style difference must survive quantization on a three-block-high roof");
    }
    @TestFactory Stream<DynamicTest> replayCurrentSession() {
        return Stream.of("225358", "225448", "225713", "225828", "230021", "230232", "230359", "230526", "230659", "230919", "231048", "231158", "231336", "231614", "231808", "231921", "232025", "232144")
                .map(name -> DynamicTest.dynamicTest(name, () -> {
                    AssemblyCompileDiagnostics.clear();
                    var blocks = ComponentPlanCompiler.compile(load(name), BlockPos.ORIGIN, null, null, false);
                    var gap = AssemblyCompileDiagnostics.get();
                    System.out.println("LIVE2252 " + name + " blocks=" + blocks.size() + " gap=" + gap);
                    // Soft-repairs may clear former hard gaps; remaining rejects keep a stable code.
                    var rejected = Map.of(
                            "225358", "E_STAIR_COMPONENT_INVALID",
                            "225448", "E_COMPONENT_GENERATION_FAILED",
                            "230021", "E_FLOOR_LAYOUT_OVERFLOW",
                            "230359", "E_PATCH_CONFLICT",
                            "231048", "E_PATCH_CONFLICT",
                            "231158", "E_PATCH_CONFLICT",
                            "231336", "E_PATCH_CONFLICT",
                            "231921", "E_STAIR_COMPONENT_INVALID",
                            "232025", "E_OPENING_MATERIAL_MISMATCH");
                    if (!rejected.containsKey(name)) {
                        assertNull(gap, name); assertFalse(blocks.isEmpty(), name);
                    } else {
                        assertTrue(blocks.isEmpty(), name); assertNotNull(gap, name);
                        assertEquals(rejected.get(name), gap.code(), name);
                    }
                }));
    }
}
