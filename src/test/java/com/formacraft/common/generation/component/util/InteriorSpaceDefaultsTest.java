package com.formacraft.common.generation.component.util;

import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.dto.Dimensions;
import com.formacraft.common.llm.dto.Vec3i;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class InteriorSpaceDefaultsTest {

    @Test
    void defaultClearHeightGroundTallerThanUpper() {
        assertEquals(4, InteriorSpaceDefaults.defaultClearHeight(0, 3));
        assertEquals(3, InteriorSpaceDefaults.defaultClearHeight(1, 3));
        assertEquals(3, InteriorSpaceDefaults.defaultClearHeight(0, 1));
    }

    @Test
    void storeyHeightNeverBelowMinClear() {
        assertEquals(3, InteriorSpaceDefaults.defaultStoreyHeight(5));
        assertEquals(4, InteriorSpaceDefaults.defaultStoreyHeight(12));
    }

    @Test
    void hollowDefaultRespectsSolidAndSize() {
        assertTrue(InteriorSpaceDefaults.shouldHollowByDefault(false, false, 7, 7, 6));
        assertFalse(InteriorSpaceDefaults.shouldHollowByDefault(true, false, 7, 7, 6));
        assertFalse(InteriorSpaceDefaults.shouldHollowByDefault(false, true, 7, 7, 6));
        assertFalse(InteriorSpaceDefaults.shouldHollowByDefault(false, false, 3, 7, 6));
    }

    @Test
    void resolvedFloorLayoutClampsImplicitHeightToMinClear() {
        Component mass = new Component(
                "MASS_MAIN", "s0", new Vec3i(0, 0, 0),
                new Dimensions(9, 9, 8), List.of(),
                Map.of("floor_count", 3));
        ResolvedFloorLayout layout = ResolvedFloorLayout.from(mass, 8);
        assertTrue(layout.floorHeight() >= InteriorSpaceDefaults.MIN_CLEAR_HEIGHT);
    }
}
