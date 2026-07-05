package com.formacraft.common.component.query;

import com.formacraft.common.component.query.ComponentMetadata.Semantic;
import com.formacraft.common.component.rank.ComponentRanker;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ComponentQueryMatchUtilTest {

    @Test
    void tagMatchesIsCaseInsensitive() {
        assertTrue(ComponentQueryMatchUtil.tagMatches("Chinese", List.of("chinese_traditional")));
        assertTrue(ComponentQueryMatchUtil.tagMatches("GOTHIC", List.of("medieval", "gothic_stone")));
    }

    @Test
    void roleImpliedByTagsSupportsChineseKeywords() {
        assertTrue(ComponentQueryMatchUtil.roleImpliedByTags("balcony", List.of("江南", "阳台")));
    }
}

class ComponentRankerSoftMatchTest {

    @Test
    void ranksTagOnlyBalconyAboveUnrelatedDecoration() {
        ComponentQuery query = new ComponentQuery();
        query.semantic = new ComponentQuery.Semantic();
        query.semantic.role = "balcony";
        query.semantic.tags = Set.of("chinese");

        ComponentMetadata balconyLike = new ComponentMetadata();
        balconyLike.componentId = "bal_a";
        balconyLike.semantic = new Semantic();
        balconyLike.semantic.role = "decoration";
        balconyLike.semantic.tags = List.of("chinese", "balcony");

        ComponentMetadata unrelated = new ComponentMetadata();
        unrelated.componentId = "orn_a";
        unrelated.semantic = new Semantic();
        unrelated.semantic.role = "decoration";
        unrelated.semantic.tags = List.of("gothic");

        var ranked = ComponentRanker.rank(query, List.of(unrelated, balconyLike));
        assertFalse(ranked.isEmpty());
        assertTrue(ranked.getFirst().component().componentId.equals("bal_a"));
    }
}
