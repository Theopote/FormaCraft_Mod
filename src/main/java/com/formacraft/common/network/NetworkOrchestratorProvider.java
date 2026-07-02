package com.formacraft.common.network;

import com.formacraft.server.orchestrator.OrchestratorClient;

/**
 * Lazy singleton for the Python orchestrator HTTP client (endpoint from ConfigManager).
 */
public final class NetworkOrchestratorProvider {
    private NetworkOrchestratorProvider() {}

    private static volatile OrchestratorClient orchestratorClient = null;
    private static String lastEndpoint = null;

    public static OrchestratorClient get() {
        String currentEndpoint = com.formacraft.common.config.ConfigManager.getOrchestratorEndpoint();
        if (orchestratorClient == null || !currentEndpoint.equals(lastEndpoint)) {
            synchronized (NetworkOrchestratorProvider.class) {
                if (orchestratorClient == null || !currentEndpoint.equals(lastEndpoint)) {
                    orchestratorClient = new OrchestratorClient(currentEndpoint);
                    lastEndpoint = currentEndpoint;
                }
            }
        }
        return orchestratorClient;
    }
}
