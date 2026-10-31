package com.formacraft.common.network;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class OrchestratorEndpointAccessTest {

    @Test
    void localhostEndpointsAreDirectlyCallable() {
        assertTrue(OrchestratorEndpointAccess.isLocalhost("http://localhost:8000"));
        assertTrue(OrchestratorEndpointAccess.isLocalhost("http://127.0.0.1:8000/models"));
        assertTrue(OrchestratorEndpointAccess.clientMayCallDirectly("http://localhost:8000"));
    }

    @Test
    void remoteWithoutSecretIsBlocked() {
        assertFalse(OrchestratorEndpointAccess.isLocalhost("https://orch.example.com"));
        // Process env may or may not set the secret; when absent, remote must be blocked.
        if (OrchestratorSecretHeaders.fromEnvironment() == null) {
            assertFalse(OrchestratorEndpointAccess.clientMayCallDirectly("https://orch.example.com"));
        }
    }
}
