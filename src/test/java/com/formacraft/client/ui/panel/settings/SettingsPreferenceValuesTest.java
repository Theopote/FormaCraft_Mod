package com.formacraft.client.ui.panel.settings;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SettingsPreferenceValuesTest {
    @Test void acceptsExactTypedValuesAndBoundaries() {
        assertEquals(new SettingsPreferenceValues(37,0.23f),SettingsPreferenceValues.parse("37","0.23"));
        assertEquals(new SettingsPreferenceValues(5,0f),SettingsPreferenceValues.parse(" 5 ","0"));
        assertEquals(new SettingsPreferenceValues(100,1f),SettingsPreferenceValues.parse("100","1"));
    }
    @Test void rejectsPartialOrOutOfRangeDistancesInsteadOfSilentlyClamping() {
        for(String value:new String[]{"","-","4","101","37.5","text"})
            assertThrows(IllegalArgumentException.class,() -> SettingsPreferenceValues.parse(value,"0.5"));
    }
    @Test void rejectsNonFiniteOrInvalidTemperatures() {
        for(String value:new String[]{"",".","NaN","Infinity","-0.1","1.1","text"})
            assertThrows(IllegalArgumentException.class,() -> SettingsPreferenceValues.parse("80",value));
    }
}
