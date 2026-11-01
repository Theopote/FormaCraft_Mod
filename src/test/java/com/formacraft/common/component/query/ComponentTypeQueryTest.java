package com.formacraft.common.component.query;

import com.formacraft.common.component.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ComponentTypeQueryTest {
    private ComponentMetadata metadata() {
        var m = new ComponentMetadata(); m.category = ComponentCategory.GENERIC; m.type = ComponentTypes.custom("飞檐挂落");
        m.semantic = new ComponentMetadata.Semantic(); m.semantic.tags = List.of(); return m;
    }
    @Test void exactCustomLabelAndKeyFilterWithoutInventingPartialTypeAliases() {
        var m = metadata(); var query = new ComponentQuery(); query.semantic.assetType = "飞檐挂落";
        assertTrue(ComponentRetriever.hardFilter(m, query)); query.semantic.assetType = m.type.key(); assertTrue(ComponentRetriever.hardFilter(m, query));
        query.semantic.assetType = "挂落"; assertFalse(ComponentRetriever.hardFilter(m, query));
    }
    @Test void verifiedSourceTypeAliasesAreDiscoverableWithoutGrantingRequiredTags() {
        var m = metadata(); m.typeAliases = List.of(ComponentTypes.custom("组合花架")); var query = new ComponentQuery();
        query.semantic.assetType = "组合花架"; assertTrue(ComponentRetriever.hardFilter(m, query));
        query.constraints.mustHave.add("allow_carve"); assertFalse(ComponentRetriever.hardFilter(m, query));
        assertTrue(m.semantic.tags.isEmpty());
    }
    @Test void missingTypeFallsBackToCoarseCategoryAndNoTypeQueryKeepsNormalBehavior() {
        var m = metadata(); m.type = null; m.category = ComponentCategory.WINDOW; var query = new ComponentQuery();
        assertTrue(ComponentRetriever.hardFilter(m, query)); query.semantic.assetType = "窗"; assertTrue(ComponentRetriever.hardFilter(m, query));
        query.semantic.assetType = "屋脊"; assertFalse(ComponentRetriever.hardFilter(m, query));
    }
}
