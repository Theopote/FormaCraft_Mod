package com.formacraft.client.component;

import com.formacraft.common.component.*;
import com.formacraft.common.component.variant.CaptureAdaptationSettings;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ComponentRecipeDraftTest {
    private ComponentCatalog.Entry entry() {
        var e = new ComponentCatalog.Entry(); e.id = "asset_a"; e.name = "Railing"; e.packageRef = "ref";
        e.size = new ComponentDefinition.Size(); e.size.w = 5; e.size.h = e.size.d = 1;
        var d = new ComponentDefinition(); d.size = e.size; var settings = new CaptureAdaptationSettings();
        settings.modes[0] = CaptureAdaptationSettings.Mode.REPEAT; e.variantRules = settings.toRules(d); return e;
    }
    @Test void respectsFixedAxesAndRangeAndResetsOnlyWhenAssetIdentityChanges() {
        var draft = new ComponentRecipeDraft(); var e = entry(); draft.bind(e);
        assertFalse(draft.canAdjust(1,1)); assertTrue(draft.canAdjust(0,1)); draft.adjust(0,1);
        assertEquals(6,draft.spec().x()); assertEquals(5,e.size.w);
        draft.bind(entry()); assertEquals(6,draft.size(0));
        e.packageRef = "new ref"; draft.bind(e); assertEquals(5,draft.size(0));
        draft.adjust(0,-2); assertFalse(draft.canAdjust(0,-1)); assertFalse(draft.canAdjust(0,100));
    }
}
