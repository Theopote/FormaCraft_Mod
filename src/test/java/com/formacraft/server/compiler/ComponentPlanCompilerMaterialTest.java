package com.formacraft.server.compiler;

import com.formacraft.common.llm.dto.*;
import com.formacraft.server.assembly.AssemblyCompileDiagnostics;
import com.formacraft.test.PatchTestSnapshot;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ComponentPlanCompilerMaterialTest {
    @Test void grayBrickMaterialNamesResolveToRegisteredStoneBricksAndCompileAsActualWalls() {
        for(String name:List.of("gray_brick","gray_bricks","grey_brick","grey_bricks","青砖","灰砖")) {
            var attrs=new StyleAttributes(null,name,null,"dark_gray_tile",null,null,null,null);
            assertTrue(com.formacraft.common.palette.dynamic.ExplicitMaterialPolicy.invalidAttribute(attrs).isEmpty());
            assertEquals("minecraft:stone_bricks",com.formacraft.common.palette.dynamic.DynamicPaletteResolver.mapMaterialToBlock(name));
            var p=plan("minecraft:stone_bricks");
            var withAttributes=LlmPlanTestFixtures.builder().mode(LlmPlan.Mode.build).styleProfile("MODERN")
                    .anchor(new Vec3i(0,0,0)).layout(new Layout(null,false,List.of()))
                    .styleAttributes(attrs).components(p.components()).build();
            var output=PatchTestSnapshot.blocks(ComponentPlanCompiler.compile(withAttributes,BlockPos.ORIGIN,null,null,false));
            assertFalse(output.isEmpty(),String.valueOf(AssemblyCompileDiagnostics.get()));
            assertTrue(output.containsValue("minecraft:stone_bricks"));
        }
        assertNull(com.formacraft.common.palette.dynamic.DynamicPaletteResolver.mapMaterialToBlock("othermod:gray_brick"));
        assertNull(com.formacraft.common.palette.dynamic.DynamicPaletteResolver.mapMaterialToBlock("minecraft:gray_brick"));
        for(String name:List.of("dark_gray_tile","dark_grey_tile","dark_gray_tiles","dark_grey_tiles"))
            assertEquals("minecraft:deepslate_tiles",com.formacraft.common.palette.dynamic.DynamicPaletteResolver.mapMaterialToBlock(name));
    }

    @Test void loggedCourtyardMaterialVocabularyResolvesForNativeGalleryGeneration() {
        var attrs=new StyleAttributes(null,"gray_brick",null,"dark_gray_tile",null,"stone_brick",null,null);
        assertTrue(com.formacraft.common.palette.dynamic.ExplicitMaterialPolicy.invalidAttribute(attrs).isEmpty());
        var spec=new com.formacraft.common.model.build.BuildingSpec();
        var materials=new com.formacraft.common.model.build.Materials();
        materials.setWall(com.formacraft.common.palette.dynamic.DynamicPaletteResolver.mapMaterialToBlock(attrs.wallMaterial()));
        materials.setRoof(com.formacraft.common.palette.dynamic.DynamicPaletteResolver.mapMaterialToBlock(attrs.roofMaterial()));
        materials.setFloor(com.formacraft.common.palette.dynamic.DynamicPaletteResolver.mapMaterialToBlock(attrs.floorMaterial()));
        spec.setMaterials(materials);
        var output=com.formacraft.server.generation.typology.builder.CourtyardCompoundBuilder.generate(
                Map.of("width",48,"depth",64,"includeGallery",true,"gallery",Map.of("clearWidth",3)),
                BlockPos.ORIGIN,null,spec).getBlocks();
        assertFalse(output.isEmpty());
        assertTrue(output.stream().anyMatch(b->b.getTargetState().isOf(net.minecraft.block.Blocks.STONE_BRICKS)));
        assertTrue(output.stream().anyMatch(b->b.getTargetState().isOf(net.minecraft.block.Blocks.DEEPSLATE_TILES)));
    }
    @Test void namespacedStoneBrickAliasResolvesWithoutAcceptingUnknownBlockIds() {
        var attrs = new StyleAttributes(null, "minecraft:stone_brick", null, null, null, null, null, null);
        assertEquals("minecraft:stone_bricks",
                com.formacraft.common.palette.dynamic.DynamicPaletteResolver.mapMaterialToBlock(attrs.wallMaterial()));
        assertTrue(com.formacraft.common.palette.dynamic.ExplicitMaterialPolicy.invalidAttribute(attrs).isEmpty());
        assertEquals("minecraft:stone_bricks", com.formacraft.common.palette.dynamic.DynamicPaletteResolver.resolve(
                com.formacraft.common.semantic.SemanticPart.WALL, attrs));
        assertNull(com.formacraft.common.palette.dynamic.DynamicPaletteResolver.mapMaterialToBlock("othermod:stone_brick"));
        assertNull(com.formacraft.common.palette.dynamic.DynamicPaletteResolver.mapMaterialToBlock("minecraft:not_a_block"));
        assertEquals("minecraft:stone_brick_stairs",
                com.formacraft.common.palette.dynamic.DynamicPaletteResolver.mapMaterialToBlock("minecraft:stone_brick_stairs"));
    }
    private Component body(String id, int x, String roof) {
        return new Component("MASS_MAIN", "s", new Vec3i(x, 0, 0), new Dimensions(12, 12, 8), List.of(),
                Map.of("component_id", id, "anchor_mode", "min_corner", "plan_type", "rectangle",
                        "roof_block", roof, "roof_type", "flat", "window_ratio", 0.0));
    }
    @Test void twoBuildingsInheritDistinctRoofMaterialsAndExplicitRoofWins() {
        var a = body("a", 0, "minecraft:deepslate_tiles");
        var b = body("b", 30, "minecraft:spruce_planks");
        var roof = new Component("ROOF", "s", new Vec3i(30, 7, 0), new Dimensions(12, 12, 3), List.of(),
                Map.of("component_id", "b_roof", "host_id", "b", "roof_type", "flat", "roof_block", "minecraft:bricks",
                        "anchor_mode", "min_corner"));
        var plan = LlmPlanTestFixtures.builder().mode(LlmPlan.Mode.build).styleProfile("MODERN")
                .anchor(new Vec3i(0, 0, 0)).layout(new Layout(null, false, List.of())).components(List.of(b, roof, a)).build();
        var output = PatchTestSnapshot.blocks(ComponentPlanCompiler.compile(plan, BlockPos.ORIGIN, null, null, false));
        assertFalse(output.isEmpty(), String.valueOf(AssemblyCompileDiagnostics.get()));
        assertEquals("minecraft:deepslate_tiles", output.get(new Vec3i(5, 7, 5)));
        assertEquals("minecraft:bricks", output.get(new Vec3i(35, 7, 5)));
    }
    @Test void wholePlanReplaysAfterOtherPlansUseSharedPalettes() {
        var plan = LlmPlanTestFixtures.builder().mode(LlmPlan.Mode.build).styleProfile("MEDIEVAL")
                .anchor(new Vec3i(0, 0, 0)).layout(new Layout(null, false, List.of()))
                .proportionHints(Map.of("design_seed", 123L))
                .components(List.of(body("a", 0, "minecraft:deepslate_tiles"))).build();
        var before = PatchTestSnapshot.blocks(ComponentPlanCompiler.compile(plan, BlockPos.ORIGIN, null, null, false));
        assertFalse(before.isEmpty());
        ComponentPlanCompiler.compile(plan("minecraft:stone_bricks"), BlockPos.ORIGIN, null, null, false);
        assertEquals(before, PatchTestSnapshot.blocks(ComponentPlanCompiler.compile(plan, BlockPos.ORIGIN, null, null, false)));
    }
    @BeforeAll static void bootstrap() { com.formacraft.test.MinecraftRegistryTestBootstrap.initialize(); }
    private LlmPlan plan(String wall) {
        var mass = new Component("MASS_MAIN", "s", new Vec3i(0, 0, 0), new Dimensions(12, 12, 8),
                List.of(), Map.of("anchor_mode", "min_corner", "plan_type", "rectangle", "wall_block", wall,
                "window_ratio", 0.0, "roof_type", "flat"));
        return LlmPlanTestFixtures.builder().mode(LlmPlan.Mode.build).styleProfile("MODERN")
                .anchor(new Vec3i(0, 0, 0)).layout(new Layout(null, false, List.of())).components(List.of(mass)).build();
    }
    @Test void unknownMaterialsFallBackAndStillCompile() {
        var source = plan("missingmod:unknown_wall").components().getFirst();
        var params = new HashMap<String, Object>(source.params());
        params.put("allow_patch_overwrite", true);
        var original = LlmPlanTestFixtures.builder().mode(LlmPlan.Mode.build).styleProfile("MODERN")
                .anchor(new Vec3i(0, 0, 0)).layout(new Layout(null, false, List.of()))
                .components(List.of(new Component(source.componentType(), source.slotId(), source.relativePosition(),
                        source.dimensions(), source.features(), params))).build();
        var normalized = com.formacraft.common.palette.dynamic.MaterialFallbackPolicy.normalize(original);
        assertEquals("minecraft:stone_bricks", normalized.components().getFirst().params().get("wall_block"));
        assertEquals("missingmod:unknown_wall", original.components().getFirst().params().get("wall_block"));
        var output = PatchTestSnapshot.blocks(ComponentPlanCompiler.compile(original));
        assertFalse(output.isEmpty(), String.valueOf(AssemblyCompileDiagnostics.get()));
        assertTrue(output.containsValue("minecraft:stone_bricks"));
        assertFalse(AssemblyCompileDiagnostics.hasGap());
    }
    @Test void loggedRoofVocabularyAndUnknownAttributesUseFallbacks() {
        var policy = com.formacraft.common.palette.dynamic.MaterialFallbackPolicy.normalize(
                LlmPlanTestFixtures.builder().styleAttributes(new StyleAttributes(null, "unknown_wall",
                        null, "gray_clay_tile", "dark_timber", "unknown_floor", null, null)).build());
        assertEquals("minecraft:deepslate_tiles", policy.styleAttributes().roofMaterial());
        assertEquals("minecraft:dark_oak_planks", policy.styleAttributes().accentMaterial());
        assertEquals("minecraft:stone_bricks", policy.styleAttributes().wallMaterial());
        assertTrue(com.formacraft.common.palette.dynamic.ExplicitMaterialPolicy.invalidAttribute(policy.styleAttributes()).isEmpty());
    }
    @Test void explicitWallSurvivesWholePostProcessingPipeline() {
        var plan = plan("minecraft:stone_bricks");
        var before = PatchTestSnapshot.blocks(ComponentPlanCompiler.compile(plan));
        assertFalse(before.isEmpty());
        var after = PatchTestSnapshot.blocks(ComponentPlanCompiler.compile(plan, BlockPos.ORIGIN, null, null, false));
        var explicit = before.entrySet().stream().filter(e -> "minecraft:stone_bricks".equals(e.getValue())).toList();
        assertFalse(explicit.isEmpty());
        for (var entry : explicit) assertEquals(entry.getValue(), after.get(entry.getKey()), "material at " + entry.getKey());
    }
}
