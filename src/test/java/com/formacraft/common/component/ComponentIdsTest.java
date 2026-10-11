package com.formacraft.common.component;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class ComponentIdsTest {

    @TempDir
    Path tempDir;

    @Test
    void acceptsSafeIds() {
        assertEquals("door_oak", ComponentIds.validateComponentId("door_oak"));
        assertEquals("a", ComponentIds.validateComponentId("a"));
        assertEquals("x1-y2_z3", ComponentIds.validateComponentId("x1-y2_z3"));
    }

    @Test
    void rejectsPathTraversalAndDots() {
        assertThrows(IllegalArgumentException.class, () -> ComponentIds.validateComponentId(".."));
        assertThrows(IllegalArgumentException.class, () -> ComponentIds.validateComponentId("../evil"));
        assertThrows(IllegalArgumentException.class, () -> ComponentIds.validateComponentId("a/b"));
        assertThrows(IllegalArgumentException.class, () -> ComponentIds.validateComponentId("a\\b"));
        assertThrows(IllegalArgumentException.class, () -> ComponentIds.validateComponentId("door.oak"));
        assertThrows(IllegalArgumentException.class, () -> ComponentIds.validateComponentId(""));
        assertThrows(IllegalArgumentException.class, () -> ComponentIds.validateComponentId(null));
        assertThrows(IllegalArgumentException.class, () -> ComponentIds.validateComponentId("A"));
        String tooLong = "a" + "b".repeat(64);
        assertThrows(IllegalArgumentException.class, () -> ComponentIds.validateComponentId(tooLong));
    }

    @Test
    void resolveStaysInsideBase() {
        Path file = ComponentIds.resolveComponentFile(tempDir, "my_door", ".json");
        assertTrue(file.startsWith(tempDir.toAbsolutePath().normalize()));
        assertTrue(file.getFileName().toString().equals("my_door.json"));
    }

    @Test
    void legacyLoadAllowsDotsButNotTraversal() {
        assertEquals("door.oak", ComponentIds.tryNormalizeLoadId("door.oak"));
        assertNull(ComponentIds.tryNormalizeLoadId(".."));
        assertNull(ComponentIds.tryNormalizeLoadId("../x"));
        assertNull(ComponentIds.tryNormalizeLoadId("a/b"));
    }

    @Test
    void tryResolveRejectsTraversal() {
        assertNull(ComponentIds.tryResolveComponentFile(tempDir, "..", ".json"));
        assertNull(ComponentIds.tryResolveComponentFile(tempDir, "../evil", ".png"));
    }
}
