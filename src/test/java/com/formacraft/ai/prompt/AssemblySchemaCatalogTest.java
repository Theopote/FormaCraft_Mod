package com.formacraft.ai.prompt;

import com.formacraft.server.assembly.schema.AssemblySchemaExporter;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AssemblySchemaCatalogTest {

    @BeforeAll
    static void initSchema() {
        AssemblySchemaExporter.initialize();
    }

    @Test
    void selectsSpiralRelevantTypes() {
        var schemas = AssemblySchemaCatalog.selectRelevant("原创螺旋瞭望塔");
        assertTrue(schemas.stream().anyMatch(s -> "SHELL_BOX".equals(s.type())));
        assertTrue(schemas.stream().anyMatch(s -> "FRAME_GRID_3D".equals(s.type())));
    }

    @Test
    void promptBlockIncludesPortsAndPreset() {
        String block = AssemblySchemaCatalog.promptBlockForIntent("螺旋 ASSEMBLY 自由几何");
        assertTrue(block.contains("SHELL_BOX"));
        assertTrue(block.contains("ports:"));
        assertTrue(block.contains("spiral_watchtower"));
    }

    @Test
    void skipsBlockForOrdinaryHouse() {
        assertFalse(AssemblySchemaCatalog.promptBlockForIntent("建一栋中式别墅").contains("ASSEMBLY COMPONENT LIBRARY"));
    }
}
