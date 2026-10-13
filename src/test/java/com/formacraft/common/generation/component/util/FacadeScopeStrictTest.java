package com.formacraft.common.generation.component.util;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class FacadeScopeStrictTest {
    @Test
    void absentFacesIsFullMass() {
        assertTrue(FacadeScope.parseStrict(Map.of("component_id", "w"), null).isFullMass());
    }

    @Test
    void explicitEmptyListFails() {
        var ex = assertThrows(IllegalArgumentException.class,
                () -> FacadeScope.parseStrict(Map.of("faces", List.of()), null));
        assertTrue(ex.getMessage().startsWith("E_FACADE_SCOPE_INVALID"));
    }

    @Test
    void typoFaceFails() {
        var ex = assertThrows(IllegalArgumentException.class,
                () -> FacadeScope.parseStrict(Map.of("faces", List.of("NORT")), null));
        assertTrue(ex.getMessage().contains("NORT"));
    }

    @Test
    void minGreaterThanMaxFails() {
        var ex = assertThrows(IllegalArgumentException.class,
                () -> FacadeScope.parseStrict(Map.of(
                        "faces", "SOUTH",
                        "min_floor", 2,
                        "max_floor", 1), null));
        assertTrue(ex.getMessage().startsWith("E_FACADE_SCOPE_INVALID"));
    }

    @Test
    void floorBeyondHostFails() {
        var ex = assertThrows(IllegalArgumentException.class,
                () -> FacadeScope.parseStrict(Map.of(
                        "faces", "SOUTH",
                        "min_floor", 0,
                        "max_floor", 5), 3));
        assertTrue(ex.getMessage().contains("floor_count"));
    }

}
