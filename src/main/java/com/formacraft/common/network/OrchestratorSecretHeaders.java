package com.formacraft.common.network;

/**
 * Shared helper for {@code X-FormaCraft-Secret}. Client-side callers read the process
 * environment (same var the auto-started backend inherits); dedicated servers use
 * {@link com.formacraft.server.config.ServerLlmConfig#orchestratorSecret()}.
 */
public final class OrchestratorSecretHeaders {
    public static final String HEADER = "X-FormaCraft-Secret";
    public static final String ENV = "FORMACRAFT_ORCHESTRATOR_SECRET";

    private OrchestratorSecretHeaders() {}

    /** Local/env secret for client→orchestrator probes (never persisted in settings JSON). */
    public static String fromEnvironment() {
        String raw = System.getenv(ENV);
        return raw == null || raw.isBlank() ? null : raw.trim();
    }

    public static void apply(java.net.http.HttpRequest.Builder builder, String secret) {
        if (builder == null) return;
        if (secret != null && !secret.isBlank()) {
            builder.header(HEADER, secret.trim());
        }
    }
}
