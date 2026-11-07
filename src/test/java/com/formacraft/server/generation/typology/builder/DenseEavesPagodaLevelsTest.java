package com.formacraft.server.generation.typology.builder;

import com.formacraft.common.build.GeneratedStructure;
import com.formacraft.common.build.PlannedBlock;
import com.formacraft.common.typology.TypologyParamResolver;
import com.formacraft.common.typology.TypologyParams;
import com.formacraft.common.typology.detail.ChineseTypologyDetailUtil;
import com.formacraft.test.MinecraftRegistryTestBootstrap;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.formacraft.common.typology.TypologyGenerationException;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DenseEavesPagodaLevelsTest {

    @BeforeAll
    static void boot() {
        MinecraftRegistryTestBootstrap.initialize();
    }

    @Test
    void explicitThreeLevelsNotClampedToSeven() {
        TypologyParams p = new TypologyParams(Map.of("levels", 3));
        assertEquals(3, PagodaVerticalSemantics.resolve(p, 13, 7, 15).floorCount());
    }

    @Test
    void floorCountThreeHonored() {
        TypologyParams p = new TypologyParams(Map.of("floor_count", 3));
        assertEquals(3, PagodaVerticalSemantics.resolve(p, 13, 7, 15).floorCount());
    }

    @Test
    void threeStoreyChongyanNotInflatedToSevenPlus() {
        TypologyParams p = new TypologyParams(Map.of(
                "floor_count", 3,
                "eaves_per_level", 2
        ));
        PagodaVerticalSemantics v = PagodaVerticalSemantics.resolve(p, 13, 7, 15);
        assertEquals(3, v.floorCount());
        assertEquals(6, v.eaveTiers());
    }

    @Test
    void implicitDefaultKeepsTallMin() {
        TypologyParams p = new TypologyParams(Map.of());
        assertEquals(13, PagodaVerticalSemantics.resolve(p, 13, 7, 15).floorCount());
    }

    @Test
    void octagonVerticalSpanFitsPlannedHeightWithDoubleEaves() {
        // Historical failure: body used full height then stacked eaves outside the budget.
        var component = new com.formacraft.common.llm.dto.Component(
                "STRUCTURE",
                null,
                new com.formacraft.common.llm.dto.Vec3i(0, 0, 0),
                new com.formacraft.common.llm.dto.Dimensions(7, 7, 28),
                java.util.List.of("typology:dense_eaves_pagoda"),
                Map.of(
                        "floor_count", 3,
                        "eaves_per_level", 2,
                        "height", 28,
                        "baseWidth", 7,
                        "footprint", "octagon",
                        "detailLevel", "simple"
                )
        );
        Map<String, Object> params = TypologyParamResolver.merge("dense_eaves_pagoda", component);
        GeneratedStructure structure = DenseEavesPagodaBuilder.generate(
                params, new BlockPos(0, 64, 0), null, null);
        int minY = Integer.MAX_VALUE;
        int maxY = Integer.MIN_VALUE;
        for (PlannedBlock block : structure.getBlocks()) {
            int y = block.getPos().getY();
            minY = Math.min(minY, y);
            maxY = Math.max(maxY, y);
        }
        int span = maxY - minY + 1;
        assertTrue(span <= 30, "span=" + span + " should fit planned height 28 (±2 for finial)");
        assertTrue(span >= 20, "span=" + span + " should use most of the planned height");
    }

    @Test
    void llmInferredShortHeightForNineStoreyExpandsInsteadOfFailing() {
        // Round-17: player asked 九层重檐, LLM guessed height=40 (< abs min) — must not E_PAGODA_SIZE.
        BlockPos origin = new BlockPos(0, 64, 0);
        GeneratedStructure structure = generate(Map.of(
                "floor_count", 9,
                "eaves_per_level", 2,
                "height", 40,
                "baseWidth", 9,
                "footprint", "octagon",
                "detailLevel", "simple"
        ), origin, false);
        var geo = PagodaMorphologyAudit.observe(structure.getBlocks(), origin);
        assertEquals(9, geo.measuredFloors());
        assertEquals(18, geo.measuredEaveTiers());
        assertTrue(geo.span() >= 49, "LLM height=40 must soft-expand; span=" + geo.span());
    }

    @Test
    void tightHeightStillEmitsAllExplicitDoubleEaveTiers() {
        // Batch A #1: height=24 must resolve size feasibility up-front (compress), not drop eaves mid-build.
        BlockPos origin = new BlockPos(0, 64, 0);
        GeneratedStructure structure = generate(Map.of(
                "floor_count", 3,
                "eaves_per_level", 2,
                "height", 24,
                "baseWidth", 8,
                "footprint", "octagon",
                "detailLevel", "refined"
        ), origin, true);
        var geo = PagodaMorphologyAudit.observe(structure.getBlocks(), origin);
        assertEquals(3, geo.measuredFloors(), "floor_count");
        assertEquals(6, geo.measuredEaveTiers(), "eave_tiers must not be trimmed under tight height");
        assertTrue(geo.finialCentered(), "compact finial still centered");
        assertTrue(geo.span() <= 26, "authored height=24 must not silently expand past envelope; span=" + geo.span());
    }

    @Test
    void comfortableHeightUsesFullFinialAndMatchesPlannedAboveGroundSpan() {
        BlockPos origin = new BlockPos(0, 64, 0);
        GeneratedStructure structure = generate(Map.of(
                "floor_count", 3,
                "eaves_per_level", 2,
                "height", 26,
                "baseWidth", 8,
                "footprint", "octagon",
                "detailLevel", "simple"
        ), origin, true);
        int maxY = structure.getBlocks().stream()
                .mapToInt(b -> b.getPos().getY())
                .filter(y -> y >= origin.getY())
                .max()
                .orElse(origin.getY());
        int aboveGround = maxY - origin.getY() + 1;
        assertEquals(26, aboveGround, "full finial must consume the reserved 10-block tip; span=" + aboveGround);
        assertFinialConnectedToTopRoof(structure, origin, 26);
    }

    @Test
    void nineStoreyDoubleEaveWithoutAuthoredHeightDoesNotFailOnRegistryDefault() {
        // Registry soft height=47 < abs min for 9×重檐; typology default must stay soft.
        BlockPos origin = new BlockPos(0, 64, 0);
        GeneratedStructure structure = generate(Map.of(
                "floor_count", 9,
                "eaves_per_level", 2,
                "baseWidth", 9,
                "footprint", "octagon",
                "detailLevel", "simple"
        ), origin);
        var geo = PagodaMorphologyAudit.observe(structure.getBlocks(), origin);
        assertEquals(9, geo.measuredFloors());
        assertEquals(18, geo.measuredEaveTiers());
        assertTrue(geo.finialCentered());
        assertTrue(geo.span() >= 49, "should auto-expand past registry 47; span=" + geo.span());
    }

    private static void assertFinialConnectedToTopRoof(
            GeneratedStructure structure, BlockPos origin, int plannedHeight
    ) {
        int finialH = plannedHeight >= PagodaVerticalLayout.requiredMinHeight(
                new PagodaVerticalSemantics(3, 2, 6, true))
                ? PagodaVerticalLayout.FINIAL_HEIGHT_FULL
                : PagodaVerticalLayout.FINIAL_HEIGHT_COMPACT;
        int finialBaseWorldY = origin.getY() + plannedHeight - finialH;
        boolean seatSolid = structure.getBlocks().stream().anyMatch(b ->
                b.getPos().getX() == origin.getX()
                        && b.getPos().getZ() == origin.getZ()
                        && b.getPos().getY() == finialBaseWorldY
                        && !b.getTargetState().isAir());
        assertTrue(seatSolid, "finial base at local Y=" + (plannedHeight - finialH) + " must be solid");
        // No empty axis gap between last roof deck (finialBase-1) and finial base.
        boolean deckOrEave = structure.getBlocks().stream().anyMatch(b ->
                b.getPos().getY() == finialBaseWorldY - 1
                        && Math.abs(b.getPos().getX() - origin.getX()) <= 3
                        && Math.abs(b.getPos().getZ() - origin.getZ()) <= 3
                        && !b.getTargetState().isAir());
        assertTrue(deckOrEave, "top roof/eave must meet finial seat — no floating tip");
    }

    @Test
    void octagonThreeStoreyDoubleEaveAtTwentyEightHasFullMorphology() {
        // Batch A #2
        BlockPos origin = new BlockPos(0, 64, 0);
        GeneratedStructure structure = generate(Map.of(
                "floor_count", 3,
                "eaves_per_level", 2,
                "height", 28,
                "baseWidth", 7,
                "footprint", "octagon",
                "detailLevel", "simple"
        ), origin);
        var geo = PagodaMorphologyAudit.observe(structure.getBlocks(), origin);
        assertEquals(3, geo.measuredFloors());
        assertEquals(6, geo.measuredEaveTiers());
        assertTrue(geo.finialCentered());
        assertTrue(geo.rolesPresent());
        PagodaVerticalSemantics vertical = new PagodaVerticalSemantics(3, 2, 6, true);
        PagodaMorphologyAudit.Result audit = PagodaMorphologyAudit.checkFromBlocks(
                "dense_eaves_pagoda", vertical, structure.getBlocks(), origin, true);
        assertTrue(audit.passed(), String.valueOf(audit.diagnostics()));
    }

    @Test
    void squareThreeStoreySingleEaveKeepsFormalFinial() {
        // Batch A #3: old 2-block tip failed heuristic finial audit; shared finial must pass.
        BlockPos origin = new BlockPos(0, 64, 0);
        GeneratedStructure structure = generate(Map.of(
                "floor_count", 3,
                "eaves_per_level", 1,
                "height", 28,
                "baseWidth", 11,
                "footprint", "square",
                "detailLevel", "simple"
        ), origin);
        var geo = PagodaMorphologyAudit.observe(structure.getBlocks(), origin);
        assertEquals(3, geo.measuredFloors());
        assertEquals(3, geo.measuredEaveTiers());
        assertTrue(geo.finialCentered(), "square must not fail for missing finial");
        assertTrue(geo.rolesPresent());
        PagodaVerticalSemantics vertical = new PagodaVerticalSemantics(3, 1, 3, true);
        PagodaMorphologyAudit.Result audit = PagodaMorphologyAudit.checkFromBlocks(
                "dense_eaves_pagoda", vertical, structure.getBlocks(), origin, true);
        assertTrue(audit.passed(), String.valueOf(audit.diagnostics()));
    }

    @Test
    void squareThreeStoreyDoubleEaveEmitsSixRoleTaggedEaves() {
        // Batch A #4: square path must honor eaves_per_level=2 (not legacy single ring).
        BlockPos origin = new BlockPos(0, 64, 0);
        GeneratedStructure structure = generate(Map.of(
                "floor_count", 3,
                "eaves_per_level", 2,
                "height", 28,
                "baseWidth", 11,
                "footprint", "square",
                "detailLevel", "simple"
        ), origin);
        var geo = PagodaMorphologyAudit.observe(structure.getBlocks(), origin);
        assertEquals(3, geo.measuredFloors());
        assertEquals(6, geo.measuredEaveTiers(), "square double eaves must emit 6 role-tagged tiers");
        assertTrue(geo.finialCentered());
        PagodaVerticalSemantics vertical = new PagodaVerticalSemantics(3, 2, 6, true);
        PagodaMorphologyAudit.Result audit = PagodaMorphologyAudit.checkFromBlocks(
                "dense_eaves_pagoda", vertical, structure.getBlocks(), origin, true);
        assertTrue(audit.passed(), String.valueOf(audit.diagnostics()));
    }

    @Test
    void explicitHeightTooSmallReturnsSizeConflictNotMorphology() {
        TypologyGenerationException ex = assertThrows(
                TypologyGenerationException.class,
                () -> generate(Map.of(
                        "floor_count", 3,
                        "eaves_per_level", 2,
                        "height", 18,
                        "baseWidth", 8,
                        "footprint", "octagon",
                        "detailLevel", "simple"
                ), new BlockPos(0, 64, 0), true));
        assertEquals("E_PAGODA_SIZE", ex.code());
        assertTrue(ex.getMessage().contains("minimum feasible height"), ex.getMessage());
    }

    /** Mark height as player hard constraint (tests that assert no silent expand). */
    private static Map<String, Object> withUserExplicitHeight(Map<String, ?> params) {
        Map<String, Object> m = new HashMap<>(params);
        Map<String, Object> ds = new HashMap<>();
        Object existing = m.get("design_sources");
        if (existing instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> e : map.entrySet()) {
                if (e.getKey() != null) ds.put(String.valueOf(e.getKey()), e.getValue());
            }
        }
        if (m.containsKey("height")) ds.put("height", "USER_EXPLICIT");
        if (m.containsKey("towerHeight")) ds.put("towerHeight", "USER_EXPLICIT");
        m.put("design_sources", ds);
        return m;
    }

    private static GeneratedStructure generate(Map<String, ?> params, BlockPos origin) {
        return generate(params, origin, false);
    }

    private static GeneratedStructure generate(
            Map<String, ?> params, BlockPos origin, boolean userExplicitHeight
    ) {
        Map<String, Object> raw = userExplicitHeight
                ? withUserExplicitHeight(params)
                : new HashMap<>(params);
        var component = new com.formacraft.common.llm.dto.Component(
                "STRUCTURE",
                null,
                new com.formacraft.common.llm.dto.Vec3i(0, 0, 0),
                new com.formacraft.common.llm.dto.Dimensions(9, 9, 40),
                java.util.List.of("typology:dense_eaves_pagoda"),
                raw
        );
        Map<String, Object> merged = TypologyParamResolver.merge("dense_eaves_pagoda", component);
        return DenseEavesPagodaBuilder.generate(merged, origin, null, null);
    }

    @Test
    void sevenStoreyDoubleEaveEmitsFourteenTiers() {
        var component = new com.formacraft.common.llm.dto.Component(
                "STRUCTURE",
                null,
                new com.formacraft.common.llm.dto.Vec3i(0, 0, 0),
                new com.formacraft.common.llm.dto.Dimensions(11, 11, 42),
                java.util.List.of("typology:dense_eaves_pagoda"),
                Map.of(
                        "floor_count", 7,
                        "eaves_per_level", 2,
                        "height", 42,
                        "baseWidth", 11,
                        "footprint", "octagon",
                        "detailLevel", "refined"
                )
        );
        Map<String, Object> params = TypologyParamResolver.merge("dense_eaves_pagoda", component);
        BlockPos origin = new BlockPos(0, 64, 0);
        GeneratedStructure structure = DenseEavesPagodaBuilder.generate(params, origin, null, null);
        var geo = PagodaMorphologyAudit.observe(structure.getBlocks(), origin);
        assertEquals(7, geo.measuredFloors());
        assertEquals(14, geo.measuredEaveTiers());
    }

    @Test
    void eaveStairsRadiateOutwardFromCenter() {
        var component = new com.formacraft.common.llm.dto.Component(
                "STRUCTURE",
                null,
                new com.formacraft.common.llm.dto.Vec3i(0, 0, 0),
                new com.formacraft.common.llm.dto.Dimensions(11, 11, 42),
                java.util.List.of("typology:dense_eaves_pagoda"),
                Map.of(
                        "floor_count", 7,
                        "eaves_per_level", 2,
                        "height", 46,
                        "baseWidth", 11,
                        "footprint", "octagon",
                        "detailLevel", "simple",
                        "eaveBlock", "minecraft:blackstone_stairs"
                )
        );
        Map<String, Object> params = TypologyParamResolver.merge("dense_eaves_pagoda", component);
        BlockPos origin = new BlockPos(0, 64, 0);
        GeneratedStructure structure = DenseEavesPagodaBuilder.generate(params, origin, null, null);
        int checked = 0;
        for (PlannedBlock b : structure.getBlocks()) {
            if (b.getTargetState() == null) continue;
            if (!(b.getTargetState().getBlock() instanceof net.minecraft.block.StairsBlock)) continue;
            String producer = b.getSource() != null ? b.getSource().producer() : "";
            if (producer == null || !producer.startsWith(PagodaMorphologyAudit.ROLE_EAVE)) continue;
            var facingProp = b.getTargetState().contains(net.minecraft.state.property.Properties.HORIZONTAL_FACING)
                    ? net.minecraft.state.property.Properties.HORIZONTAL_FACING
                    : (b.getTargetState().contains(net.minecraft.state.property.Properties.FACING)
                            ? net.minecraft.state.property.Properties.FACING : null);
            if (facingProp == null) continue;
            int dx = b.getPos().getX() - origin.getX();
            int dz = b.getPos().getZ() - origin.getZ();
            if (dx == 0 && dz == 0) continue;
            var expected = ChineseTypologyDetailUtil.outwardFromCenter(dx, dz);
            var actual = b.getTargetState().get(facingProp);
            assertEquals(expected, actual, "eave stair at relative (" + dx + "," + dz + ")");
            checked++;
        }
        assertTrue(checked >= 20, "expected many oriented eave stairs, got " + checked);
    }

    @Test
    void refinedDoorTunnelsThroughWallThickness() {
        var component = new com.formacraft.common.llm.dto.Component(
                "STRUCTURE",
                null,
                new com.formacraft.common.llm.dto.Vec3i(0, 0, 0),
                new com.formacraft.common.llm.dto.Dimensions(11, 11, 36),
                java.util.List.of("typology:dense_eaves_pagoda"),
                Map.of(
                        "floor_count", 3,
                        "eaves_per_level", 1,
                        "height", 36,
                        "baseWidth", 11,
                        "footprint", "octagon",
                        "detailLevel", "refined",
                        "facing", "SOUTH"
                )
        );
        Map<String, Object> params = TypologyParamResolver.merge("dense_eaves_pagoda", component);
        BlockPos origin = new BlockPos(0, 64, 0);
        GeneratedStructure structure = DenseEavesPagodaBuilder.generate(params, origin, null, null);
        int half = 5;
        BlockPos face = ChineseTypologyDetailUtil.octagonFaceCell(half, net.minecraft.util.math.Direction.SOUTH);
        // Behind the door (one cell inward) must not remain solid — wallThick=2 for refined.
        int behindZ = face.getZ() - 1;
        boolean blocked = structure.getBlocks().stream().anyMatch(b ->
                b.getPos().getX() == origin.getX() + face.getX()
                        && b.getPos().getZ() == origin.getZ() + behindZ
                        && b.getPos().getY() == origin.getY() + 2
                        && b.getTargetState() != null
                        && !b.getTargetState().isAir()
                        && !(b.getTargetState().getBlock() instanceof net.minecraft.block.DoorBlock));
        // Last-write wins in apply; presence of a later AIR at the same cell clears the solid.
        boolean cleared = structure.getBlocks().stream().anyMatch(b ->
                b.getPos().getX() == origin.getX() + face.getX()
                        && b.getPos().getZ() == origin.getZ() + behindZ
                        && b.getPos().getY() == origin.getY() + 2
                        && b.getTargetState() != null
                        && b.getTargetState().isAir());
        assertTrue(cleared || !blocked, "door must tunnel through wall; behind cell still solid");
    }

    @Test
    void threeStoreySealsTopDeckAndHasPlinthAndEaveSoffit() {
        var component = new com.formacraft.common.llm.dto.Component(
                "STRUCTURE",
                null,
                new com.formacraft.common.llm.dto.Vec3i(0, 0, 0),
                new com.formacraft.common.llm.dto.Dimensions(11, 11, 28),
                java.util.List.of("typology:dense_eaves_pagoda"),
                Map.of(
                        "floor_count", 3,
                        "eaves_per_level", 2,
                        "height", 28,
                        "baseWidth", 11,
                        "footprint", "octagon",
                        "detailLevel", "refined",
                        "plinth_height", 1
                )
        );
        Map<String, Object> params = TypologyParamResolver.merge("dense_eaves_pagoda", component);
        BlockPos origin = new BlockPos(0, 64, 0);
        GeneratedStructure structure = DenseEavesPagodaBuilder.generate(params, origin, null, null);

        // Plinth below origin.
        boolean hasPlinth = structure.getBlocks().stream().anyMatch(b ->
                b.getPos().getY() == origin.getY() - 1
                        && b.getTargetState() != null
                        && !b.getTargetState().isAir());
        assertTrue(hasPlinth, "expected plinth pad below origin");

        // Axis at the top of the last storey must be solid (sealed deck), not open to sky.
        var geo = PagodaMorphologyAudit.observe(structure.getBlocks(), origin);
        int topBodyY = structure.getBlocks().stream()
                .filter(b -> b.getTargetState() != null && !b.getTargetState().isAir())
                .filter(b -> b.getPos().getX() == origin.getX() && b.getPos().getZ() == origin.getZ())
                .filter(b -> {
                    String prod = b.getSource() == null ? "" : String.valueOf(b.getSource().producer());
                    return prod.startsWith(PagodaMorphologyAudit.ROLE_SHAFT)
                            || prod.startsWith(PagodaMorphologyAudit.ROLE_FINIAL);
                })
                .mapToInt(b -> b.getPos().getY())
                .max()
                .orElse(origin.getY());
        boolean sealedAxis = structure.getBlocks().stream().anyMatch(b ->
                b.getPos().getX() == origin.getX()
                        && b.getPos().getZ() == origin.getZ()
                        && b.getPos().getY() >= origin.getY() + 8
                        && b.getTargetState() != null
                        && !b.getTargetState().isAir());
        assertTrue(sealedAxis, "tower axis near top must be sealed; topBodyY=" + topBodyY);
        assertTrue(geo.finialCentered(), "compact Chinese finial must stay on axis");
    }

    @Test
    void smallOctagonPagodaIsHollowWithUsableClearHeight() {
        var component = new com.formacraft.common.llm.dto.Component(
                "STRUCTURE",
                null,
                new com.formacraft.common.llm.dto.Vec3i(0, 0, 0),
                new com.formacraft.common.llm.dto.Dimensions(7, 7, 32),
                java.util.List.of("typology:dense_eaves_pagoda"),
                Map.of(
                        "floor_count", 3,
                        "eaves_per_level", 1,
                        "height", 32,
                        "baseWidth", 7,
                        "footprint", "octagon",
                        "detailLevel", "simple"
                )
        );
        Map<String, Object> params = TypologyParamResolver.merge("dense_eaves_pagoda", component);
        BlockPos origin = new BlockPos(0, 64, 0);
        GeneratedStructure structure = DenseEavesPagodaBuilder.generate(params, origin, null, null);

        // Ground-storey cavity: axis cell above the floor plate must be empty (not solid fill).
        boolean axisAir = structure.getBlocks().stream().noneMatch(b ->
                b.getPos().getX() == origin.getX()
                        && b.getPos().getZ() == origin.getZ()
                        && b.getPos().getY() == origin.getY() + 2
                        && b.getTargetState() != null
                        && !b.getTargetState().isAir());
        assertTrue(axisAir, "pagoda shaft should be hollow at axis Y+2");

        // First storey body should reach at least min clear height before eaves.
        int solidMaxInLowerBand = structure.getBlocks().stream()
                .filter(b -> b.getTargetState() != null && !b.getTargetState().isAir())
                .filter(b -> {
                    int dy = b.getPos().getY() - origin.getY();
                    return dy >= 0 && dy < 4;
                })
                .mapToInt(b -> b.getPos().getY() - origin.getY())
                .max()
                .orElse(-1);
        assertTrue(solidMaxInLowerBand >= 2,
                "ground storey body should span ≥3 clear (max dy in lower band=" + solidMaxInLowerBand + ")");
    }
}
