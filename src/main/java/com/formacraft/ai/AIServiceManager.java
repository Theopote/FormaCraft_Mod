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
}
