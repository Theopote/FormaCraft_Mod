package com.formacraft.common.style;
import org.junit.jupiter.api.Test;
import com.formacraft.common.style.catalog.StyleProfileCatalogRegistry;
import static org.junit.jupiter.api.Assertions.*;

class StyleIdentityRegistryTest {
    @Test void legacyEquivalentNamesResolveToSameProfile(){
        assertSame(StyleProfileCatalogRegistry.get("Industrial_Structure"),StyleProfileCatalogRegistry.get("Industrial_Structural"));
        assertSame(StyleProfileCatalogRegistry.get("Greco_Roman_Classical"),StyleProfileCatalogRegistry.get("Classical_GrecoRoman"));
    }
    @Test void chineseAndCaseAliasesResolve(){assertEquals("Modern_International",StyleIdentityRegistry.canonical("现代风格"));assertNotNull(StyleProfileCatalogRegistry.get("modern_international"));}
    @Test void historicalVariantsAreNotCollapsedIntoImperialStyle(){assertEquals("Tang_Dynasty_Timber",StyleIdentityRegistry.canonical("Tang_Dynasty_Timber"));assertNull(StyleProfileCatalogRegistry.get("Tang_Dynasty_Timber"));}
    @Test void unknownAndEditNamesArePreserved(){assertEquals("UnknownStyle",StyleIdentityRegistry.canonical("UnknownStyle"));assertEquals("Patch_Edit",StyleIdentityRegistry.canonical("Patch_Edit"));}
}
