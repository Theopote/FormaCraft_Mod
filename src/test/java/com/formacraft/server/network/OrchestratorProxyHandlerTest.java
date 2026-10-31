package com.formacraft.server.network;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class OrchestratorProxyHandlerTest {

    @Test
    void unsupportedOpReturns400() {
        var result = OrchestratorProxyHandler.execute("nope", "");
        assertEquals(400, result.status());
        assertTrue(result.body().contains("unsupported"));
    }
}
