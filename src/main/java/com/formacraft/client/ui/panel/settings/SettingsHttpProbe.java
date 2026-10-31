package com.formacraft.client.ui.panel.settings;

import com.formacraft.client.network.OrchestratorProxyClient;
import com.formacraft.common.network.OrchestratorEndpointAccess;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

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
        if (!OrchestratorEndpointAccess.clientMayCallDirectly(url)) {
            Response proxied = tryModelsProxy(url, bearerToken, timeout);
            if (proxied != null) {
                return proxied;
            }
            return new Response(401, OrchestratorEndpointAccess.REMOTE_SECRET_HINT_ZH);
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

    private static Response tryModelsProxy(String url, String bearerToken, Duration timeout) {
        try {
            String query = "";
            int q = url == null ? -1 : url.indexOf('?');
            if (q >= 0 && q + 1 < url.length()) {
                query = url.substring(q + 1);
            }
            long seconds = timeout == null ? 12L : Math.max(3L, timeout.toSeconds());
            var fut = OrchestratorProxyClient.models(url, query, bearerToken);
            var payload = fut.get(seconds, TimeUnit.SECONDS);
            if (payload == null) {
                return null;
            }
            return new Response(payload.status(), payload.body());
        } catch (Exception e) {
            return new Response(-1, e.toString());
        }
    }
}
