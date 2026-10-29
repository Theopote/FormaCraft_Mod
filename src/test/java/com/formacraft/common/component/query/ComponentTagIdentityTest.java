package com.formacraft.common.component.query;

import com.formacraft.common.component.rank.ComponentRanker;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class ComponentTagIdentityTest {
    @Test void playerSearchAndSemanticDiscoveryHaveDifferentPurposes() {
        assertTrue(ComponentQueryMatchUtil.tagSearchMatches("wood frame", List.of("dark_wood-frame")));
        assertTrue(ComponentQueryMatchUtil.tagSearchMatches("art", List.of("cart")));
        assertFalse(ComponentQueryMatchUtil.tagMatches("art", List.of("cart")));
    }

    @Test void inferredAffinityIsIndependentOfTagOrder() {
        var def = new com.formacraft.common.component.ComponentDefinition();
        def.tags = List.of("gothic", "chinese");
        var first = ComponentMetadata.fromComponent(def, null).styleAffinity;
        def.tags = List.of("chinese", "gothic");
        assertEquals(first, ComponentMetadata.fromComponent(def, null).styleAffinity);
    }
    @Test void normalizedIdentitiesPreserveNamespacesAndUnknownTags() {
        assertTrue(ComponentQueryMatchUtil.tagIdentityMatches(" ＤＡＲＫ－ＷＯＯＤ ", List.of("dark_wood")));
        assertTrue(ComponentQueryMatchUtil.tagIdentityMatches("my-pack:曲线 窗", List.of("my_pack:曲线_窗")));
        assertFalse(ComponentQueryMatchUtil.tagIdentityMatches("window", List.of("author:window")));
        assertFalse(ComponentQueryMatchUtil.tagIdentityMatches("gothic", List.of("neo_gothic")));
    }

    @Test void discoveryUsesPhraseBoundariesAndKeepsChineseKeywords() {
        assertFalse(ComponentQueryMatchUtil.roleImpliedByTags("door", List.of("outdoor")));
        assertFalse(ComponentQueryMatchUtil.roleImpliedByTags("door", List.of("")));
        assertFalse(ComponentQueryMatchUtil.tagMatches("art", List.of("cart")));
        assertFalse(ComponentQueryMatchUtil.tagMatches("gothic_stone", List.of("gothic")));
        assertTrue(ComponentQueryMatchUtil.tagMatches("gothic", List.of("gothic_stone")));
        assertTrue(ComponentQueryMatchUtil.tagMatches("dark wood", List.of("dark-wood-frame")));
        assertTrue(ComponentQueryMatchUtil.roleImpliedByTags("window", List.of("雕花窗框")));
    }

    @Test void requiredAndForbiddenTagsAreWholeIdentities() {
        var q = new ComponentQuery();
        var m = new ComponentMetadata(); m.semantic = new ComponentMetadata.Semantic();
        m.semantic.tags = List.of("dark_wood", "outdoor");
        q.constraints.mustHave = Set.of("door");
        assertFalse(ComponentRetriever.hardFilter(m, q));
        q.constraints.mustHave = Set.of("ＤＡＲＫ－ＷＯＯＤ");
        assertTrue(ComponentRetriever.hardFilter(m, q));
        q.constraints.forbiddenTags = Set.of("door");
        assertTrue(ComponentRetriever.hardFilter(m, q));
        q.constraints.forbiddenTags = Set.of("dark wood");
        assertFalse(ComponentRetriever.hardFilter(m, q));
    }

    @Test void styleSpellingVariantsMatchWithoutInventingShapeAffinityFromMaterialSwap() {
        var q = new ComponentQuery(); q.style.styleProfile = "Ｍｅｄｉｅｖａｌ－Ｃａｓｔｌｅ";
        var m = new ComponentMetadata(); m.styleAffinity = Map.of("Medieval_Castle", 0.9);
        assertEquals(0.9, ComponentRanker.explain(q, m).style());
        m.variantCapability = new ComponentMetadata.VariantCapability(); m.variantCapability.materialSwap = true;
        q.style.styleProfile = "Gothic";
        assertEquals(0, ComponentRanker.explain(q, m).style());
        m.styleAffinity = Map.of("GOTHIC", Double.NaN, "gothic", 2.0);
        assertEquals(1, ComponentRanker.explain(q, m).style());
    }
}
