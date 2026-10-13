package com.formacraft.common.generation.component.impl;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class MassMainFaceSuppressTest {
    @Test
    void parseSuppressFacesFromList() {
        assertEquals(Set.of("NORTH", "WEST"),
                MassMainGenerator.parseSuppressFaces(Map.of("suppress_window_faces", java.util.List.of("NORTH", "WEST")),
                        "suppress_window_faces"));
    }

    @Test
    void northFaceSuppressedAtMaxZ() {
        assertTrue(MassMainGenerator.isFaceSuppressed(3, 7, 8, 8, Set.of("NORTH")));
        assertFalse(MassMainGenerator.isFaceSuppressed(3, 0, 8, 8, Set.of("NORTH")));
        assertTrue(MassMainGenerator.isFaceSuppressed(3, 0, 8, 8, Set.of("SOUTH")));
    }
}
