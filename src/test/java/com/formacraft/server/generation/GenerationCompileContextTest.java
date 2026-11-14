package com.formacraft.server.generation;

import com.formacraft.common.generation.routing.GenerationAuthority;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class GenerationCompileContextTest {
    @Test
    void metadataSnapshotKeepsNullValuesAndIgnoresLaterSourceEdits() {
        var source = new HashMap<String, Object>();
        source.put("allow_structure_empty_fallback", false);
        source.put("optional", null);
        var context = new GenerationCompileContext(GenerationAuthority.COMPONENTS, source, false);
        source.put("allow_structure_empty_fallback", true);
        source.clear();

        assertEquals(false, context.planMeta().get("allow_structure_empty_fallback"));
        assertTrue(context.planMeta().containsKey("optional"));
        assertNull(context.planMeta().get("optional"));
        assertThrows(UnsupportedOperationException.class, () -> context.planMeta().put("new", true));
    }

    @Test
    void nestedFailureAndNullScopeRestoreEnclosingContext() {
        var before = GenerationCompileContext.current();
        var outer = new GenerationCompileContext(GenerationAuthority.COMPONENTS, Map.of(), false);
        var inner = new GenerationCompileContext(GenerationAuthority.TYPOLOGY, Map.of(), true);
        GenerationCompileContext.runWith(outer, () -> {
            assertThrows(IllegalStateException.class, () -> GenerationCompileContext.runWith(inner, () -> {
                assertSame(inner, GenerationCompileContext.current());
                throw new IllegalStateException("abort");
            }));
            assertSame(outer, GenerationCompileContext.current());
            GenerationCompileContext.runWith(null, () -> assertNull(GenerationCompileContext.current()));
            assertSame(outer, GenerationCompileContext.current());
        });
        assertSame(before, GenerationCompileContext.current());
    }
}
