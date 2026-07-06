package com.formacraft.common.typology;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TypologyInterpreterRegistryTest {

    @Test
    void bootstrapsFamenAndFoguangInterpreters() {
        assertTrue(TypologyInterpreterRegistry.has("dense_eaves_pagoda"));
        assertTrue(TypologyInterpreterRegistry.has("tailiang_timber_hall"));

        TypologyInterpreter famen = TypologyInterpreterRegistry.get("dense_eaves_pagoda");
        assertNotNull(famen);
        assertEquals("dense_eaves_pagoda", famen.typologyId());
        assertInstanceOf(com.formacraft.server.generation.typology.interpreter.DenseEavesPagodaInterpreter.class, famen);
    @Test
    void tailiangInterpreterIsNative() {
        TypologyInterpreter hall = TypologyInterpreterRegistry.get("tailiang_timber_hall");
        assertNotNull(hall);
        assertInstanceOf(com.formacraft.server.generation.typology.interpreter.TailiangTimberHallInterpreter.class, hall);
    }
}
