package com.formacraft.server.rag;

import java.util.List;
import java.util.Map;

/**
 * P0 Culture Card schema used for retrieval + few-shot selection.
 *
 * Stored as assets/formacraft/culture_cards/*.json
 */
public record CultureCard(
        String id,
        String styleId,
        List<String> intents,
        List<String> keywords,
        Map<String, List<String>> synonyms,
        Map<String, Double> keywordWeights,
        List<String> negativeKeywords,
        List<String> exampleRefs,
        List<String> llmPlanExampleRefs,
        List<CultureArchetype> archetypes
) {
    /** LlmPlan-only cards cannot supply examples for the legacy Assembly DSL. */
    public boolean supportsAssemblyRetrieval() {
        return llmPlanExampleRefs.isEmpty()
                || !exampleRefs.isEmpty()
                || archetypes.stream().anyMatch(a -> a.exampleRef() != null && !a.exampleRef().isBlank());
    }

    public record CultureArchetype(
            String name,
            String exampleRef,
            Map<String, Object> macroHint,
            List<String> constraints
    ) {}
}


