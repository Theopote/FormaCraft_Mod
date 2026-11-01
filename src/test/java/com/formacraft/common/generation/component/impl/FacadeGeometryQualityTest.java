package com.formacraft.common.generation.component.impl;

import com.formacraft.common.compiler.semantic.SemanticComponent;
import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.dto.Dimensions;
import com.formacraft.common.llm.dto.GlobalConstraints;
import com.formacraft.common.llm.dto.Slot;
import com.formacraft.common.llm.dto.Vec3i;
import com.formacraft.common.patch.BlockPatch;
import com.formacraft.server.phase2.Phase3RoofFacadeQuality;
import com.formacraft.test.MinecraftRegistryTestBootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Facade geometry: floor bands, pier gaps, and face-axis conventions aligned with generators.
 */
class FacadeGeometryQualityTest {

    @BeforeAll
    static void bootstrap() {
        MinecraftRegistryTestBootstrap.initialize();
    }

    @Test
    void multiFloorApartmentRhythmHasFourYBandsAndPiers() {
        List<BlockPatch> patches = sizedFacade(
                14, 10, 16,
                Map.of(
                        "faces", List.of("SOUTH"),
                        "window_width", 2,
                        "window_height", 2,
                        "windows_per_wall", 3,
                        "sill_offset", 1,
                        "floor_count", 4,
                        "floor_height", 4,
                        "rhythm", "regular",
                        "reserve_entrance_bay", false
                )
        );
        assertFalse(patches.isEmpty());
        Set<Integer> glassYs = patches.stream()
                .filter(p -> p.targetBlock() != null && p.targetBlock().contains("glass"))
                .map(BlockPatch::dy)
                .collect(Collectors.toCollection(HashSet::new));
        // 4 floors × 2-high windows → at least 4 distinct sill/glazing bands (often 8 Ys).
        assertTrue(glassYs.size() >= 4, "floor rhythm Ys=" + glassYs);

        Phase3RoofFacadeQuality.FacadeReport report = Phase3RoofFacadeQuality.analyze(
                patches, Set.of("window_rhythm")).facade();
        assertTrue(report.southOpenings() >= 4, "SOUTH openings=" + report.southOpenings());
        assertTrue(report.southRhythmScore() >= 0.35, "rhythm=" + report.southRhythmScore());
        assertTrue(report.southPierGapRatio() >= 0.12,
                "pier gaps=" + report.southPierGapRatio());
        assertTrue(report.openingDistinctYs() >= 3, "distinctYs=" + report.openingDistinctYs());
    }

    @Test
    void southFaceOpeningsLandOnMinZMatchingGeneratorConvention() {
        List<BlockPatch> patches = sizedFacade(
                12, 8, 8,
                Map.of(
                        "faces", List.of("SOUTH"),
                        "window_width", 2,
                        "window_height", 2,
                        "windows_per_wall", 2,
                        "sill_offset", 1,
                        "floor_count", 2,
                        "floor_height", 4,
                        "reserve_entrance_bay", false
                )
        );
        int minZ = patches.stream().mapToInt(BlockPatch::dz).min().orElse(99);
        long onMinZ = patches.stream()
                .filter(p -> p.targetBlock() != null && p.targetBlock().contains("glass"))
                .filter(p -> p.dz() == minZ)
                .count();
        assertTrue(onMinZ >= 4, "SOUTH windows should sit on minZ, onMinZ=" + onMinZ + " minZ=" + minZ);

        Phase3RoofFacadeQuality.FacadeReport report = Phase3RoofFacadeQuality.analyze(
                patches, Set.of("openings")).facade();
        assertTrue(report.southOpenings() > 0, "Phase3 SOUTH must match generator minZ face");
        assertEquals(0, report.northOpenings(), "NORTH should be empty when only SOUTH authored");
    }

    @Test
    void wrapFacadePlacesOpeningsOnMultipleCardinals() {
        List<BlockPatch> patches = sizedFacade(
                10, 10, 8,
                Map.of(
                        "faces", List.of("SOUTH", "NORTH", "EAST", "WEST"),
                        "window_width", 2,
                        "window_height", 2,
                        "windows_per_wall", 2,
                        "sill_offset", 1,
                        "floor_count", 2,
                        "floor_height", 4,
                        "reserve_entrance_bay", false
                )
        );
        Phase3RoofFacadeQuality.FacadeReport report = Phase3RoofFacadeQuality.analyze(
                patches, Set.of("openings")).facade();
        int facesHit = 0;
        if (report.southOpenings() > 0) facesHit++;
        if (report.northOpenings() > 0) facesHit++;
        if (report.eastOpenings() > 0) facesHit++;
        if (report.westOpenings() > 0) facesHit++;
        assertTrue(facesHit >= 3, "wrap should hit >=3 faces, report=" + report);
    }

