package com.formacraft.server.generation.typology.builder;

import com.formacraft.common.typology.TypologyGenerationException;
import com.formacraft.common.typology.TypologyParamResolver;
import com.formacraft.common.typology.TypologyParams;
import com.formacraft.test.MinecraftRegistryTestBootstrap;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PagodaVerticalSemanticsTest {

    @BeforeAll
    static void boot() {
        MinecraftRegistryTestBootstrap.initialize();
    }

    @Test
    void threeStoreyDoubleEavesKeepsThreeFloorsAndSixEaveTiers() {
        TypologyParams p = new TypologyParams(Map.of(
                "floor_count", 3,
                "eaves_per_level", 2
        ));
        PagodaVerticalSemantics v = PagodaVerticalSemantics.resolve(p, 13, 7, 15);
        assertEquals(3, v.floorCount());
        assertEquals(2, v.eavesPerLevel());
        assertEquals(6, v.eaveTiers());
        assertTrue(v.floorsExplicit());
    }

    @Test
    void doubleEavesCueDoesNotInflateFloorCount() {
        TypologyParams p = new TypologyParams(Map.of(
                "levels", 3,
                "double_eaves", true
        ));
        PagodaVerticalSemantics v = PagodaVerticalSemantics.resolve(p, 13, 7, 15);
        assertEquals(3, v.floorCount());
        assertEquals(2, v.eavesPerLevel());
        assertEquals(6, v.eaveTiers());
    }

    @Test
    void doubleEavesBeatsRegistryDefaultSingleEavePerLevel() {
        // Typology registry defaults often inject eaves_per_level=1 alongside plan double_eaves.
        TypologyParams p = new TypologyParams(Map.of(
                "floor_count", 3,
                "eaves_per_level", 1,
                "double_eaves", true
        ));
        PagodaVerticalSemantics v = PagodaVerticalSemantics.resolve(p, 13, 7, 15);
        assertEquals(3, v.floorCount());
        assertEquals(2, v.eavesPerLevel());
        assertEquals(6, v.eaveTiers());
    }

    @Test
    void eaveTiersAloneDoesNotBecomeSevenPlusDefault() {
        TypologyParams p = new TypologyParams(Map.of("eave_tiers", 6));
        PagodaVerticalSemantics v = PagodaVerticalSemantics.resolve(p, 13, 7, 15);
        // Authored eave_tiers alone ≈ one eave per storey → 6 floors, not implicit 13.
        assertEquals(6, v.floorCount());
        assertEquals(1, v.eavesPerLevel());
        assertEquals(6, v.eaveTiers());
    }

    @Test
    void explicitTwoFloorsIsNotSilentlyBumpedToThree() {
        TypologyParams p = new TypologyParams(Map.of("floor_count", 2));
        PagodaVerticalSemantics v = PagodaVerticalSemantics.resolve(p, 13, 7, 15);
        assertEquals(2, v.floorCount());
        assertTrue(v.floorsExplicit());
    }

    @Test
    void morphologyAuditFailsWrongFloorCount() {
        PagodaVerticalSemantics expected = new PagodaVerticalSemantics(3, 2, 6, true);
        TypologyGenerationException ex = assertThrows(
                TypologyGenerationException.class,
                () -> PagodaMorphologyAudit.check(
                        "dense_eaves_pagoda", expected, 7, 7, true, true, true));
        assertEquals("E_PAGODA_MORPHOLOGY", ex.code());
        assertTrue(ex.getMessage().contains("floor_count"));
    }

    @Test
    void morphologyAuditPassesMatchingVertical() {
        PagodaVerticalSemantics expected = new PagodaVerticalSemantics(3, 2, 6, true);
        PagodaMorphologyAudit.Result r = PagodaMorphologyAudit.check(
                "dense_eaves_pagoda", expected, 3, 6, true, true, true);
        assertTrue(r.passed());
    }

    @Test
    void morphologyAuditFromBlocksObservesSetbackAndFinial() {
        var component = new com.formacraft.common.llm.dto.Component(
                "STRUCTURE",
                null,
                new com.formacraft.common.llm.dto.Vec3i(0, 0, 0),
                new com.formacraft.common.llm.dto.Dimensions(9, 9, 36),
                java.util.List.of("typology:dense_eaves_pagoda"),
                Map.of(
                        "floor_count", 3,
                        "eaves_per_level", 1,
                        "height", 36,
                        "baseWidth", 9,
                        "footprint", "octagon",
                        "detailLevel", "simple"
                )
        );
        Map<String, Object> params = TypologyParamResolver.merge("dense_eaves_pagoda", component);
        BlockPos origin = new BlockPos(0, 64, 0);
        var structure = DenseEavesPagodaBuilder.generate(params, origin, null, null);
        PagodaVerticalSemantics vertical = PagodaVerticalSemantics.resolve(
                new TypologyParams(params), 13, 7, 15);
        PagodaMorphologyAudit.GeometryObservation geo =
                PagodaMorphologyAudit.observe(structure.getBlocks(), origin);
        assertTrue(geo.measuredEaveTiers() >= 2, "eave tiers=" + geo.measuredEaveTiers());
        assertTrue(geo.setbackObserved(), "setback missing");
        assertTrue(geo.finialCentered(), "finial off-axis");
        PagodaMorphologyAudit.Result r = PagodaMorphologyAudit.checkFromBlocks(
                "dense_eaves_pagoda", vertical, structure.getBlocks(), origin, true);
        assertTrue(r.passed(), String.valueOf(r.diagnostics()));
    }
}
