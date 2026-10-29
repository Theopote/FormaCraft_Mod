package com.formacraft.common.component.query;

import com.formacraft.common.component.rank.ComponentRanker;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ComponentRankingEvidenceTest {
    private ComponentMetadata candidate(String id, List<String> axes) {
        var m = new ComponentMetadata();
        m.componentId = id;
        m.geometrySpec = new ComponentMetadata.GeometrySpec();
        m.geometrySpec.baseSize = new int[]{3, 5, 1};
        m.geometrySpec.scalableAxes = axes;
        return m;
    }

    @Test void originalSizeRequestDoesNotPenalizeFixedAsset() {
        var q = new ComponentQuery();
        q.geometry.scalable = false;
        assertEquals(ComponentRanker.explain(q, candidate("fixed", List.of())).geometry(),
                ComponentRanker.explain(q, candidate("flexible", List.of("height"))).geometry());
    }

    @Test void tiesAreIndependentOfCatalogOrderAndEvidenceMatchesTotal() {
        var q = new ComponentQuery();
        var a = candidate("a", List.of());
        var b = candidate("b", List.of());
        var forward = ComponentRanker.rank(q, List.of(a, b));
        var reverse = ComponentRanker.rank(q, List.of(b, a));
        assertEquals(List.of("a", "b"), reverse.stream().map(s -> s.component().componentId).toList());
        assertEquals(forward, reverse);
        assertEquals(reverse.getFirst().score(), ComponentRanker.explain(q, a).total(), 1e-12);
    }
}
