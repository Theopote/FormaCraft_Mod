package com.formacraft.server.rag;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class KeywordCultureRetrieverTest {
    @Test
    void assemblyQueriesKeepTheirCompatibleCardAndLoadableExamples() throws Exception {
        var retriever = new KeywordCultureRetriever(CultureCardRepository.load());
        assertAssemblyHit(retriever, "生成一个日式传统建筑：天守阁、层叠屋檐、深色瓦、禅意", "japanese_traditional");
        assertAssemblyHit(retriever, "生成一个日式传统建筑：天守阁、层叠屋檐、深色瓦、禅意、架空地板", "japanese_traditional");
        assertAssemblyHit(retriever, "生成一个现代主义包豪斯建筑：幕墙、平屋顶、少即是多、不要哥特式、不要飞扶壁、不要玫瑰花窗", "modern_international");
    }

    @Test
    void repositoryRetainsLlmPlanCardsWithoutRankingThemAsAssemblyExamples() throws Exception {
        var repo = CultureCardRepository.load();
        var preset = repo.allCards().stream().filter(c -> c.id().equals("japanese_timber_house")).findFirst().orElseThrow();
        assertFalse(preset.llmPlanExampleRefs().isEmpty());
        assertFalse(preset.supportsAssemblyRetrieval());
        assertTrue(new KeywordCultureRetriever(repo).retrieve("日式传统", 100, 3).hits().stream()
                .allMatch(hit -> hit.card().supportsAssemblyRetrieval()));
    }

    @Test
    void mixedFormatAndKnowledgeOnlyCardsRemainEligible() {
        assertTrue(card(List.of("assembly.json"), List.of("llmplan.json"), List.of()).supportsAssemblyRetrieval());
        var archetype = new CultureCard.CultureArchetype("box", "assembly.json", Map.of(), List.of());
        assertTrue(card(List.of(), List.of("llmplan.json"), List.of(archetype)).supportsAssemblyRetrieval());
        assertTrue(card(List.of(), List.of(), List.of()).supportsAssemblyRetrieval());
        assertFalse(card(List.of(), List.of("llmplan.json"), List.of()).supportsAssemblyRetrieval());
    }

    private static CultureCard card(List<String> assemblyRefs, List<String> planRefs,
                                    List<CultureCard.CultureArchetype> archetypes) {
        return new CultureCard("test", "DEFAULT", List.of(), List.of(), Map.of(), Map.of(),
                List.of(), assemblyRefs, planRefs, archetypes);
    }

    private static void assertAssemblyHit(KeywordCultureRetriever retriever, String prompt, String expected) {
        var result = retriever.retrieve(prompt, 3, 3);
        assertFalse(result.hits().isEmpty());
        assertEquals(expected, result.hits().getFirst().card().id());
        assertFalse(result.fewShots().isEmpty());
        assertEquals(result.fewShotExampleRefs().size(), result.fewShots().size());
    }
}
