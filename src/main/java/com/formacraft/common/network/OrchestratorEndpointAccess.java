package com.formacraft.common.network;

import java.net.URI;
import java.util.Locale;

/**
 * Whether the Minecraft client may call the orchestrator HTTP API directly.
 * Dedicated multiplayer backends keep the secret on the server; clients without
 * {@link OrchestratorSecretHeaders#ENV} must not hit a remote /summarize or /models.
 */
public final class OrchestratorEndpointAccess {
    private OrchestratorEndpointAccess() {}

    public static final String REMOTE_SECRET_HINT_ZH =
            "远程编排器需要环境变量 FORMACRAFT_ORCHESTRATOR_SECRET；"
                    + "多人服请在本地启动后端，或由服务端转发摘要/模型探测。";

    public static boolean isLocalhost(String endpoint) {
        if (endpoint == null || endpoint.isBlank()) {
            return true;
        }
        try {
            String raw = endpoint.trim();
            while (raw.endsWith("/")) {
                raw = raw.substring(0, raw.length() - 1);
            }
            URI uri = URI.create(raw.contains("://") ? raw : ("http://" + raw));
            String host = uri.getHost();
            if (host == null || host.isBlank()) {
                return looksLikeLocalhostLiteral(raw);
            }
            String h = host.toLowerCase(Locale.ROOT);
            return "localhost".equals(h)
                    || "127.0.0.1".equals(h)
                    || "::1".equals(h)
                    || "0:0:0:0:0:0:0:1".equals(h);
        } catch (Exception e) {
            return looksLikeLocalhostLiteral(endpoint);
        }
    }

    private static boolean looksLikeLocalhostLiteral(String endpoint) {
        String lower = endpoint.toLowerCase(Locale.ROOT);
        return lower.contains("localhost") || lower.contains("127.0.0.1");
    }

    /** True when client HTTP to this endpoint is expected to authenticate successfully. */
    public static boolean clientMayCallDirectly(String endpoint) {
        if (isLocalhost(endpoint)) {
            return true;
        }
        String secret = OrchestratorSecretHeaders.fromEnvironment();
        return secret != null && !secret.isBlank();
    }
}
