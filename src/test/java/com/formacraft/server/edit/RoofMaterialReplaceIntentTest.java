package com.formacraft.server.edit;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RoofMaterialReplaceIntentTest {
    @Test
    void detectsChineseRoofMaterialReplace() {
        assertTrue(RoofMaterialReplaceIntent.matches(
                "把这栋房子的屋顶换成深色橡木，其他地方全部保持不变。"));
        assertEquals("深色橡木",
                RoofMaterialReplaceIntent.materialPhrase(
                        "把这栋房子的屋顶换成深色橡木，其他地方全部保持不变。").orElseThrow());
    }

    @Test
    void detectsEnglishRoofReplace() {
        assertTrue(RoofMaterialReplaceIntent.matches("change the roof to spruce planks, keep the rest"));
        assertTrue(RoofMaterialReplaceIntent.materialPhrase("change the roof to spruce")
                .orElseThrow().toLowerCase().contains("spruce"));
    }

    @Test
    void ignoresFullRebuildLanguage() {
        assertFalse(RoofMaterialReplaceIntent.matches("把屋顶拆掉重建整栋房子"));
        assertFalse(RoofMaterialReplaceIntent.matches("rebuild the house with a new roof"));
    }

    @Test
    void ignoresNonRoofEdits() {
        assertFalse(RoofMaterialReplaceIntent.matches("把二楼东面的三扇窗户改大"));
        assertFalse(RoofMaterialReplaceIntent.matches("换个门"));
    }

    @Test
    void darkOakPhraseDoesNotCollapseToOak() {
        assertEquals("minecraft:dark_oak_planks",
                RoofMaterialIntent.resolveBlockId("深色橡木").orElseThrow());
        assertEquals("minecraft:dark_oak_planks",
                RoofMaterialIntent.resolveBlockId("dark oak").orElseThrow());
        assertEquals("minecraft:oak_planks",
                RoofMaterialIntent.resolveBlockId("橡木").orElseThrow());
    }
}
