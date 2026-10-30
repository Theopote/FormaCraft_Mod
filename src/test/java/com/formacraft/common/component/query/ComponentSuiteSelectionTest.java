package com.formacraft.common.component.query;

import com.formacraft.common.component.ComponentDefinition;
import com.formacraft.common.json.JsonUtil;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Random;
import static org.junit.jupiter.api.Assertions.*;

class ComponentSuiteSelectionTest {
    private ComponentDefinition capture(String id, int width, String suite) {
        var def = new ComponentDefinition(); def.id = id; def.sourceOrigin = "CAPTURED";
        def.tags = suite == null ? null : List.of("suite:" + suite);
        def.size = new ComponentDefinition.Size(); def.size.w = width; def.size.h = def.size.d = 1;
        var block = new ComponentDefinition.BlockEntry(); block.block = "minecraft:stone";
        def.blocks = List.of(block); return def;
    }
    private ComponentScore score(String id, double value) {
        var score = new ComponentScore(id); score.totalScore = value; return score;
    }
    @Test void preferredSuiteCanCoordinateSimilarCandidatesButCannotBypassFit() {
        var q = new ComponentQuery(); q.selection.preferredSuite = "alice/courtyard";
        q.geometry.openingWidth = 5;
        var report = ComponentRetriever.resolveCandidates(q, new Random(1),
                List.of(score("too_small", .95), score("other", .81), score("suite", .80)),
                id -> capture(id, id.equals("too_small") ? 3 : 5, id.equals("other") ? "bob/courtyard" : "alice/courtyard"));
        assertEquals("suite", report.result().sourceId());
        assertEquals(ComponentResolveResult.Status.ADAPT_FAILED, report.attempts().getFirst().status());
        assertTrue(report.attempts().getLast().fitScore().suiteMatched());
        assertEquals(.05, report.attempts().getLast().fitScore().suiteBonus());
    }
    @Test void missingSuiteDoesNotBlockOtherCandidatesAndOldQueriesHaveNoBonus() {
        var q = new ComponentQuery(); var def = capture("other", 5, "bob/courtyard");
        assertEquals(.8, ComponentFitScore.evaluate(score("other", .8), def, def, q).total());
        q.selection.preferredSuite = "alice/courtyard";
        assertFalse(ComponentFitScore.evaluate(score("other", .8), def, def, q).suiteMatched());
        var report = ComponentRetriever.resolveCandidates(q, new Random(1), List.of(score("other", .8)), id -> def);
        assertEquals("other", report.result().sourceId());
    }
    @Test void suiteIdentityIsExplicitAndSurvivesAssetSerialization() {
        var def = capture("suite", 5, "alice/courtyard");
        def = JsonUtil.fromJson(JsonUtil.toJson(def), ComponentDefinition.class);
        var q = new ComponentQuery(); q.selection.preferredSuite = "alice/court";
        assertFalse(ComponentFitScore.evaluate(score("suite", .8), def, def, q).suiteMatched());
        q.selection.preferredSuite = "alice/courtyard"; q.selection.suiteBonus = Double.NaN;
        assertEquals(.05, ComponentFitScore.evaluate(score("suite", .8), def, def, q).suiteBonus());
        q.selection.suiteBonus = -1;
        assertEquals(0, ComponentFitScore.evaluate(score("suite", .8), def, def, q).suiteBonus());
    }
}
