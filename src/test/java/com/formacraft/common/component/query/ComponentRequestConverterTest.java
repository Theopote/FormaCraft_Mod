package com.formacraft.common.component.query;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class ComponentRequestConverterTest {

    @Test
    void buildsQueryFromLegacyCategoryAndTags() {
        var query = ComponentRequestConverter.fromLegacyMap(Map.of(
                "category", "RAILING",
                "tags", Set.of("chinese", "balcony"),
                "approx_size", Map.of("w", 8, "h", 2, "d", 1)
        ), "");
        assertNotNull(query);
        assertEquals("railing", query.semantic.role);
        assertEquals(8, query.geometry.openingWidth);
    }
}
