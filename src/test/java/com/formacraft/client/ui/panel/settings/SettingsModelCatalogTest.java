package com.formacraft.client.ui.panel.settings;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SettingsModelCatalogTest {
    @Test
    void acceptsCustomEndpointsIncludingIntranetAndIpv6() {
        for (String url : new String[]{"http://llm:8000/v1", "http://[::1]:1234/v1",
                "http://localhost:11434/v1", "https://gateway.example/api/openai/v1"}) {
            assertEquals(url, SettingsModelCatalog.sanitizeLlmBaseUrlOrNull(url));
        }
    }

    @Test
    void normalizesWhitespaceAndTrailingSlashesWithoutChangingTheApiPath() {
        assertEquals("https://gateway.example/custom/v2",
                SettingsModelCatalog.sanitizeLlmBaseUrlOrNull(" gateway.example/custom/v2/// "));
        assertEquals("HTTPS://gateway.example/v1", SettingsModelCatalog.sanitizeLlmBaseUrlOrNull("HTTPS://gateway.example/v1/"));
        assertEquals("", SettingsModelCatalog.sanitizeLlmBaseUrlOrNull("  "));
    }

    @Test
    void rejectsMalformedOrUnsupportedEndpoints() {
        for (String url : new String[]{"ftp://gateway.example/v1", "https://ps://bad",
                "http://", "https://bad host/v1", "https://gateway.example:70000/v1",
                "https://user:password@gateway.example/v1", "https://gateway.example/v1?token=x",
                "https://gateway.example/v1#section"}) {
            assertNull(SettingsModelCatalog.sanitizeLlmBaseUrlOrNull(url), url);
        }
    }
}
