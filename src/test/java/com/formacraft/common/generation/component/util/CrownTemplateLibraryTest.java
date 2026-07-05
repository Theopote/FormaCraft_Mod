package com.formacraft.common.generation.component.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CrownTemplateLibraryTest {

    @Test
    void normalizeMapsAliases() {
        assertEquals(CrownTemplateLibrary.ONION_DOME, CrownTemplateLibrary.normalize("baroque_onion"));
        assertEquals(CrownTemplateLibrary.CLASSICAL_CUPOLA, CrownTemplateLibrary.normalize("monument_cupola"));
        assertEquals(CrownTemplateLibrary.SIMPLE_DOME, CrownTemplateLibrary.normalize("hemisphere"));
    }
}
