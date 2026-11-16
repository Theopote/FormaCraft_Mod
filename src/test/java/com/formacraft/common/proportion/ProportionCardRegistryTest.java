package com.formacraft.common.proportion;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProportionCardRegistryTest {

    @Test
    void resolveForPrompt_cottage() {
        ProportionCardRegistry.ProportionCard card = ProportionCardRegistry.resolveForPrompt(
                "盖一个 7x7 的小石头房子，带门和窗");
        assertNotNull(card);
        assertTrue("cottage_refined".equals(card.id()) || "cottage".equals(card.typology()));
    }

    @Test
    void resolveForPrompt_castle() {
        ProportionCardRegistry.ProportionCard card = ProportionCardRegistry.resolveForPrompt(
                "盖一座中世纪石头城堡，有塔楼和城墙");
        assertNotNull(card);
        assertTrue("castle_wall".equals(card.id()));
    }

    @Test
    void promptBlock_nonEmpty() {
        ProportionCardRegistry.ProportionCard card = ProportionCardRegistry.getById("castle_wall");
        assertNotNull(card);
        String block = ProportionCardRegistry.promptBlockForCard(card);
        assertTrue(block.contains("PROPORTION ONTOLOGY"));
        assertTrue(block.contains("proportion_hints"));
        assertTrue(block.contains("NON-BINDING research hint"));
        assertTrue(!block.contains("You MUST output proportion_hints"));
        assertTrue(block.contains("Honour the user's text"));
    }
}
