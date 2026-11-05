package com.formacraft.server.generation.typology.builder;

import com.formacraft.common.build.GeneratedStructure;
import com.formacraft.common.typology.TypologyGenerationException;
import com.formacraft.common.typology.TypologyParamResolver;
import com.formacraft.common.typology.TypologyParams;
import com.formacraft.test.MinecraftRegistryTestBootstrap;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PagodaMorphologyRoleAuditTest {

    @BeforeAll
    static void boot() {
        MinecraftRegistryTestBootstrap.initialize();
    }

    @Test
    void slabAndFullBlockEavesBothPassSameGeometry() {
        PagodaMorphologyAudit.Result slab = auditWithEave("minecraft:blackstone_slab");
        PagodaMorphologyAudit.Result tile = auditWithEave("minecraft:deepslate_tiles");
        assertTrue(slab.passed(), String.valueOf(slab.diagnostics()));
        assertTrue(tile.passed(), String.valueOf(tile.diagnostics()));
        assertEquals(slab.actualFloors(), tile.actualFloors());
        assertEquals(slab.actualEaveTiers(), tile.actualEaveTiers());
    }

    @Test
    void threeStoreyDoubleEaveMissingTiersFails() {
        // Generate single-eave geometry, then audit against double-eave expectation.
        GeneratedStructure structure = generate(Map.of(
                "floor_count", 3,
                "eaves_per_level", 1,
                "height", 36,
                "baseWidth", 9,
                "footprint", "octagon",
                "detailLevel", "simple",
                "eaveBlock", "minecraft:blackstone_slab"
        ));
        PagodaVerticalSemantics wantDouble = new PagodaVerticalSemantics(3, 2, 6, true);
        TypologyGenerationException ex = assertThrows(
                TypologyGenerationException.class,
                () -> PagodaMorphologyAudit.checkFromBlocks(
                        "dense_eaves_pagoda",
                        wantDouble,
                        structure.getBlocks(),
                        new BlockPos(0, 64, 0),
                        true));
        assertEquals("E_PAGODA_MORPHOLOGY", ex.code());
        assertTrue(ex.getMessage().contains("eave_tiers"), ex.getMessage());
    }

    @Test
    void threeStoreyDoubleEaveExactRolesPass() {
        PagodaMorphologyAudit.Result r = auditWithEave("minecraft:deepslate_tiles");
        assertEquals(3, r.actualFloors());
        assertEquals(6, r.actualEaveTiers());
        assertTrue(r.passed(), String.valueOf(r.diagnostics()));
    }

    private static PagodaMorphologyAudit.Result auditWithEave(String eaveBlock) {
        GeneratedStructure structure = generate(Map.of(
                "floor_count", 3,
                "eaves_per_level", 2,
                "height", 40,
                "baseWidth", 9,
                "footprint", "octagon",
                "detailLevel", "simple",
                "eaveBlock", eaveBlock
        ));
        BlockPos origin = new BlockPos(0, 64, 0);
        PagodaVerticalSemantics vertical = PagodaVerticalSemantics.resolve(
                new TypologyParams(Map.of("floor_count", 3, "eaves_per_level", 2)), 13, 7, 15);
        return PagodaMorphologyAudit.checkFromBlocks(
                "dense_eaves_pagoda", vertical, structure.getBlocks(), origin, true);
    }

    private static GeneratedStructure generate(Map<String, ?> params) {
        var component = new com.formacraft.common.llm.dto.Component(
                "STRUCTURE",
                null,
                new com.formacraft.common.llm.dto.Vec3i(0, 0, 0),
                new com.formacraft.common.llm.dto.Dimensions(9, 9, 40),
                java.util.List.of("typology:dense_eaves_pagoda"),
                new HashMap<>(params)
        );
        Map<String, Object> merged = TypologyParamResolver.merge("dense_eaves_pagoda", component);
        return DenseEavesPagodaBuilder.generate(merged, new BlockPos(0, 64, 0), null, null);
    }
}
