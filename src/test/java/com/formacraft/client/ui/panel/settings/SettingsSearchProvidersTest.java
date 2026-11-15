package com.formacraft.client.ui.panel.settings;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SettingsSearchProvidersTest {
    @Test void freeProvidersDoNotShowUnrelatedCredentialFields() {
        for(String provider:new String[]{"duckduckgo","wikipedia_only"}) {
            assertFalse(SettingsSearchProviders.showsCredentials(provider));
            assertFalse(SettingsSearchProviders.showsGoogleCx(provider));
        }
    }
    @Test void apiProvidersShowKeysAndOnlyGoogleShowsCx() {
        for(String provider:new String[]{"bing","tavily","serpapi","google_cse"}) {
            assertTrue(SettingsSearchProviders.showsCredentials(provider));
            assertEquals(provider.equals("google_cse"),SettingsSearchProviders.showsGoogleCx(provider));
        }
    }
    @Test void automaticModeAllowsBothOptionalEnvironmentOverrides() {
        assertTrue(SettingsSearchProviders.showsCredentials("auto"));
        assertTrue(SettingsSearchProviders.showsGoogleCx("auto"));
    }
}
