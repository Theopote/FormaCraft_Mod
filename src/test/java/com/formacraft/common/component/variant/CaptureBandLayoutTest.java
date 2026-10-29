package com.formacraft.common.component.variant;

import com.formacraft.common.component.ComponentDefinition;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CaptureBandLayoutTest {
    @Test void displayedMinimumAndBoundariesAgreeWithSavedAsymmetricRules() {
        var settings = new CaptureAdaptationSettings();
        settings.modes[1] = CaptureAdaptationSettings.Mode.REPEAT;
        settings.setFixedEdge(1, true, 2);
        var band = CaptureBandLayout.of(settings, 1, 6);
        assertTrue(band.valid()); assertEquals(3, band.middleSize());
        assertEquals(1, band.start()); assertEquals(4, band.middleEnd());
        var def = new ComponentDefinition(); def.size = new ComponentDefinition.Size();
        def.size.w = def.size.d = 1; def.size.h = 6;
        assertEquals(settings.toRules(def).scaling.axes.get("Y").min.intValue(), band.minimumSize());
        assertEquals(4, band.minimumSize());
    }

    @Test void noMiddleIsRejectedButOneSliceIsValid() {
        var settings = new CaptureAdaptationSettings(); settings.modes[0] = CaptureAdaptationSettings.Mode.TRIM;
        settings.setFixedEdge(0, true, 2);
        assertFalse(CaptureBandLayout.of(settings, 0, 3).valid());
        assertFalse(CaptureBandLayout.of(settings, 0, 2).valid());
        assertTrue(CaptureBandLayout.of(settings, 0, 4).valid());
        assertEquals(1, CaptureBandLayout.of(settings, 0, 4).middleSize());
    }

    @Test void thinLockedAssetsDoNotNeedAnAdaptableMiddle() {
        var band = CaptureBandLayout.of(new CaptureAdaptationSettings(), 0, 1);
        assertTrue(band.valid()); assertTrue(band.locked());
        assertEquals(1, band.minimumSize()); assertEquals(0, band.middleSize());
        assertFalse(CaptureBandLayout.of(new CaptureAdaptationSettings(), 0, 0).valid());
    }
}
