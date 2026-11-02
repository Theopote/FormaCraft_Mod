package com.formacraft.server.generation.typology.builder;

import com.formacraft.common.typology.TypologyParams;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DenseEavesPagodaLevelsTest {

    @Test
    void explicitThreeLevelsNotClampedToSeven() {
        TypologyParams p = new TypologyParams(Map.of("levels", 3));
        assertEquals(3, DenseEavesPagodaBuilder.resolveLevels(p, 13, 7, 15));
    }

    @Test
    void floorCountThreeHonored() {
        TypologyParams p = new TypologyParams(Map.of("floor_count", 3));
        assertEquals(3, DenseEavesPagodaBuilder.resolveLevels(p, 13, 7, 15));
    }

    @Test
    void implicitDefaultKeepsTallMin() {
        TypologyParams p = new TypologyParams(Map.of());
        assertEquals(13, DenseEavesPagodaBuilder.resolveLevels(p, 13, 7, 15));
    }
}
