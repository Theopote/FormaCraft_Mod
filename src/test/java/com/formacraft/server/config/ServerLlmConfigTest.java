package com.formacraft.server.config;

import com.formacraft.common.model.request.FormaRequest;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ServerLlmConfigTest {
    private FormaRequest clientRequest() {
        FormaRequest request = new FormaRequest();
        request.setApiKey("client-secret");
        request.setLlmBaseUrl("https://attacker.invalid/v1");
        request.setLlmProvider("client-provider");
        request.setModel("client-model");
        return request;
    }
    @Test void dedicatedServerReplacesClientCredentialsAndDestination() {
        FormaRequest request = clientRequest();
        ServerLlmConfig.apply(request, true);
        assertNotEquals("client-secret", request.getApiKey());
        assertNotEquals("https://attacker.invalid/v1", request.getLlmBaseUrl());
        assertNotEquals("client-provider", request.getLlmProvider());
        assertNotEquals("client-model", request.getModel());
    }
    @Test void integratedServerPreservesLocalSettings() {
        FormaRequest request = clientRequest();
        ServerLlmConfig.apply(request, false);
        assertEquals("client-secret", request.getApiKey());
        assertEquals("https://attacker.invalid/v1", request.getLlmBaseUrl());
    }
}
