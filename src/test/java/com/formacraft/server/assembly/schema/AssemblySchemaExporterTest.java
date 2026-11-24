package com.formacraft.server.assembly.schema;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AssemblySchemaExporterTest {

    @BeforeAll
    static void init() {
        AssemblySchemaExporter.initialize();
    }

    @Test
    void snapshotHasComponentsOpsAndPresets() {
        AssemblySchemaExporter.SchemaSnapshot snap = AssemblySchemaExporter.snapshot();
        assertTrue(snap.schemaVersion() >= 3);
        assertFalse(snap.components().isEmpty());
        assertFalse(snap.ops().isEmpty());
        assertFalse(snap.presets().isEmpty());
        assertTrue(snap.components().stream().anyMatch(c -> "SHELL_BOX".equals(c.type())));
    }

    @Test
    void exportedJsonIsNonEmpty() {
        String json = AssemblySchemaExporter.toJson(AssemblySchemaExporter.snapshot());
        assertTrue(json.contains("\"SHELL_BOX\""));
        assertTrue(json.contains("spiral_watchtower"));
    }

    @Test
    void snapshotIncludesMidLevelArchitecturalOps() {
        AssemblySchemaExporter.SchemaSnapshot snap = AssemblySchemaExporter.snapshot();
        for (String type : java.util.List.of(
                "ARCADE_SERIES", "BUTTRESS_ARRAY", "DRUM_DOME", "LOUVER_SKIN", "CANTILEVER_TIER")) {
            assertTrue(snap.components().stream().anyMatch(c -> type.equals(c.type())), type);
            assertTrue(snap.ops().contains(type), type);
        }
    }
}
