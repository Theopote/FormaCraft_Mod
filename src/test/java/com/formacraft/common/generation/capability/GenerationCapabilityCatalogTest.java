package com.formacraft.common.generation.capability;

import com.formacraft.common.generation.component.util.RoofTypeKind;
import com.formacraft.common.generation.component.util.RoofTypeCatalog;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class GenerationCapabilityCatalogTest {

    @Test
    void buildExposesGateHostAndIrimoyaAlias() {
        Map<String, Object> catalog = GenerationCapabilityCatalog.build();
        assertEquals(GenerationCapabilityCatalog.SCHEMA, catalog.get("schema"));

        @SuppressWarnings("unchecked")
        Map<String, Object> components = (Map<String, Object>) catalog.get("component_types");
        assertNotNull(components.get("GATE"));
        @SuppressWarnings("unchecked")
        Map<String, Object> gate = (Map<String, Object>) components.get("GATE");
        assertEquals(true, gate.get("can_host"));
        assertEquals("supported", gate.get("implementation_status"));

        assertTrue(GenerationCapabilityCatalog.isHostable("GATE"));
        assertTrue(GenerationCapabilityCatalog.isHosted("ROOF"));
        assertEquals("xieshan", GenerationCapabilityCatalog.normalizeRoofType("irimoya"));
        assertTrue(GenerationCapabilityCatalog.isExecutableRoofType("irimoya"));
        assertEquals(RoofTypeKind.XIESHAN, RoofTypeCatalog.parseExplicit("irimoya").orElseThrow());
    }

    @Test
    void resourceCatalogAgreesOnCoreContract() throws Exception {
        String json;
        try (InputStream in = GenerationCapabilityCatalogTest.class.getResourceAsStream(
                "/assets/formacraft/generation_capability_catalog.json")) {
            assertNotNull(in, "checked-in capability catalog missing");
            json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        assertTrue(json.contains("formacraft.generation_capability.v1"));
        assertTrue(json.contains("\"GATE\""));
        assertTrue(json.contains("irimoya"));
        assertTrue(json.contains("xieshan"));
        assertTrue(json.contains("dense_eaves_pagoda"));
        assertTrue(json.contains("tibetan_stupa"));

        @SuppressWarnings("unchecked")
        List<String> hostable = (List<String>) GenerationCapabilityCatalog.build().get("hostable_component_types");
        assertTrue(hostable.contains("GATE"));
        assertTrue(hostable.contains("MASS_MAIN"));

        @SuppressWarnings("unchecked")
        Map<String, Object> classes = (Map<String, Object>) GenerationCapabilityCatalog.build().get("building_classes");
        @SuppressWarnings("unchecked")
        Map<String, Object> tower = (Map<String, Object>) classes.get("tower_like");
        @SuppressWarnings("unchecked")
        List<String> preferred = (List<String>) tower.get("preferred_typologies");
        assertTrue(preferred.contains("dense_eaves_pagoda"));
        assertTrue(preferred.contains("tibetan_stupa"));
    }

    @Test
    void templeBuildingClassDefaultsToSingleComposition() {
        @SuppressWarnings("unchecked")
        Map<String, Object> classes = (Map<String, Object>) GenerationCapabilityCatalog.build().get("building_classes");
        @SuppressWarnings("unchecked")
        Map<String, Object> temple = (Map<String, Object>) classes.get("temple");
        assertEquals("single", temple.get("composition_default"));
        assertTrue(((List<?>) temple.get("composition_compound_triggers")).contains("建筑群"));
    }
}
