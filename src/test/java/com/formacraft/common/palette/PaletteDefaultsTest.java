package com.formacraft.common.palette;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.formacraft.common.model.build.BuildingSpec;
import com.formacraft.common.model.build.BuildingStyle;
import com.formacraft.server.generation.structure.HouseStyleOptionsResolver;
import org.junit.jupiter.api.Test;

class PaletteDefaultsTest {

    @Test
    void defaultStyleUsesYellowishCream() {
        assertEquals(PaletteDefaults.YELLOWISH_CREAM, PaletteDefaults.forStyle(BuildingStyle.DEFAULT, null));
    }

    @Test
    void medievalUsesStoneFortress() {
        assertEquals(PaletteDefaults.STONE_FORTRESS, PaletteDefaults.forStyle(BuildingStyle.MEDIEVAL, null));
    }

    @Test
    void resolvePaletteIdFallsBackWhenExtraMissing() {
        BuildingSpec spec = new BuildingSpec();
        spec.setStyle(BuildingStyle.DEFAULT);
        String paletteId = HouseStyleOptionsResolver.resolvePaletteId(spec, null);
        assertEquals(PaletteDefaults.YELLOWISH_CREAM, paletteId);
    }

    @Test
    void yellowishCreamPaletteLoadsFromCatalog() {
        PaletteRegistry.ensureLoaded();
        assertNotNull(PaletteRegistry.get(PaletteDefaults.YELLOWISH_CREAM));
    }
}
