package com.formacraft.common.alignment;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BayGridResolverTest {

    @Test
    void expandsSideBays() {
        BayRhythm rhythm = new BayRhythm(
                List.of(
                        new BaySpec(3, "wing"),
                        new BaySpec(5, "center"),
                        new BaySpec(3, "wing")
                ),
                null,
                null
        );

        BayGridResolver.ResolvedAxisGrid grid = BayGridResolver.resolve(rhythm);
        assertEquals(11, grid.totalSpan());
        assertEquals(3, grid.bays().size());
        assertEquals(0, grid.bays().get(0).start());
        assertEquals(3, grid.bays().get(1).start());
        assertEquals(8, grid.bays().get(2).start());
        assertTrue(BayGridResolver.isSymmetric(grid.bays()));
    }

    @Test
    void expandsUniformBayCount() {
        BayRhythm rhythm = new BayRhythm(null, 4, 5);

        BayGridResolver.ResolvedAxisGrid grid = BayGridResolver.resolve(rhythm);
        assertEquals(20, grid.totalSpan());
        assertEquals(4, grid.bays().size());
        assertEquals("regular", grid.bays().get(0).role());
    }

    @Test
    void detectsAsymmetricSideBays() {
        List<BayGridResolver.BaySpan> bays = List.of(
                new BayGridResolver.BaySpan(0, 3, "wing"),
                new BayGridResolver.BaySpan(3, 5, "center"),
                new BayGridResolver.BaySpan(8, 4, "wing")
        );
        assertFalse(BayGridResolver.isSymmetric(bays));
    }
}
