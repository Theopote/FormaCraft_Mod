package com.formacraft.server.compiler;

import com.formacraft.common.generation.component.util.ComponentFootprintUtil;
import com.formacraft.common.llm.parser.LlmPlanParser;
import com.formacraft.test.MinecraftRegistryTestBootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Townhall wings must separate from the central hall even when the hall carries
 * plaza/feature text that mentions "steps" (must not be misclassified as a foundation).
 */
class WingSeparationDiagTest {
    @BeforeAll
    static void boot() {
        MinecraftRegistryTestBootstrap.initialize();
    }

    @Test
    void late4WingSouthSeparatesFromHall() throws Exception {
        var json = Files.readString(Path.of(
                "python_backend/tests/fixtures/complex_build_retest/oct18_late_4.normalized.json"));
        var plan = LlmPlanParser.parse(json);
        var repaired = MassFootprintSeparationRepair.apply(plan);
        var hall = repaired.components().stream()
                .filter(c -> "townhall_central_hall".equals(String.valueOf(c.params().get("component_id"))))
                .findFirst().orElseThrow();
        var wing = repaired.components().stream()
                .filter(c -> "wing_south".equals(String.valueOf(c.params().get("component_id"))))
                .findFirst().orElseThrow();
        assertEquals(Boolean.TRUE, wing.params().get("footprint_separated"));
        var hb = ComponentFootprintUtil.bounds(hall);
        var wb = ComponentFootprintUtil.bounds(wing);
        boolean overlap = hb.minX() < wb.maxX() && wb.minX() < hb.maxX()
                && hb.minZ() < wb.maxZ() && wb.minZ() < hb.maxZ();
        assertFalse(overlap, () -> "still overlap hall=" + hb + " wing=" + wb);
    }
}
