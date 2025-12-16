package com.formacraft.ai;

public class AIServiceManager implements AIService {

    private final AIService cloudService;
    private final AIService localService;

    public AIServiceManager() {
        this.cloudService = new HttpAIService();
        this.localService = new BasicAIService();
    }

    public AIServiceManager(AIService cloudService, AIService localService) {
        this.cloudService = cloudService;
        this.localService = localService;
    }

    @Override
    public AIResult generateBuildingPlan(BuildingRequest request) {
        try {
            return cloudService.generateBuildingPlan(request);
        } catch (Exception e) {
            return localService.generateBuildingPlan(request);
        }
    }

    @Override
    public AIResult generateBuildingPlan(BuildingRequest request, AICancelToken token) {
        if (token != null && token.isCancelled()) return null;
        try {
            AIResult r = cloudService.generateBuildingPlan(request, token);
            if (r != null) return r;
            if (token != null && token.isCancelled()) return null;
            return localService.generateBuildingPlan(request, token);
        } catch (Exception e) {
            if (token != null && token.isCancelled()) return null;
            return localService.generateBuildingPlan(request, token);
        }
    }
}
