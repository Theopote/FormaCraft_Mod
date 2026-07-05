package com.formacraft.server.compiler;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ComponentPlanCompilerTypeNormalizationTest {

    @Test
    void mapsWindowTypesToFacadeWindows() throws Exception {
        assertEquals("FACADE_WINDOWS", normalize("WINDOW"));
        assertEquals("FACADE_WINDOWS", normalize("WINDOWS"));
        assertEquals("FACADE_WINDOWS", normalize("FACADE_WINDOWS"));
    }

    @Test
    void mapsDefensiveWallSynonymsToWall() throws Exception {
        assertEquals("WALL", normalize("RAMPART"));
        assertEquals("WALL", normalize("PALISADE"));
        assertEquals("WALL", normalize("BARRIER"));
        assertEquals("WALL", normalize("PARAPET"));
        assertEquals("WALL", normalize("STONE_SCREEN"));
    }

    @Test
    void returnsEmptyForTrulyUnknownTypes() throws Exception {
        assertEquals("", normalize("NOT_A_REAL_COMPONENT"));
    }

    private static String normalize(String type) throws Exception {
        Method m = ComponentPlanCompiler.class.getDeclaredMethod(
                "normalizeComponentType",
                String.class,
                boolean.class
        );
        m.setAccessible(true);
        return (String) m.invoke(null, type, false);
    }
}
