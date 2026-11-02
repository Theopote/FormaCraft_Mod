package com.formacraft.client.component;

import com.formacraft.common.component.*;
import com.formacraft.common.json.JsonUtil;
import org.junit.jupiter.api.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ClientComponentCatalogStateTest {
    @BeforeEach void setup() { ClientComponentCatalogState.beginSession(false); }
    @AfterEach void restore() { ClientComponentCatalogState.beginSession(false); }
    private String catalog() {
        var entry = new ComponentCatalog.Entry(); entry.id = "asset_remote"; entry.name = "Remote window"; entry.packageRef = "publisher/window@1#sha256:hash";
        var catalog = new ComponentCatalog(); catalog.components = new ArrayList<>(List.of(entry)); return JsonUtil.toJson(catalog);
    }
    @Test void serverCatalogIsAuthoritativeWithoutClientFilesAndEmptyCatalogStaysEmpty() {
        ClientComponentCatalogState.setFromJson(catalog()); assertEquals(1,ClientComponentCatalogState.getCatalog().components.size());
        assertTrue(ClientComponentCatalogState.hasRegisteredComponents()); assertTrue(ClientComponentCatalogState.isSyncComplete());
        ClientComponentCatalogState.setFromJson("{\"schema\":\"formacraft.component_catalog.v1\",\"components\":[]}");
        assertFalse(ClientComponentCatalogState.hasRegisteredComponents()); assertTrue(ClientComponentCatalogState.getCatalog().components.isEmpty());
        assertFalse(ClientComponentCatalogState.hydrateFromDiskIfEmpty());
    }
    @Test void malformedRefreshPreservesLastGoodCatalogAndConnectionChangeClearsIt() {
        ClientComponentCatalogState.setFromJson(catalog()); var previous = ClientComponentCatalogState.getCatalog();
        ClientComponentCatalogState.markSyncRequested(); ClientComponentCatalogState.setFromJson("broken");
        assertSame(previous,ClientComponentCatalogState.getCatalog()); assertFalse(ClientComponentCatalogState.isSyncComplete());
        ClientComponentCatalogState.beginSession(false); assertNull(ClientComponentCatalogState.getCatalog());
        assertFalse(ClientComponentCatalogState.hasRegisteredComponents()); assertFalse(ClientComponentCatalogState.isSyncPending());
    }
}
