package com.formacraft.client.ui.panel.settings;

import com.formacraft.FormacraftMod;
import com.formacraft.client.ui.panel.SettingsPanel;
import net.minecraft.text.Text;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/** Package-private probe peer for SettingsPanel. */
public final class SettingsProbeController {
    private final SettingsPanel host;
    public SettingsProbeController(SettingsPanel panel) { this.host = panel; }

    public void startValidateSearchKey() {
        syncSearchIdentity();
        if (host.validatingSearchKey) return;
        long revision=host.searchRevision;
        SettingsPanel.SearchIdentity identity=currentSearchIdentity();
        host.validatingSearchKey = true;
        if (host.testSearchKeyButton != null) {
            host.testSearchKeyButton.setMessage(Text.literal("测试中..."));
            host.testSearchKeyButton.active = false;
        }

        String base = sanitizeEndpoint(host.orchestratorInput.getText());
        String provider = (host.draftSearchProvider == null || host.draftSearchProvider.isBlank()) ? "auto" : host.draftSearchProvider.trim();
        String searchApiKey = SettingsSearchProviders.showsCredentials(provider) ? host.searchApiKeyInput.getText().trim() : "";
        String googleCx = SettingsSearchProviders.showsGoogleCx(provider) ? host.googleCseCxInput.getText().trim() : "";

        String computedUrl = base + "/search/validate";
        try {
            StringBuilder q = new StringBuilder();
            q.append("provider=").append(URLEncoder.encode(provider, StandardCharsets.UTF_8));
            if (!googleCx.isBlank()) {
                q.append("&google_cse_cx=").append(URLEncoder.encode(googleCx, StandardCharsets.UTF_8));
            }
            computedUrl = computedUrl + "?" + q;
        } catch (Exception e) {
            host.LOG.debug("build search validate URL failed", e);
        }
        final String finalUrl = computedUrl;

        CompletableFuture.supplyAsync(() -> {
            var r = SettingsHttpProbe.get(host.httpClient, finalUrl, searchApiKey, Duration.ofSeconds(15));
            return new SettingsPanel.DetectResponse(r.status(), r.body());
        }).thenAccept(resp -> host.client.execute(() -> {
            if(revision!=host.searchRevision || !identity.equals(currentSearchIdentity())) return;
            host.validatingSearchKey = false;
            if (host.testSearchKeyButton != null) {
                host.testSearchKeyButton.setMessage(Text.literal("测试搜索配置"));
                host.testSearchKeyButton.active = true;
            }
            if (resp == null) {
                host.showToast("搜索校验失败：未知错误", true);
                return;
            }
            if (resp.status() < 0) {
                host.showToast("搜索校验失败：" + (resp.body() == null ? "" : resp.body()), true);
                return;
            }
            if (resp.status() < 200 || resp.status() >= 300) {
                host.showToast("搜索校验 HTTP " + resp.status() + "：" + (resp.body() == null ? "" : resp.body()), true);
                return;
            }
            String msg = readSearchValidateMessage(resp.body());
            boolean ok = readSearchValidateOk(resp.body());
            host.showToast(msg != null && !msg.isBlank() ? msg : (ok ? "搜索配置校验通过" : "搜索配置校验失败"), !ok);
        }));
    }

    public static boolean readSearchValidateOk(String body) {
        if (body == null || body.isBlank()) return false;
        try {
            var obj = com.formacraft.common.json.JsonUtil.get().fromJson(body, com.google.gson.JsonObject.class);
            return obj != null && obj.has("ok") && obj.get("ok").getAsBoolean();
        } catch (Exception e) {
            return false;
        }
    }

