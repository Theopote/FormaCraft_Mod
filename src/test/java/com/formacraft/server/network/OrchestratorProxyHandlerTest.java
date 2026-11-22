package com.formacraft.server.network;

import com.formacraft.server.config.ServerLlmConfig;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class OrchestratorProxyHandlerTest {

    @Test
    void unsupportedOpReturns400() {
        var result = OrchestratorProxyHandler.execute("nope", "");
        assertEquals(400, result.status());
        assertTrue(result.body().contains("unsupported"));
    }

    @Test
    void dedicatedSummarizeStripsClientLlmOverrides() {
        String raw = "{\"transcript\":\"hello\",\"apiKey\":\"client-key\","
                + "\"llmBaseUrl\":\"https://evil.example/v1\","
                + "\"llmProvider\":\"openai\",\"model\":\"gpt-x\"}";
        String rewritten = ServerLlmConfig.rewriteProxySummarizeJson(raw, true);
        JsonObject obj = JsonParser.parseString(rewritten).getAsJsonObject();
        assertEquals("hello", obj.get("transcript").getAsString());
        assertNotEquals("client-key",
                obj.has("apiKey") && !obj.get("apiKey").isJsonNull()
                        ? obj.get("apiKey").getAsString() : null);
        assertFalse(obj.has("llmBaseUrl")
                && !obj.get("llmBaseUrl").isJsonNull()
                && obj.get("llmBaseUrl").getAsString().contains("evil.example"));
    }

    @Test
    void integratedSummarizeKeepsClientOverrides() {
        String raw = "{\"transcript\":\"hello\",\"apiKey\":\"client-key\","
                + "\"llmBaseUrl\":\"https://local.example/v1\"}";
        String rewritten = ServerLlmConfig.rewriteProxySummarizeJson(raw, false);
        JsonObject obj = JsonParser.parseString(rewritten).getAsJsonObject();
        assertEquals("client-key", obj.get("apiKey").getAsString());
        assertEquals("https://local.example/v1", obj.get("llmBaseUrl").getAsString());
    }
}
