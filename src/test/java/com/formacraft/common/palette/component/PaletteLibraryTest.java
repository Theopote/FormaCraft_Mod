package com.formacraft.common.palette.component;

import com.formacraft.common.llm.dto.StyleAttributes;
import com.formacraft.common.semantic.SemanticPart;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.*;

class PaletteLibraryTest {
    @org.junit.jupiter.api.BeforeAll
    static void initializeRegistries() {
        com.formacraft.test.MinecraftRegistryTestBootstrap.initialize();
    }


    @Test
    void forStyle_fuzzyMatchesUnknownProfiles() {
        assertEquals(
                PaletteLibrary.forStyle("MODERN_CLASSIC"),
                PaletteLibrary.forStyle("Some_Modern_Glass_Tower")
        );
        assertEquals(
                PaletteLibrary.forStyle("HUI_STYLE_VILLA"),
                PaletteLibrary.forStyle("Custom_Chinese_Villa_Style")
        );
    }

    @Test
    void resolveBlock_prefersStyleAttributesOverPalette() {
        StyleAttributes attrs = new StyleAttributes(
                "white", "plaster", "gray", "tile", "brown", "stone", null, null
        );
        String wall = PaletteLibrary.resolveBlock(SemanticPart.WALL, "UNKNOWN_STYLE_XYZ", attrs);
        assertNotEquals("minecraft:stone", wall);
    }

    @Test
    void partialAttributesKeepModernRoofAndWallPalettes() {
        var wallOnly = new StyleAttributes("white", "concrete", null, null, null, null, null, null);
        var roofOnly = new StyleAttributes(null, null, null, "slate", null, null, null, null);
        for (int i = 0; i < 100; i++) {
            assertTrue(java.util.Set.of("minecraft:gray_concrete", "minecraft:light_gray_concrete").contains(
                    PaletteLibrary.resolveBlock(SemanticPart.ROOF, "MODERN", wallOnly)));
            assertTrue(java.util.Set.of("minecraft:white_concrete", "minecraft:light_gray_concrete", "minecraft:gray_concrete").contains(
                    PaletteLibrary.resolveBlock(SemanticPart.WALL, "MODERN", roofOnly)));
        }
        assertEquals("minecraft:white_concrete", PaletteLibrary.resolveBlock(SemanticPart.WALL, "MODERN", wallOnly));
        assertEquals("minecraft:deepslate_tiles", PaletteLibrary.resolveBlock(SemanticPart.ROOF, "MODERN", roofOnly));
    }

    @Test
    void emptyAttributesLeaveAllRolesUnspecified() {
        var empty = new StyleAttributes(null, null, null, null, null, null, null, null);
        for (var part : java.util.List.of(SemanticPart.WALL, SemanticPart.ROOF, SemanticPart.FLOOR,
                SemanticPart.WINDOW, SemanticPart.PILLAR, SemanticPart.DECOR))
            assertNull(com.formacraft.common.palette.dynamic.DynamicPaletteResolver.resolve(part, empty));
    }

    @Test
    void materialValidationAcceptsAliasesAndRejectsUnknownIds() {
        assertEquals("minecraft:gray_concrete", com.formacraft.common.palette.dynamic.DynamicPaletteResolver.mapMaterialToBlock("concrete"));
        assertEquals("minecraft:oak_planks", com.formacraft.common.palette.dynamic.DynamicPaletteResolver.mapMaterialToBlock("wood_planks"));
        var invalid = new StyleAttributes(null, "minecraft:not_a_block", null, null, null, null, null, null);
        assertEquals("style_attributes.wall_material=minecraft:not_a_block",
                com.formacraft.common.palette.dynamic.ExplicitMaterialPolicy.invalidAttribute(invalid).orElseThrow());
        assertNull(com.formacraft.common.palette.dynamic.DynamicPaletteResolver.mapMaterialToBlock("minecraft:red_bricks"));
        for (var alias : java.util.List.of("red_lacquer", "white_marble", "white_plaster", "membrane",
                "flat_slab", "grey_tile", "slate_tile", "dark_glazed_tile"))
            assertNotNull(com.formacraft.common.palette.dynamic.DynamicPaletteResolver.mapMaterialToBlock(alias), alias);
    }
}