    public static String readSearchValidateMessage(String body) {
        if (body == null || body.isBlank()) return null;
        try {
            var obj = com.formacraft.common.json.JsonUtil.get().fromJson(body, com.google.gson.JsonObject.class);
            if (obj != null && obj.has("message_zh") && !obj.get("message_zh").isJsonNull()) {
                return obj.get("message_zh").getAsString();
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    public SettingsPanel.SearchIdentity currentSearchIdentity() {
        return new SettingsPanel.SearchIdentity(host.orchestratorInput.getText().trim(),host.draftSearchProvider,host.searchApiKeyInput.getText().trim(),host.googleCseCxInput.getText().trim());
    }

    public void syncSearchIdentity() {
        SettingsPanel.SearchIdentity next=currentSearchIdentity();
        if(next.equals(host.searchIdentity)) return;
        host.searchIdentity=next; host.searchRevision++; host.validatingSearchKey=false;
    }

    public SettingsPanel.ConnectionIdentity currentConnectionIdentity() {
        return new SettingsPanel.ConnectionIdentity(host.orchestratorInput.getText().trim(), host.apiKeyInput.getText().trim(),
                host.draftLlmProvider == null ? "auto" : host.draftLlmProvider, host.llmBaseUrlInput.getText().trim());
    }

    public void syncConnectionIdentity() {
        SettingsPanel.ConnectionIdentity next = currentConnectionIdentity();
        if (next.equals(host.connectionIdentity)) return;
        boolean changed = host.connectionIdentity != null;
        host.connectionIdentity = next;
        host.connectionRevision++;
        host.detectingModel = false;
        host.validatingKey = false;
        if (host.testKeyButton != null) host.testKeyButton.setMessage(Text.literal("校验 Key"));
        host.availableModels = new ArrayList<>();
        host.availableModelsUpdatedAtMs = 0L;
        if (changed && !host.modelInput.getText().isBlank()) host.modelNeedsConfirmation = true;
        host.widgets.syncBaseUrlPresetFromValue(host.llmBaseUrlInput.getText());
    }

    public void startDetectModel() {
        syncConnectionIdentity();
        if (host.detectingModel) return;
        long revision = host.connectionRevision;
        SettingsPanel.ConnectionIdentity identity = currentConnectionIdentity();
        host.detectingModel = true;
        if (host.detectModelButton != null) {
            host.detectModelButton.setMessage(host.widgets.getDetectModelButtonText());
        }

        String base = sanitizeEndpoint(host.orchestratorInput.getText());
        String url = base.endsWith("/models") ? base : (base + "/models");
        // 注意：这里用的是“当前输入框的 API Key”（草稿态），而不是已保存配置。
        // 否则用户未点 Save 时会一直使用旧 key，体验很差。
        String apiKey = host.apiKeyInput.getText() != null ? host.apiKeyInput.getText().trim() : "";
        String provider = (host.draftLlmProvider == null) ? "" : host.draftLlmProvider.trim();
        String llmBaseUrlRaw = host.llmBaseUrlInput.getText() != null ? host.llmBaseUrlInput.getText().trim() : "";
        String llmBaseUrl = SettingsModelCatalog.sanitizeLlmBaseUrlOrNull(llmBaseUrlRaw);
        if (llmBaseUrl == null && !llmBaseUrlRaw.isBlank()) {
            host.detectingModel = false;
            host.showToast("Base URL 无效：请填写 HTTP(S) 服务地址（不含查询参数或片段）", true);
            if (host.detectModelButton != null) host.detectModelButton.setMessage(host.widgets.getDetectModelButtonText());
            return;
        }

        // 附带 provider/baseUrl（让 orchestrator /models 也能探测真实 LLM 端点）
        String computedUrl = url;
        try {
            if (llmBaseUrl != null && (!provider.isBlank() || !llmBaseUrl.isBlank())) {
                StringBuilder q = new StringBuilder();
                if (!provider.isBlank())
                    q.append("provider=").append(URLEncoder.encode(provider, StandardCharsets.UTF_8));
                if (!llmBaseUrl.isBlank()) {
                    if (!q.isEmpty()) q.append('&');
                    q.append("base_url=").append(URLEncoder.encode(llmBaseUrl, StandardCharsets.UTF_8));
                }
                computedUrl = url + "?" + q;
            }
        } catch (Exception e) {
            host.LOG.debug("build models query URL failed", e);
        }
        final String finalUrl = computedUrl;

        // 记录关键信息到日志（避免 host.toast 过长看不清）
        FormacraftMod.LOGGER.debug(
                "[DetectModel] backend={} provider={} base_url={} url={}",
                base, provider, llmBaseUrl, finalUrl);

        CompletableFuture.supplyAsync(() -> {
            var r = SettingsHttpProbe.get(host.httpClient, finalUrl, apiKey, Duration.ofSeconds(12));
            return new SettingsPanel.DetectResponse(r.status(), r.body());
        }).thenAccept(resp -> host.client.execute(() -> {
            if (revision != host.connectionRevision || !identity.equals(currentConnectionIdentity())) return;
            host.detectingModel = false;
            if (resp == null) {
                host.showToast("刷新失败：未知错误（resp=null）", true);
                if (host.detectModelButton != null) host.detectModelButton.setMessage(host.widgets.getDetectModelButtonText());
                return;
            }

            // 本地异常（URI.create/连接失败等）
            if (resp.status() < 0) {
                String err = (resp.body() == null ? "" : resp.body());
                // 针对超时做更友好的提示：后端可能健康，但上游模型列表探测不可达/太慢
                if (err.toLowerCase().contains("httptimeoutexception") || err.toLowerCase().contains("request timed out")) {
                    host.showToast("刷新超时：上游模型列表可能不可达（仍可手动输入模型）", true);
                } else {
                    host.showToast("刷新失败：请求异常（详见日志） err=" + err, true);
                }
                if (host.detectModelButton != null) host.detectModelButton.setMessage(host.widgets.getDetectModelButtonText());
                return;
            }

            if (resp.body() == null || resp.body().isBlank()) {
                host.showToast("刷新失败：后端无响应内容（详见日志）", true);
                if (host.detectModelButton != null) host.detectModelButton.setMessage(host.widgets.getDetectModelButtonText());
                return;
            }

            if (resp.status() < 200 || resp.status() >= 300) {
                // 常见：OpenAI 兼容端点未带 key → 401；URL 不对 → 404
                host.showToast("刷新失败：" + resp.status() + "（详见日志）", true);
                if (host.detectModelButton != null) host.detectModelButton.setMessage(host.widgets.getDetectModelButtonText());
                return;
            }

            // 解析返回：models[] + default_model + remote_models_ok
            String suggested = SettingsModelCatalog.parseDetectedModel(resp.body());
            List<String> models = SettingsModelCatalog.parseModelsList(resp.body());
            host.availableModels = (models == null) ? new ArrayList<>() : new ArrayList<>(models);
            host.availableModelsUpdatedAtMs = System.currentTimeMillis();
            if (host.choices.isEditingValue()) host.choices.updateOptions(host.availableModels.stream()
                    .map(m -> new SettingsChoicePanel.Option(m, m)).toList());

            boolean remoteOk = SettingsModelCatalog.readRemoteModelsOk(resp.body());
            String modelsSource = SettingsModelCatalog.readModelsSource(resp.body());

            // 同步草稿态（以输入框为准；留空=自动）
            host.draftModel = host.modelInput.getText() == null ? "" : host.modelInput.getText().trim();

            if (host.availableModels.isEmpty()) {
                host.showToast("模型列表为空（可手动输入模型）", true);
            } else {
                String tip = "模型列表已更新：" + host.availableModels.size();
                if (modelsSource != null && !modelsSource.isBlank()) {
                    if ("fallback".equalsIgnoreCase(modelsSource)) tip += "（常用预设）";
                    if ("remote".equalsIgnoreCase(modelsSource)) tip += "（在线拉取）";
                }
                if (!remoteOk) tip += "（上游不可达）";
                if (suggested != null && !suggested.isBlank()) tip += "（推荐：" + suggested.trim() + "）";
                host.showToast(tip, false);
            }

            if (host.detectModelButton != null) host.detectModelButton.setMessage(host.widgets.getDetectModelButtonText());
        }));
    }

    public void startValidateKey() {
        syncConnectionIdentity();
        if (host.validatingKey) return;
        long revision = host.connectionRevision;
        SettingsPanel.ConnectionIdentity identity = currentConnectionIdentity();
        host.validatingKey = true;
        if (host.testKeyButton != null) {
            host.testKeyButton.setMessage(Text.literal("校验中..."));
            host.testKeyButton.active = false;
        }

        String base = sanitizeEndpoint(host.orchestratorInput.getText());
        String url = base.endsWith("/models") ? base : (base + "/models");
        String apiKey = host.apiKeyInput.getText() != null ? host.apiKeyInput.getText().trim() : "";
        String provider = (host.draftLlmProvider == null) ? "" : host.draftLlmProvider.trim();
        String model = host.modelInput.getText() != null ? host.modelInput.getText().trim() : "";
        String llmBaseUrlRaw = host.llmBaseUrlInput.getText() != null ? host.llmBaseUrlInput.getText().trim() : "";
        String llmBaseUrl = SettingsModelCatalog.sanitizeLlmBaseUrlOrNull(llmBaseUrlRaw);
        String providerHint = provider.isBlank() ? "auto" : provider;
        String modelHint = model.isBlank() ? "auto" : model;
        String baseHint = (llmBaseUrl == null || llmBaseUrl.isBlank()) ? "(auto)" : llmBaseUrl;
        final String llmHint = providerHint + "/" + modelHint + " @ " + baseHint;

        if (llmBaseUrl == null && !llmBaseUrlRaw.isBlank()) {
            host.validatingKey = false;
            host.showToast("Base URL 无效：请填写 HTTP(S) 服务地址（不含查询参数或片段）", true);
            if (host.testKeyButton != null) {
                host.testKeyButton.setMessage(Text.literal("校验 Key"));
                host.testKeyButton.active = true;
            }
            return;
        }

        String computedUrl = url;
        try {
            if (llmBaseUrl != null && (!provider.isBlank() || !llmBaseUrl.isBlank())) {
                StringBuilder q = new StringBuilder();
                if (!provider.isBlank()) {
                    q.append("provider=").append(URLEncoder.encode(provider, StandardCharsets.UTF_8));
                }
                if (!llmBaseUrl.isBlank()) {
                    if (!q.isEmpty()) q.append('&');
                    q.append("base_url=").append(URLEncoder.encode(llmBaseUrl, StandardCharsets.UTF_8));
                }
                computedUrl = url + "?" + q;
            }
        } catch (Exception e) {
            host.LOG.debug("build validate key URL failed", e);
        }
        final String finalUrl = computedUrl;

        CompletableFuture.supplyAsync(() -> {
            var r = SettingsHttpProbe.get(host.httpClient, finalUrl, apiKey, Duration.ofSeconds(10));
            return new SettingsPanel.DetectResponse(r.status(), r.body());
        }).thenAccept(resp -> host.client.execute(() -> {
            if (revision != host.connectionRevision || !identity.equals(currentConnectionIdentity())) return;
            host.validatingKey = false;
            if (host.testKeyButton != null) {
                host.testKeyButton.setMessage(Text.literal("校验 Key"));
                host.testKeyButton.active = true;
            }

            if (resp == null) {
                host.showToast("校验失败：未知错误（resp=null）", true);
                return;
            }

            if (resp.status() < 0) {
                host.showToast("校验失败：请求异常 " + (resp.body() == null ? "" : resp.body()), true);
                return;
            }

            if (resp.status() >= 200 && resp.status() < 300) {
                boolean remoteOk = SettingsModelCatalog.readRemoteModelsOk(resp.body());
                String source = SettingsModelCatalog.readModelsSource(resp.body());
                if (!remoteOk) {
                    String src = (source == null || source.isBlank()) ? "fallback" : source;
                    host.showToast("上游模型端点不可达（" + src + "），暂无法确认 Key 有效性。当前 LLM：" + llmHint, true);
                    return;
                }
                host.showToast("Key 校验通过（" + providerHint + "）", false);
                return;
            }

            if (resp.status() == 401 || resp.status() == 403) {
                host.showToast("Key 无效/未授权（" + resp.status() + "）。当前 LLM：" + llmHint, true);
                return;
            }

            host.showToast("校验失败：" + resp.status() + "。当前 LLM：" + llmHint + "。" + (resp.body() == null ? "" : resp.body()), true);
        }));
    }

    public static String sanitizeEndpoint(String endpoint) {
        if (endpoint == null || endpoint.trim().isEmpty()) {
            return "http://localhost:8000";
        }
        String v = endpoint.trim();
        
        // 简单容错：如果用户只填了 localhost:8000，则补协议
        if (!v.startsWith("http://") && !v.startsWith("https://")) {
            v = "http://" + v;
        }
        
        // 移除末尾斜杠
        while (v.endsWith("/")) {
            v = v.substring(0, v.length() - 1);
        }
        
        // 验证URL格式（使用URI避免弃用警告）
        try {
            URI uri = new URI(v);
            // 检查是否为有效的http/https URI
            String scheme = uri.getScheme();
            if (scheme == null || (!scheme.equals("http") && !scheme.equals("https"))) {
                return "http://localhost:8000";
            }
        } catch (URISyntaxException e) {
            // URL格式无效，返回默认值
            return "http://localhost:8000";
        }
        
        return v;
    }

}
