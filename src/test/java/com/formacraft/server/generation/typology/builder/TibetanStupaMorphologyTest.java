package com.formacraft.server.generation.typology.builder;

import com.formacraft.common.typology.TypologyGenerationException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TibetanStupaMorphologyTest {

    @Test
    void typologyIdIsDistinctFromDenseEaves() {
        assertEquals("tibetan_stupa", TibetanStupaBuilder.TYPOLOGY_ID);
        assertNotEquals(DenseEavesPagodaBuilder.TYPOLOGY_ID, TibetanStupaBuilder.TYPOLOGY_ID);
    }

    @Test
    void morphologyExceptionCodeIsStupaNotPagoda() {
        TypologyGenerationException ex = new TypologyGenerationException(
                TibetanStupaBuilder.TYPOLOGY_ID,
                "E_STUPA_MORPHOLOGY",
                "missing anda",
                java.util.List.of("hasAnda=false"));
        assertEquals("E_STUPA_MORPHOLOGY", ex.code());
        assertEquals("tibetan_stupa", ex.typologyId());
    }
}
