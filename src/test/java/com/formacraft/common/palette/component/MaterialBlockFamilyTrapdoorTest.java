package com.formacraft.common.palette.component;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MaterialBlockFamilyTrapdoorTest {
    @Test
    void woodFamilyYieldsTrapdoorOffline() {
        assertEquals("minecraft:oak_trapdoor", MaterialBlockFamily.trapdoor("minecraft:oak_planks"));
        assertEquals("minecraft:spruce_trapdoor", MaterialBlockFamily.trapdoor("minecraft:spruce_planks"));
    }

    @Test
    void stoneFamilyHasNoTrapdoor() {
        assertNull(MaterialBlockFamily.trapdoor("minecraft:stone_bricks"));
        assertNull(MaterialBlockFamily.trapdoor("minecraft:white_concrete"));
    }

    @Test
    void buttonShapeCandidateForStone() {
        // Offline string candidate — may be non-null; registry check deferred at runtime.
        String btn = MaterialBlockFamily.button("minecraft:stone");
        assertNotNull(btn);
        assertTrue(btn.endsWith("_button"));
    }
}
