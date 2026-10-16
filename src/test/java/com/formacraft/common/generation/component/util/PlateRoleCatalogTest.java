package com.formacraft.common.generation.component.util;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlateRoleCatalogTest {

    @Test
    void unsupportedExplicitRejected() {
        assertFalse(PlateRoleCatalog.isSupportedExplicit("skybridge"));
        assertTrue(PlateRoleCatalog.parseSupported("skybridge").isEmpty());
    }

    @Test
    void knownAliasesMap() {
        assertEquals(HostedPlateOccupancy.PlateRole.TERRACE,
                PlateRoleCatalog.parseSupported("cantilever").orElseThrow());
        assertEquals(HostedPlateOccupancy.PlateRole.BRIDGE,
                PlateRoleCatalog.parseSupported("link").orElseThrow());
        assertEquals(HostedPlateOccupancy.PlateRole.INTERIOR,
                PlateRoleCatalog.parseSupported("interior").orElseThrow());
    }

    @Test
    void resolveRoleThrowsOnUnknownExplicit() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> HostedPlateOccupancy.resolveRole(Map.of("plate_role", "skybridge")));
        assertTrue(ex.getMessage().startsWith("E_PLATE_ROLE_UNSUPPORTED"));
    }

    @Test
    void omittedRoleDefaultsInterior() {
        assertEquals(HostedPlateOccupancy.PlateRole.INTERIOR,
                HostedPlateOccupancy.resolveRole(Map.of()));
    }
}
