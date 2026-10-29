package com.formacraft.common.component.variant;

import com.formacraft.common.component.ComponentDefinition;
import com.formacraft.common.component.model.ComponentModelApi;
import com.formacraft.common.component.query.ComponentQuery;
import com.formacraft.common.json.JsonUtil;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import static org.junit.jupiter.api.Assertions.*;

class AsymmetricCaptureEdgesTest {
    @Test void legacySymmetricSettingsStillLoadAndSeparateEditsPreserveOtherEnd() {
        var settings = JsonUtil.fromJson("{\"fixedEdges\":[1,2,1]}", CaptureAdaptationSettings.class);
        assertEquals(2, settings.fixedEnd(1));
        settings.setFixedEdge(1, false, 1);
        assertEquals(2, settings.fixedEnd(1));
        var copy = settings.copy();
        settings.setFixedEdge(1, true, 3);
        assertEquals(2, copy.fixedEnd(1));
        var restored = JsonUtil.fromJson(JsonUtil.toJson(settings), CaptureAdaptationSettings.class);
        assertEquals(1, restored.fixedEdges[1]);
        assertEquals(3, restored.fixedEnd(1));
        assertThrows(IllegalArgumentException.class, () -> settings.setFixedEdge(1, false, 0));
    }

    @Test void stretchAndShrinkPreserveDistinctBaseAndTwoLayerCapital() {
        var def = new ComponentDefinition();
        def.id = "asymmetric_column"; def.sourceOrigin = "CAPTURED";
        def.size = new ComponentDefinition.Size(); def.size.w = def.size.d = 1; def.size.h = 6;
        def.blocks = new ArrayList<>();
        for (int y = 0; y < 6; y++) {
            var block = new ComponentDefinition.BlockEntry(); block.dy = y;
            block.block = y == 0 ? "minecraft:diamond_block" : y == 4 ? "minecraft:gold_block"
                    : y == 5 ? "minecraft:lapis_block" : "minecraft:stone";
            def.blocks.add(block);
        }
        var settings = new CaptureAdaptationSettings(); settings.modes[1] = CaptureAdaptationSettings.Mode.REPEAT;
        settings.setFixedEdge(1, true, 2); def.variantRules = settings.toRules(def);
        assertEquals(4, def.variantRules.scaling.axes.get("Y").min);
        for (int height : new int[]{4, 8}) {
            var q = new ComponentQuery(); q.geometry.openingHeight = height;
            var fitted = ComponentModelApi.fitToQuery(def, q, null);
            assertNotNull(fitted);
            assertEquals("minecraft:diamond_block", fitted.blocks.stream().filter(b -> b.dy == 0).findFirst().orElseThrow().block);
            assertEquals("minecraft:gold_block", fitted.blocks.stream().filter(b -> b.dy == height - 2).findFirst().orElseThrow().block);
            assertEquals("minecraft:lapis_block", fitted.blocks.stream().filter(b -> b.dy == height - 1).findFirst().orElseThrow().block);
        }
        var impossible = new ComponentQuery(); impossible.geometry.openingHeight = 3;
        assertNull(ComponentModelApi.fitToQuery(def, impossible, null));
        assertEquals(6, def.blocks.size()); assertEquals(6, def.size.h);
    }
}
