package com.formacraft.common.palette.component;

import com.formacraft.common.llm.dto.StyleAttributes;
import com.formacraft.common.semantic.SemanticPart;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class PaletteLibraryTest {

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
}
