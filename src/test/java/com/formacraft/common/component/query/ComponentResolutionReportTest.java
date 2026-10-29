package com.formacraft.common.component.query;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Random;
import static org.junit.jupiter.api.Assertions.*;

class ComponentResolutionReportTest {
    @Test void skipsUnfittableCaptureAndPreservesOriginal() {
        var small = capture("small", 3);
        var matching = capture("matching", 5);
        var query = new ComponentQuery();
        query.geometry.openingWidth = 5;
        var report = ComponentRetriever.resolveCandidates(query, new Random(1),
                List.of(score("small", 0.9), score("matching", 0.8)),
                id -> id.equals("small") ? small : matching);
        assertEquals(ComponentResolveResult.Status.SUCCESS, report.result().status());
        assertEquals("matching", report.result().sourceId());
        assertEquals(List.of(ComponentResolveResult.Status.ADAPT_FAILED, ComponentResolveResult.Status.SUCCESS),
                report.attempts().stream().map(ComponentRetriever.CandidateAttempt::status).toList());
        assertEquals(3, small.size.w);
        assertEquals(1, small.blocks.size());
    }

    private com.formacraft.common.component.ComponentDefinition capture(String id, int width) {
        var def = new com.formacraft.common.component.ComponentDefinition();
        def.id = id;
        def.sourceOrigin = "CAPTURED";
        def.size = new com.formacraft.common.component.ComponentDefinition.Size();
        def.size.w = width;
        def.size.h = 1;
        def.size.d = 1;
        var block = new com.formacraft.common.component.ComponentDefinition.BlockEntry();
        block.block = "minecraft:stone";
        def.blocks = List.of(block);
        return def;
    }
    private ComponentScore score(String id, double value) {
        var score = new ComponentScore(id);
        score.totalScore = value;
        return score;
    }

    @Test void missingRankedAssetIsLoadFailureRatherThanLibraryMiss() {
        var report = ComponentRetriever.resolveCandidates(new ComponentQuery(), new Random(1),
                List.of(score("missing", 0.9)), id -> null);
        assertEquals(ComponentResolveResult.Status.LOAD_FAILED, report.result().status());
        assertEquals("missing", report.result().sourceId());
        assertEquals(ComponentResolveResult.Status.LOAD_FAILED, report.attempts().getFirst().status());
        assertThrows(UnsupportedOperationException.class, () -> report.attempts().clear());
    }

    @Test void lowScoreAndNullQueriesDoNotLoadAssets() {
        var report = ComponentRetriever.resolveCandidates(new ComponentQuery(), new Random(1),
                List.of(score("irrelevant", 0.2)), id -> { fail("must not load rejected candidate"); return null; });
        assertEquals(ComponentResolveResult.Status.NO_CANDIDATE, report.result().status());
        assertTrue(report.attempts().isEmpty());
        assertEquals("query_null", ComponentRetriever.resolveCandidates(null, new Random(1),
                List.of(), id -> null).result().message());
    }
}
