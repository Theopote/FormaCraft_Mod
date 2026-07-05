package com.formacraft.ai.prompt;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AssemblyIntentSectionsTest {

    @Test
    void detectsSpiralWatchtowerIntent() {
        assertTrue(AssemblyIntentSections.detectsFreeformAssemblyIntent(
                "原创螺旋瞭望塔，不要地标模块，用自由几何表达非矩形体量"));
    }

    @Test
    void detectsAssemblyKeyword() {
        assertTrue(AssemblyIntentSections.detectsFreeformAssemblyIntent("Build with ASSEMBLY freeform shell"));
    }

    @Test
    void ignoresOrdinaryHouseRequest() {
        assertFalse(AssemblyIntentSections.detectsFreeformAssemblyIntent("建一栋中式别墅，带庭院"));
    }

    @Test
    void promptBlockIncludesMandatoryAssemblyRouting() {
        String block = AssemblyIntentSections.promptBlockForIntent("螺旋瞭望塔 ASSEMBLY");
        assertTrue(block.contains("component_type=\"ASSEMBLY\""));
        assertTrue(block.contains("twistTurns"));
        assertTrue(block.contains("Do NOT put params.assembly inside MASS"));
    }
}
