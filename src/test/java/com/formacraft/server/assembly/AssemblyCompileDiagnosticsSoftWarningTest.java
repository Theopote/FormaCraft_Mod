package com.formacraft.server.assembly;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AssemblyCompileDiagnosticsSoftWarningTest {
    @AfterEach
    void clear() {
        AssemblyCompileDiagnostics.clear();
    }

    @Test
    void softWarningsAccumulateWithoutGap() {
        AssemblyCompileDiagnostics.addSoftWarning(
                "DECOR_COMPONENT_SKIPPED", "装饰缺失", "DECOR_DETAIL", "d1");
        assertFalse(AssemblyCompileDiagnostics.hasGap());
        assertTrue(AssemblyCompileDiagnostics.hasSoftWarnings());
        assertEquals(1, AssemblyCompileDiagnostics.softWarnings().size());
        assertEquals("DECOR_COMPONENT_SKIPPED", AssemblyCompileDiagnostics.softWarnings().get(0).code());
        assertEquals("d1", AssemblyCompileDiagnostics.softWarnings().get(0).componentId());
    }
}
