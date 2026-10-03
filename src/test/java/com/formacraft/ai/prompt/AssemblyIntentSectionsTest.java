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
        assertTrue(block.contains("spiral_watchtower"));
        assertTrue(block.contains("Do NOT put params.assembly inside MASS"));
    }

    @Test
    void suppressesProportionAndLandmarkHintsWhenAssemblyIntent() {
        String prompt = PromptAssembler.assemble("原创螺旋瞭望塔，不要地标，用 ASSEMBLY 自由几何", PromptMode.BUILD);
        // The generic system rules mention these names; check injected content,
        // not incidental references in the policy text.
        assertFalse(prompt.contains("PROPORTION ONTOLOGY (research before dimensions)"));
        assertFalse(prompt.contains("MANDATORY FOR THIS REQUEST"));
        assertFalse(prompt.contains("AVAILABLE LANDMARK MODULES (fixed-form iconic structures)"));
        assertTrue(prompt.contains("ASSEMBLY INTENT"));
        assertTrue(prompt.contains("spiral_watchtower"));
    }

    @Test
    void stillIncludesProportionHintForOrdinaryHouse() {
        String prompt = PromptAssembler.assemble("建一栋中式别墅，带庭院", PromptMode.BUILD);
        assertFalse(prompt.contains("ASSEMBLY INTENT (MANDATORY"));
    }
}
