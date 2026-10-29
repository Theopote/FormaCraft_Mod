package com.formacraft.common.component.query;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Random;
import static org.junit.jupiter.api.Assertions.*;

class ComponentResolutionReportTest {
    @Test void fittedGeometryReplacesPreliminaryEstimateAndInvalidPenaltyUsesDefault() {
        var base = capture("fit", 3);
        var fitted = capture("fit", 5);
        var q = new ComponentQuery(); q.geometry.openingWidth = 5;
        var ranked = score("fit", 0);
        ranked.breakdown = new com.formacraft.common.component.rank.ComponentRanker.ScoreBreakdown(1, 1, 0.2, 1, 1);
        ranked.totalScore = ranked.breakdown.total();
        q.selection.adaptationPenalty = Double.NaN;
        var evidence = ComponentFitScore.evaluate(ranked, base, fitted, q);
        assertEquals(ranked.breakdown.withGeometry(1) - 0.1 * (2.0 / 3), evidence.total(), 1e-12);
        q.selection.adaptationPenalty = 0;
        assertEquals(ranked.breakdown.withGeometry(1), ComponentFitScore.evaluate(ranked, base, fitted, q).total(), 1e-12);
    }
    @Test void comparesEverySuccessfulFitRatherThanTakingTheFirst() {
        var approximate = capture("approximate", 4);
        var exact = capture("exact", 5);
        var q = new ComponentQuery();
        q.geometry.openingWidth = 5;
        q.geometry.tolerance = 1;
        var report = ComponentRetriever.resolveCandidates(q, new Random(1),
                List.of(score("approximate", 0.81), score("exact", 0.80)),
                id -> id.equals("exact") ? exact : approximate);
        assertEquals("exact", report.result().sourceId());
        assertEquals(2, report.attempts().size());
        assertEquals(0, report.attempts().getLast().fitScore().targetError());
    }

    @Test void originalSizeWinsAmongSimilarlyRankedPermittedFitsButNotOverBetterStyle() {
        var stretch = capture("stretch", 3);
        stretch.size.h = 3;
        var blocks = new java.util.ArrayList<com.formacraft.common.component.ComponentDefinition.BlockEntry>();
        for (int x = 0; x < 3; x++) for (int y = 0; y < 3; y++) {
            var b = new com.formacraft.common.component.ComponentDefinition.BlockEntry();
            b.dx = x; b.dy = y; b.block = "minecraft:stone"; blocks.add(b);
        }
        stretch.blocks = blocks;
        var settings = new com.formacraft.common.component.variant.CaptureAdaptationSettings();
        settings.modes[0] = com.formacraft.common.component.variant.CaptureAdaptationSettings.Mode.REPEAT;
        stretch.variantRules = settings.toRules(stretch);
        var original = capture("original", 5);
        var q = new ComponentQuery(); q.geometry.openingWidth = 5;
        var close = ComponentRetriever.resolveCandidates(q, new Random(1),
                List.of(score("stretch", 0.81), score("original", 0.80)),
                id -> id.equals("stretch") ? stretch : original);
        assertEquals("original", close.result().sourceId());
        assertTrue(close.attempts().getFirst().fitScore().dimensionalChange() > 0);
        var distinct = ComponentRetriever.resolveCandidates(q, new Random(1),
                List.of(score("stretch", 0.95), score("original", 0.70)),
                id -> id.equals("stretch") ? stretch : original);
        assertEquals("stretch", distinct.result().sourceId());
        assertEquals(3, stretch.size.w);
    }

    @Test void fittedScoreTiesHaveStableIdentityOrder() {
        var q = new ComponentQuery();
        var report = ComponentRetriever.resolveCandidates(q, new Random(1),
                List.of(score("z", 0.8), score("a", 0.8)), id -> capture(id, 3));
        assertEquals("a", report.result().sourceId());
    }
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