    @Test
    void phase3FaceAxesAgreeWithSizedFacadeWindows() {
        // Build a single EAST-face opening strip and ensure Phase3 counts it as east, not west.
        List<BlockPatch> patches = sizedFacade(
                8, 10, 6,
                Map.of(
                        "faces", List.of("EAST"),
                        "window_width", 2,
                        "window_height", 2,
                        "windows_per_wall", 2,
                        "sill_offset", 1,
                        "floor_count", 1,
                        "floor_height", 6,
                        "reserve_entrance_bay", false
                )
        );
        Phase3RoofFacadeQuality.FacadeReport report = Phase3RoofFacadeQuality.analyze(
                patches, Set.of("openings")).facade();
        assertTrue(report.eastOpenings() > 0, "EAST face openings=" + report.eastOpenings());
        assertEquals(0, report.westOpenings());
    }

    @Test
    void eastWestOnlyFacesDoNotLeakOntoSouth() {
        int width = 12;
        int depth = 10;
        List<BlockPatch> patches = sizedFacade(
                width, depth, 8,
                Map.of(
                        "faces", List.of("EAST", "WEST"),
                        "window_width", 2,
                        "window_height", 2,
                        "windows_per_wall", 2,
                        "sill_offset", 1,
                        "floor_count", 2,
                        "floor_height", 4,
                        "reserve_entrance_bay", false
                )
        );
        List<BlockPatch> glass = patches.stream()
                .filter(p -> p.targetBlock() != null && p.targetBlock().contains("glass"))
                .toList();
        assertFalse(glass.isEmpty());
        assertTrue(glass.stream().anyMatch(p -> p.dx() == 0), "EAST (minX) openings");
        assertTrue(glass.stream().anyMatch(p -> p.dx() == width - 1), "WEST (maxX) openings");
        // Pure SOUTH/NORTH wall cells (not E/W corner columns) must stay opaque.
        long leakedSouth = glass.stream()
                .filter(p -> p.dz() == 0 && p.dx() > 0 && p.dx() < width - 1)
                .count();
        long leakedNorth = glass.stream()
                .filter(p -> p.dz() == depth - 1 && p.dx() > 0 && p.dx() < width - 1)
                .count();
        assertEquals(0, leakedSouth, "SOUTH wall must stay clear when faces=EAST/WEST");
        assertEquals(0, leakedNorth, "NORTH wall must stay clear when faces=EAST/WEST");
    }

    @Test
    void reservedEntranceBayKeepsCenterClearOnSouth() {
        int width = 13;
        List<BlockPatch> patches = sizedFacade(
                width, 1, 8,
                Map.of(
                        "faces", List.of("SOUTH"),
                        "window_width", 2,
                        "window_height", 2,
                        "windows_per_wall", 2,
                        "sill_offset", 1,
                        "floor_count", 2,
                        "floor_height", 4,
                        "reserve_entrance_bay", true,
                        "exclude_center_width", 3
                )
        );
        int mid = width / 2;
        boolean glassInEntrance = patches.stream()
                .filter(p -> p.targetBlock() != null && p.targetBlock().contains("glass"))
                .anyMatch(p -> Math.abs(p.dx() - mid) <= 1);
        assertFalse(glassInEntrance, "entrance bay must not be glazed over");
        assertTrue(patches.stream().anyMatch(p ->
                p.targetBlock() != null && p.targetBlock().contains("glass")), "side bays still glazed");
    }

    private static List<BlockPatch> sizedFacade(int w, int d, int h, Map<String, Object> params) {
        Map<String, Object> p = new HashMap<>(params);
        Component c = new Component(
                "FACADE_WINDOWS",
                "block",
                new Vec3i(0, 0, 0),
                new Dimensions(w, d, h),
                List.of(),
                p
        );
        Slot slot = new Slot("block", new Vec3i(0, 0, 0), GlobalConstraints.Facing.SOUTH, null, null, null);
        return new FacadeWindowsGenerator().generate(new SemanticComponent("FACADE_WINDOWS", slot, c));
    }
}
