package com.formacraft.ai;

import com.formacraft.common.lang.LanguageParser;
import com.formacraft.common.lang.StructureData;

public class BasicAIService implements AIService {

    @Override
    public AIResult generateBuildingPlan(BuildingRequest request) {
        if (request == null) {
            return new AIResult("", null);
        }

        String prompt = request.getPrompt();
        StructureData data = LanguageParser.parse(prompt);
        return new AIResult(prompt, data);
    }

    @Override
    public AIResult generateBuildingPlan(BuildingRequest request, AICancelToken token) {
        if (token != null && token.isCancelled()) return null;
        return generateBuildingPlan(request);
    }
}
