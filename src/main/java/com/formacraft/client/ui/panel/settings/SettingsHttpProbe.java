package com.formacraft.client.ui.panel.settings;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/** Shared HTTP GET helper for Settings panel /models and /search/validate probes. */
public final class SettingsHttpProbe {
    private SettingsHttpProbe() {}

    public record Response(int status, String body) {}

    public static Response get(HttpClient client, String url, String bearerToken, Duration timeout) {
        return get(client, url, bearerToken, timeout, com.formacraft.common.network.OrchestratorSecretHeaders.fromEnvironment());
    }

    public static Response get(
            HttpClient client, String url, String bearerToken, Duration timeout, String orchestratorSecret
    ) {
        if (!com.formacraft.common.network.OrchestratorEndpointAccess.clientMayCallDirectly(url)) {
            return new Response(401, com.formacraft.common.network.OrchestratorEndpointAccess.REMOTE_SECRET_HINT_ZH);
        }
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(timeout == null ? Duration.ofSeconds(12) : timeout)
                    .header("Accept", "application/json")
                    .GET();
            if (bearerToken != null && !bearerToken.isBlank()) {
                b.header("Authorization", "Bearer " + bearerToken);
            }
            com.formacraft.common.network.OrchestratorSecretHeaders.apply(b, orchestratorSecret);
            HttpResponse<String> resp = client.send(b.build(), HttpResponse.BodyHandlers.ofString());
            return new Response(resp.statusCode(), resp.body());
        } catch (Exception e) {
            return new Response(-1, e.toString());
        }
    }
}
