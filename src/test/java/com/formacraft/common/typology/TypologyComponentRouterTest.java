package com.formacraft.common.typology;

import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.dto.Dimensions;
import com.formacraft.common.llm.dto.Vec3i;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class TypologyComponentRouterTest {

    @Test
    void extractsTypologyFromFeature() {
        Component c = new Component(
                "STRUCTURE",
                null,
                new Vec3i(0, 0, 0),
                new Dimensions(10, 10, 47),
                List.of("typology:dense_eaves_pagoda"),
                Map.of("levels", 13)
        );
        assertEquals("dense_eaves_pagoda", TypologyComponentRouter.extractTypologyId(c));
        assertTrue(TypologyComponentRouter.hasTypologyHint(c));
    }

    @Test
    void extractsTypologyFromParams() {
        Component c = new Component(
                "STRUCTURE",
                null,
                new Vec3i(0, 0, 0),
                new Dimensions(21, 15, 16),
                List.of(),
                Map.of(
                        "typology_id", "tailiang_timber_hall",
                        "reference_landmark", "foguang_temple_hall",
                        "baysX", 7
                )
        );
        assertEquals("tailiang_timber_hall", TypologyComponentRouter.extractTypologyId(c));
        assertEquals("foguang_temple_hall", TypologyComponentRouter.extractReferenceLandmark(c));
    }

    @Test
    void mapsLegacyLandmarkToTypology() {
        assertEquals("dense_eaves_pagoda", TypologyComponentRouter.typologyForLegacyLandmark("famen_pagoda"));
        assertEquals("tailiang_timber_hall", TypologyComponentRouter.typologyForLegacyLandmark("foguang_temple_hall"));
    }
}
