package com.formacraft.server.compiler;

import com.formacraft.common.generation.component.util.GeneratedSurfaceCapture;
import com.formacraft.common.llm.dto.CapabilityGap;
import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.dto.Dimensions;
import com.formacraft.common.llm.dto.Vec3i;
import com.formacraft.common.patch.BlockPatch;
import com.formacraft.test.MinecraftRegistryTestBootstrap;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Phase 01.7 — per-cell write attribution for developer diagnostics. */
class WriteAttributionTest {
    @BeforeAll
    static void bootstrap() {
        MinecraftRegistryTestBootstrap.initialize();
    }

    @Test
    void formatDeveloperMatchesPhaseContractShape() {
        var report = new WriteAttribution.Report(
                "facade_south_01",
                "main_building",
                new BlockPos(12, 8, 3),
                "MASS_MAIN",
                "FACADE_WINDOWS",
                "POSTPROCESS_DETAIL",
                WriteAttribution.PROTECTED_OPENING_OVERWRITE,
                WriteAttribution.RESOLUTION_REJECTED,
                "opening cannot overwrite another's WINDOW");
        String text = report.formatDeveloper();
        assertTrue(text.contains("Component: facade_south_01"), text);
        assertTrue(text.contains("Host: main_building"), text);
        assertTrue(text.contains("Position:"), text);
        assertTrue(text.contains("Original writer: MASS_MAIN"), text);
        assertTrue(text.contains("Opening writer: FACADE_WINDOWS"), text);
        assertTrue(text.contains("Post-process writer: POSTPROCESS_DETAIL"), text);
        assertTrue(text.contains("Violation: PROTECTED_OPENING_OVERWRITE"), text);
        assertTrue(text.contains("Resolution: rejected"), text);
    }

    @Test
    void surfaceConflictOnWindowClassifiesProtectedOpeningOverwrite() {
        Component mass = mass("main_building", "hall");
        Component facade = facade("facade_south_01", "main_building");
        var ledger = new SurfaceOwnershipLedger();
        var occupancy = new PatchOccupancyIndex();

        List<BlockPatch> wall = List.of(new BlockPatch(BlockPatch.PLACE, 12, 8, 3, "minecraft:stone_bricks"));
        occupancy.accept(mass, wall);
        ledger.accept(mass, wall, List.of(
                new GeneratedSurfaceCapture.Cell(
                        new BlockPos(12, 8, 3), GeneratedSurfaceCapture.Role.WINDOW)), BlockPos.ORIGIN);

        List<BlockPatch> overwrite = List.of(
                new BlockPatch(BlockPatch.PLACE, 12, 8, 3, "minecraft:oak_planks"));
        var conflict = ledger.check(facade, overwrite);
        assertTrue(conflict.isPresent(), "foreign facade must not replace hall window");

        var attribution = WriteAttribution.fromSurfaceConflict(facade, conflict.get(), occupancy);
        assertEquals(WriteAttribution.PROTECTED_OPENING_OVERWRITE, attribution.violation());
        assertEquals(WriteAttribution.RESOLUTION_REJECTED, attribution.resolution());
        assertEquals("MASS_MAIN", attribution.originalWriter());
        assertEquals("hall", attribution.openingWriter());
        assertTrue(attribution.formatDeveloper().contains("Violation: PROTECTED_OPENING_OVERWRITE"));

        Map<String, Object> details = attribution.toDetails();
        assertEquals(WriteAttribution.PROTECTED_OPENING_OVERWRITE, details.get("violation"));
        assertEquals("rejected", details.get("resolution"));
        assertNotNull(details.get("attribution"));
    }

    @Test
    void occupancyConflictCarriesUnauthorizedAttribution() {
        Component a = mass("building_a", "mass_a");
        Component b = mass("building_b", "mass_b");
        var occupancy = new PatchOccupancyIndex();
        var ledger = new SurfaceOwnershipLedger();
        occupancy.accept(a, List.of(new BlockPatch(BlockPatch.PLACE, 0, 1, 0, "minecraft:stone_bricks")));

        var conflict = occupancy.validate(b,
                List.of(new BlockPatch(BlockPatch.PLACE, 0, 1, 0, "minecraft:oak_planks")));
        assertTrue(conflict.isPresent());

        var attribution = WriteAttribution.fromOccupancyConflict(b, conflict.get(), ledger);
        assertEquals(WriteAttribution.UNAUTHORIZED_PLACE_OVERWRITE, attribution.violation());
        assertEquals("MASS_MAIN", attribution.originalWriter());
        assertEquals(WriteAttribution.RESOLUTION_REJECTED, attribution.resolution());
    }

    @Test
    void patchConflictValidatorGapIncludesAttributionDetails() {
        var finding = new PatchConflictValidator.Finding(
                new BlockPos(1, 2, 3), "minecraft:stone", "minecraft:dirt", "unauthorized_final_overwrite");
        CapabilityGap gap = PatchConflictValidator.toCapabilityGap(finding).orElseThrow();
        assertEquals("E_PATCH_CONFLICT", gap.code());
        assertNotNull(gap.details());
        assertEquals(WriteAttribution.UNAUTHORIZED_PLACE_OVERWRITE, gap.details().get("violation"));
        assertTrue(String.valueOf(gap.details().get("attribution")).contains("Resolution: rejected"));
    }

    @Test
    void traceReportsSurfaceRoleWithoutViolation() {
        Component mass = mass("main", "hall");
        var ledger = new SurfaceOwnershipLedger();
        var occupancy = new PatchOccupancyIndex();
        List<BlockPatch> wall = List.of(new BlockPatch(BlockPatch.PLACE, 4, 2, 0, "minecraft:stone_bricks"));
        occupancy.accept(mass, wall);
        ledger.accept(mass, wall, List.of(
                new GeneratedSurfaceCapture.Cell(
                        new BlockPos(4, 2, 0), GeneratedSurfaceCapture.Role.WALL)), BlockPos.ORIGIN);

        var probe = WriteAttribution.trace(new BlockPos(4, 2, 0), occupancy, ledger, mass);
        assertEquals("MASS_MAIN", probe.originalWriter());
        assertEquals("hall", probe.openingWriter());
        assertNull(probe.violation());
        assertEquals("WALL", probe.reason());
    }

    private static Component mass(String buildingId, String componentId) {
        Map<String, Object> params = new HashMap<>();
        params.put("building_id", buildingId);
        params.put("component_id", componentId);
        return new Component("MASS_MAIN", null, new Vec3i(0, 0, 0),
                new Dimensions(8, 8, 6), List.of(), params);
    }

    private static Component facade(String componentId, String hostId) {
        Map<String, Object> params = new HashMap<>();
        params.put("component_id", componentId);
        params.put("host_id", hostId);
        params.put("building_id", hostId);
        return new Component("FACADE_WINDOWS", null, new Vec3i(0, 0, 0),
                new Dimensions(4, 1, 4), List.of(), params);
    }
}
