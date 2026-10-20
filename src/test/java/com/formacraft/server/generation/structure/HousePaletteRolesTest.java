package com.formacraft.server.generation.structure;

import com.formacraft.common.model.build.BuildingSpec;
import com.formacraft.common.model.build.BuildingStyle;
import com.formacraft.common.model.build.Materials;
import com.formacraft.common.style.StyleGenome;
import com.formacraft.common.style.StyleGenomeRegistry;
import com.formacraft.common.style.catalog.StyleProfileCatalog;
import com.formacraft.common.style.profile.*;
import com.formacraft.common.component.model.ComponentPrototype;
import com.formacraft.common.component.variant.SemanticMaterialApplier;
import com.formacraft.common.component.variant.Voxel;
import com.formacraft.common.component.variant.VoxelGrid;
import com.formacraft.common.patch.BlockPatchTargetResolver;
import com.formacraft.test.MinecraftRegistryTestBootstrap;
import net.minecraft.block.Blocks;
import net.minecraft.block.BlockState;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class HousePaletteRolesTest {
    @BeforeAll static void bootstrap() { MinecraftRegistryTestBootstrap.initialize(); }

    private StyleProfile profile(Map<String, Object> materials) {
        var def = new StyleProfileCatalog.StyleProfileDef();
        def.defaults = new StyleProfileCatalog.Defaults();
        def.defaults.materials = materials;
        return new CatalogStyleProfile("test_palette_roles", def, StyleCategory.CULTURAL);
    }

    @Test void allBundledGenomesCarryTwelveRegisteredRolesThroughProfileAdapter() {
        for (String id : List.of("default", "rustic_wood", "medieval_stone", "mingqing_official",
                "modern_minimal", "futuristic_clean")) {
            var genome = StyleGenomeRegistry.load(id);
            var p = new GenomeStyleProfile(genome, BuildingStyle.DEFAULT).palette();
            for (String block : List.of(p.wall, p.roof, p.floor, p.window, p.foundation, p.trim,
                    p.pillar, p.cap, p.door, p.roofTrim, p.stairs, p.roofSlab)) {
                assertTrue(Registries.BLOCK.containsId(Identifier.of(block)), id + ": " + block);
            }
            assertInstanceOf(net.minecraft.block.DoorBlock.class, Registries.BLOCK.get(Identifier.of(p.door)));
            assertInstanceOf(net.minecraft.block.StairsBlock.class, Registries.BLOCK.get(Identifier.of(p.stairs)));
            assertInstanceOf(net.minecraft.block.SlabBlock.class, Registries.BLOCK.get(Identifier.of(p.roofSlab)));
        }
    }

    @Test void catalogRolesReachMaterialSetAndRemainDistinctFromWallTrim() {
        var p = profile(Map.of("door", List.of("birch_door"), "roof_trim", "gold_block",
                "stairs", "brick_stairs", "roof_slab", "brick_slab", "trim", "stone_bricks"));
        var materials = HouseMaterialResolver.resolveMaterials(null, new BuildingSpec(),
                BuildingStyle.DEFAULT, new StyleGenome(), p);
        assertEquals(Blocks.BIRCH_DOOR, materials.door().getBlock());
        assertEquals(Blocks.GOLD_BLOCK, materials.roofTrim().getBlock());
        assertEquals(Blocks.STONE_BRICKS, materials.trim().getBlock());
        assertEquals(Blocks.BRICK_STAIRS, materials.roofStairs().getBlock());
        assertEquals(Blocks.BRICK_SLAB, materials.roofSlab().getBlock());
        assertTrue(materials.roofStairsConfigured());
        assertTrue(materials.roofSlabConfigured());
    }

    @Test void explicitMaterialsBeatProfileAndProfileBeatsGenome() {
        var genome = StyleGenomeRegistry.load("rustic_wood");
        var p = profile(Map.of("wall", "bricks", "floor", "quartz_block", "foundation", "stone"));
        var spec = new BuildingSpec();
        var authored = HouseMaterialResolver.resolveMaterials(null, spec, BuildingStyle.RUSTIC, genome, p);
        assertEquals(Blocks.BRICKS, authored.wall().getBlock());
        assertEquals(Blocks.QUARTZ_BLOCK, authored.floor().getBlock());
        var explicit = new Materials();
        explicit.setWall("minecraft:gold_block");
        explicit.setFoundation("minecraft:iron_block");
        spec.setMaterials(explicit);
        var resolved = HouseMaterialResolver.resolveMaterials(null, spec, BuildingStyle.RUSTIC, genome, p);
        assertEquals(Blocks.GOLD_BLOCK, resolved.wall().getBlock());
        assertEquals(Blocks.IRON_BLOCK, resolved.foundation().getBlock());
    }

    @Test void explicitRoofOwnsItsStairAndSlabFamily() {
        var spec = new BuildingSpec();
        var explicit = new Materials();
        explicit.setRoof("minecraft:bricks");
        spec.setMaterials(explicit);
        var resolved = HouseMaterialResolver.resolveMaterials(null, spec, BuildingStyle.RUSTIC,
                StyleGenomeRegistry.load("rustic_wood"), null);
        assertEquals(Blocks.BRICK_STAIRS, resolved.roofStairs().getBlock());
        assertEquals(Blocks.BRICK_SLAB, resolved.roofSlab().getBlock());
    }

    @Test void missingAndUnknownIdsFallThroughRatherThanTurningIntoAir() {
        var genome = StyleGenomeRegistry.load("rustic_wood");
        var spec = new BuildingSpec();
        var explicit = new Materials();
        explicit.setWall("minecraft:not_a_block");
        explicit.setRoof(null);
        spec.setMaterials(explicit);
        var p = profile(Map.of("wall", "bricks", "door", "minecraft:not_a_door", "stairs", "bad:unknown"));
        var resolved = HouseMaterialResolver.resolveMaterials(null, spec, BuildingStyle.RUSTIC, genome, p);
        assertEquals(Blocks.BRICKS, resolved.wall().getBlock());
        assertEquals(Blocks.SPRUCE_DOOR, resolved.door().getBlock());
        assertEquals(Blocks.SPRUCE_STAIRS, resolved.roofStairs().getBlock());
        var legacy = HouseMaterialResolver.resolveMaterials(null, new BuildingSpec(), null, null, null);
        assertEquals(Blocks.OAK_DOOR, legacy.door().getBlock());
        assertEquals(legacy.trim(), legacy.roofTrim());
    }

    @Test void semanticPaletteCannotOverwriteConfiguredRoofRoles() throws Exception {
        var p = profile(Map.of("stairs", "brick_stairs", "roof_slab", "brick_slab"));
        var spec = new BuildingSpec();
        var resolved = HouseMaterialResolver.resolveMaterials(null, spec, BuildingStyle.DEFAULT, null, p);
        var method = HouseGenerator.class.getDeclaredMethod("applyPaletteToRoof",
                net.minecraft.server.world.ServerWorld.class, BlockPos.class,
                HouseMaterialResolver.MaterialSet.class, String.class, BuildingSpec.class);
        method.setAccessible(true);
        var result = (BlockState[]) method.invoke(new HouseGenerator(), null, BlockPos.ORIGIN,
                resolved, "PALETTE_CHINESE_IMPERIAL_A", spec);
        assertEquals(Blocks.BRICK_STAIRS, result[0].getBlock());
        assertEquals(Blocks.BRICK_SLAB, result[1].getBlock());
    }

    @Test void capturedComponentsConsumeNewRolesWithoutLosingShapeOrFacing() {
        var p = profile(Map.of("door", "birch_door", "roof_trim", "gold_block",
                "stairs", "brick_stairs", "roof_slab", "brick_slab"));
        var rules = new ComponentPrototype.VariantRules.Material();
        rules.semantic_map = Map.of("ENTRY", "door", "EDGE", "roof_trim",
                "SLOPE", "stairs", "RIDGE", "roof_slab");
        var grid = new VoxelGrid();
        var door = new Voxel(0, 0, 0, "minecraft:oak_door[half=upper,facing=west]");
        var trim = new Voxel(1, 0, 0, "minecraft:oak_planks");
        var stairs = new Voxel(2, 0, 0, "minecraft:oak_stairs[facing=east,half=top]");
        var slab = new Voxel(3, 0, 0, "minecraft:oak_slab[type=top]");
        door.addSemanticTag("ENTRY"); trim.addSemanticTag("EDGE");
        stairs.addSemanticTag("SLOPE"); slab.addSemanticTag("RIDGE");
        grid.addAll(List.of(door, trim, stairs, slab));
        SemanticMaterialApplier.apply(grid, rules, null, p);
        assertEquals(Blocks.BIRCH_DOOR, BlockPatchTargetResolver.parse(door.blockState()).getBlock());
        assertTrue(door.blockState().contains("half=upper"));
        assertTrue(door.blockState().contains("facing=west"));
        assertEquals(Blocks.GOLD_BLOCK, BlockPatchTargetResolver.parse(trim.blockState()).getBlock());
        assertEquals(Blocks.BRICK_STAIRS, BlockPatchTargetResolver.parse(stairs.blockState()).getBlock());
        assertTrue(stairs.blockState().contains("half=top"));
        assertTrue(stairs.blockState().contains("facing=east"));
        assertEquals(Blocks.BRICK_SLAB, BlockPatchTargetResolver.parse(slab.blockState()).getBlock());
        assertTrue(slab.blockState().contains("type=top"));
    }
}
