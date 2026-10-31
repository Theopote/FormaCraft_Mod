package com.formacraft.server.orchestrator;

import com.formacraft.common.json.JsonUtil;
import com.formacraft.common.llm.dto.LlmPlan;
import com.formacraft.common.llm.parser.LlmPlanParser;
import com.formacraft.common.llm.parser.PlanParseException;
import com.formacraft.common.model.build.BuildingSpec;
import com.formacraft.common.model.city.CitySpec;
import com.formacraft.common.model.composite.CompositeSpec;
import com.formacraft.common.model.request.FormaRequest;
import com.formacraft.common.orchestrator.AiPlanResult;
import com.formacraft.common.orchestrator.ClarificationResponse;
import com.formacraft.FormacraftMod;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * OrchestratorClient - 负责与 Python 后端通信
 * 使用 Java 11+ 自带的 HttpClient（Fabric 环境可用）
 * 支持 BuildingSpec 和 CompositeSpec
 */
public class OrchestratorClient {
    private final String endpoint;
    private final String sharedSecret;
    private final HttpClient httpClient;
    // 复合/城市规划类请求可能耗时较长（尤其是 deepseek/reasoner），这里给足时间，避免过早中断。
    // 从配置读取，默认 600 秒
    private static final long ORCHESTRATOR_TIMEOUT_SEC = getTimeoutFromConfig();
    private static final long HEALTH_CACHE_TTL_MS = 10_000L;
    private static final String SECRET_HEADER = "X-FormaCraft-Secret";

    private volatile Boolean healthCache;
    private volatile long healthCacheAtMs;
    private final Object healthProbeLock = new Object();
    private volatile boolean healthProbeInFlight;

    private static long getTimeoutFromConfig() {
        // TODO: 从 SettingsConfig 读取（如果将来添加超时配置选项）
        // 目前暂时保持 600 秒的默认值
        return 600;
    }

    /**
     * Non-blocking health view: returns last known result (TTL-cached).
     * Never performs a synchronous HTTP call on the caller thread.
     * Miss / stale → schedule async probe and treat as unavailable until refreshed.
     */
    public boolean checkHealth() {
        long now = System.currentTimeMillis();
        Boolean cached = healthCache;
        if (cached != null && (now - healthCacheAtMs) < HEALTH_CACHE_TTL_MS) {
            return cached;
        }
        scheduleHealthProbe();
        return cached != null && cached;
    }

    private void scheduleHealthProbe() {
        if (healthProbeInFlight) return;
        synchronized (healthProbeLock) {
            if (healthProbeInFlight) return;
            healthProbeInFlight = true;
        }
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(endpoint + "/health"))
                .timeout(Duration.ofSeconds(5))
                .GET();
        applySecret(builder);
        httpClient.sendAsync(builder.build(), HttpResponse.BodyHandlers.ofString())
                .orTimeout(6, TimeUnit.SECONDS)
                .whenComplete((resp, err) -> {
                    boolean ok = err == null && resp != null
                            && resp.statusCode() >= 200 && resp.statusCode() < 300;
                    if (err != null) {
                        FormacraftMod.LOGGER.debug("Health check failed for {}: {}", endpoint, err.getMessage());
                    }
                    healthCache = ok;
                    healthCacheAtMs = System.currentTimeMillis();
                    healthProbeInFlight = false;
                });
    }

    public OrchestratorClient(String endpoint) {
        this(endpoint, com.formacraft.server.config.ServerLlmConfig.orchestratorSecret());
    }

    public OrchestratorClient(String endpoint, String sharedSecret) {
        this.endpoint = endpoint;
        this.sharedSecret = sharedSecret == null || sharedSecret.isBlank() ? null : sharedSecret.trim();
        this.httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .build();
    }

    private void applySecret(HttpRequest.Builder builder) {
        if (sharedSecret != null) {
            builder.header(SECRET_HEADER, sharedSecret);
        }
    }

    private static String redactSecretsForLog(String json) {
        if (json == null || json.isEmpty()) return json;
        String out = json;
        for (String key : new String[]{"apiKey", "searchApiKey", "googleCseCx", "orchestratorSecret"}) {
            out = out.replaceAll("(\"" + key + "\"\\s*:\\s*\")([^\"]*)(\")", "$1***$3");
        }
        return out;
    }

    private static String requestLogSummary(FormaRequest req, String json) {
        if (req == null) {
            return "bytes=" + (json == null ? 0 : json.length());
        }
        String model = req.getModel() == null ? "-" : req.getModel();
        boolean hasKey = req.getApiKey() != null && !req.getApiKey().isBlank();
        boolean hasSearchKey = req.getSearchApiKey() != null && !req.getSearchApiKey().isBlank();
        int history = req.getChatHistory() == null ? 0 : req.getChatHistory().size();
        return "bytes=" + (json == null ? 0 : json.length())
                + " model=" + model
                + " hasApiKey=" + hasKey
                + " hasSearchApiKey=" + hasSearchKey
                + " chatHistory=" + history;
    }

    /**
     * 运行时验证 BuildingSpec 的关键字段
     * @param spec 要验证的 BuildingSpec
     * @throws RuntimeException 如果验证失败
     */
    private static void validateBuildingSpec(BuildingSpec spec) {
        if (spec == null) {
            throw new RuntimeException("BuildingSpec is null");
        }
        
        // 验证类型
        if (spec.getType() == null) {
            throw new RuntimeException("BuildingSpec.type is null");
        }
        
        // 验证 footprint
        if (spec.getFootprint() == null) {
            throw new RuntimeException("BuildingSpec.footprint is null");
        }
        var fp = spec.getFootprint();
        if (fp.getShape() == null || fp.getShape().isBlank()) {
            throw new RuntimeException("BuildingSpec.footprint.shape is null or empty");
        }
        
        // 验证高度和楼层（合理性检查）
        if (spec.getHeight() <= 0) {
            FormacraftMod.LOGGER.warn("BuildingSpec.height is {} (<=0), clamping to 1", spec.getHeight());
            // 注意：不能直接修改 spec，因为可能是不可变的。这里只记录警告。
        }
        if (spec.getFloors() < 0) {
            FormacraftMod.LOGGER.warn("BuildingSpec.floors is {} (<0), should be >= 0", spec.getFloors());
        }
        
        // 验证尺寸合理性（避免生成过大的建筑）
        int maxDimension = 200; // 最大尺寸（可根据需要调整）
        Integer width = fp.getWidth();
        if (width != null && width > maxDimension) {
            FormacraftMod.LOGGER.warn("BuildingSpec.footprint.width is {} (>{})", width, maxDimension);
        }
        Integer depth = fp.getDepth();
        if (depth != null && depth > maxDimension) {
            FormacraftMod.LOGGER.warn("BuildingSpec.footprint.depth is {} (>{})", depth, maxDimension);
        }
        int height = spec.getHeight();
        if (height > maxDimension) {
            FormacraftMod.LOGGER.warn("BuildingSpec.height is {} (>{})", height, maxDimension);
        }
    }

    /**
     * 向 Python 后端发送建筑请求，按响应形态返回类型安全的 {@link AiPlanResult}。
     */
    public CompletableFuture<AiPlanResult> requestAiPlan(FormaRequest req) {
        return requestAiPlan(req, phase -> {});
    }

    public CompletableFuture<AiPlanResult> requestAiPlan(FormaRequest req, java.util.function.Consumer<String> progress) {
        try {
            String json = JsonUtil.toJson(req);
            FormacraftMod.LOGGER.info("Sending request to orchestrator ({})", requestLogSummary(req, json));
            FormacraftMod.LOGGER.debug("Orchestrator request body: {}", redactSecretsForLog(json));

            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(endpoint + "/build"))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(ORCHESTRATOR_TIMEOUT_SEC))
                    .POST(HttpRequest.BodyPublishers.ofString(json));
            applySecret(builder);
            HttpRequest request = builder.build();

            long t0 = System.nanoTime();
            return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                    .orTimeout(ORCHESTRATOR_TIMEOUT_SEC + 5, TimeUnit.SECONDS)
                    .thenApply(resp -> {
                        long elapsedMs = (System.nanoTime() - t0) / 1_000_000L;
                        FormacraftMod.LOGGER.info("Orchestrator /build round-trip took {} ms (status={})", elapsedMs, resp.statusCode());
                        if (resp.statusCode() >= 200 && resp.statusCode() < 300) {
                            String body = resp.body();
                            FormacraftMod.LOGGER.info("Received response from orchestrator: {}", body);
                            try {
                                return parseAiPlanResponse(body);
                            } catch (Exception e) {
                                FormacraftMod.LOGGER.error("Failed to parse response from orchestrator", e);
                                throw new RuntimeException("Failed to parse response", e);
                            }
                        } else {
                            String body = resp.body();
                            FormacraftMod.LOGGER.error("Orchestrator returned error status: {} body={}", resp.statusCode(), body);
                            throw new RuntimeException("Orchestrator returned status: " + resp.statusCode() + " body=" + body);
                        }
                    }).thenCompose(result -> {
                        if (result instanceof AiPlanResult.LlmPlan llm && com.formacraft.server.dsl.FbdRepairLoop.hasProgram(llm.plan()))
                            return com.formacraft.server.dsl.FbdRepairLoop.run(llm.plan(),
                                    (plan, diagnostic, round) -> requestFbdRepair(req, plan, diagnostic, round), progress)
                                    .thenApply(repaired -> {
                                        FormacraftMod.LOGGER.info("FBD validation complete: repairAttempts={}, valid={}",
                                                repaired.attempts(), !repaired.plan().hasCapabilityGap());
                                        return (AiPlanResult) new AiPlanResult.LlmPlan(repaired.plan());
                                    });
                        return CompletableFuture.completedFuture(result);
                    });
        } catch (Exception e) {
            FormacraftMod.LOGGER.error("Failed to create HTTP request", e);
            return CompletableFuture.failedFuture(e);
        }
    }

    private CompletableFuture<com.formacraft.server.dsl.FbdRepairLoop.Patch> requestFbdRepair(
            FormaRequest req, LlmPlan plan, com.formacraft.common.llm.dto.CapabilityGap diagnostic, int round) {
        try {
            var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            var body = mapper.createObjectNode();
            body.set("request", mapper.readTree(JsonUtil.toJson(req)));
            body.set("plan", mapper.valueToTree(plan)); body.set("diagnostic", mapper.valueToTree(diagnostic));
            body.put("round", round);
            var repairBuilder = HttpRequest.newBuilder().uri(URI.create(endpoint + "/fbd/repair"))
                    .header("Content-Type", "application/json").timeout(Duration.ofSeconds(50))
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)));
            applySecret(repairBuilder);
            var request = repairBuilder.build();
            FormacraftMod.LOGGER.info("FBD repair round {}/2: code={}, node={}, path={}", round, diagnostic.code(),
                    diagnostic.details() == null ? null : diagnostic.details().get("node"), diagnostic.path());
            return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                    .orTimeout(50, TimeUnit.SECONDS).thenApply(response -> {
                if (response.statusCode() != 200 || response.body().length() > 16_384)
                    throw new IllegalArgumentException("FBD repair unavailable or response too large");
                try { return mapper.readValue(response.body(), com.formacraft.server.dsl.FbdRepairLoop.Patch.class); }
                catch (Exception e) { throw new IllegalArgumentException("Invalid FBD repair patch", e); }
            });
        } catch (Exception e) { return CompletableFuture.failedFuture(e); }
    }

    /**
     * 按后端显式返回的 {@code kind} 判别字段分发（不再靠 body.contains(...) 的脆弱字符串启发式）。
     * 后端 /build 必须在响应中输出 {@code "kind": "llmplan|city|composite|buildingspec|clarification"}。
     */
    static AiPlanResult parseAiPlanResponse(String body) {
        if (body == null || body.isBlank()) {
            throw new RuntimeException("Orchestrator returned empty body");
        }

        String kind;
        try {
            com.google.gson.JsonObject obj = JsonUtil.get().fromJson(body, com.google.gson.JsonObject.class);
            kind = (obj != null && obj.has("kind") && !obj.get("kind").isJsonNull())
                    ? obj.get("kind").getAsString()
                    : null;
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse orchestrator response as JSON. body=" + body, e);
        }

        if (kind == null || kind.isBlank()) {
            throw new RuntimeException(
                    "Orchestrator response missing 'kind' discriminator. "
                            + "请更新 Python 后端（/build 需在响应中输出 kind 字段）。body=" + body);
        }

        return switch (kind) {
            case "llmplan" -> {
                try {
                    LlmPlan llmPlan = LlmPlanParser.parseAndValidate(body);
                    FormacraftMod.LOGGER.info("Received LlmPlan from orchestrator, mode: {}", llmPlan.mode());
                    yield new AiPlanResult.LlmPlan(llmPlan);
                } catch (PlanParseException e) {
                    throw new RuntimeException(
                            "Response kind=llmplan but failed to parse "
                                    + "(可能是枚举值不匹配，如 TerrainStrategy)。", e);
                }
            }
            case "city" -> {
                CitySpec city = JsonUtil.fromJson(body, CitySpec.class);
                if (city == null) {
                    throw new RuntimeException("kind=city but deserialized to null. body=" + body);
                }
                validateCitySpec(city);
                yield new AiPlanResult.CitySpec(city);
            }
            case "composite" -> {
                CompositeSpec composite = JsonUtil.fromJson(body, CompositeSpec.class);
                if (composite == null) {
                    throw new RuntimeException("kind=composite but deserialized to null. body=" + body);
                }
                validateCompositeSpec(composite);
                yield new AiPlanResult.CompositeSpec(composite);
            }
            case "buildingspec" -> {
                BuildingSpec spec = JsonUtil.fromJson(body, BuildingSpec.class);
                if (spec == null) {
                    throw new RuntimeException("kind=buildingspec but deserialized to null. body=" + body);
                }
                validateBuildingSpec(spec);
                yield new AiPlanResult.BuildingSpec(spec);
            }
            case "clarification" -> {
                ClarificationResponse clar = parseClarificationResponse(body);
                yield new AiPlanResult.Clarification(clar);
            }
            default -> throw new RuntimeException("Unknown orchestrator response kind='" + kind + "'. body=" + body);
        };
    }

    private static ClarificationResponse parseClarificationResponse(String body) {
        try {
            com.google.gson.JsonObject obj = JsonUtil.get().fromJson(body, com.google.gson.JsonObject.class);
            if (obj == null || !obj.has("clarification") || obj.get("clarification").isJsonNull()) {
                throw new RuntimeException("kind=clarification but missing 'clarification' object. body=" + body);
            }
            com.google.gson.JsonObject clar = obj.getAsJsonObject("clarification");
            String messageZh = clar.has("message_zh") && !clar.get("message_zh").isJsonNull()
                    ? clar.get("message_zh").getAsString()
                    : "";
            String reason = clar.has("reason") && !clar.get("reason").isJsonNull()
                    ? clar.get("reason").getAsString()
                    : null;
            String sessionId = clar.has("session_id") && !clar.get("session_id").isJsonNull()
                    ? clar.get("session_id").getAsString()
                    : null;
            java.util.List<String> questions = new java.util.ArrayList<>();
            if (clar.has("questions") && clar.get("questions").isJsonArray()) {
                clar.getAsJsonArray("questions").forEach(el -> {
                    if (el != null && !el.isJsonNull()) {
                        questions.add(el.getAsString());
                    }
                });
            }
            if (messageZh == null || messageZh.isBlank()) {
                throw new RuntimeException("kind=clarification but message_zh is empty. body=" + body);
            }
            return new ClarificationResponse(messageZh, questions, reason, sessionId);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse clarification response. body=" + body, e);
        }
    }

    /**
     * 增量编辑城市规格
     * @param cityId 城市 ID
     * @param currentJson 当前 CitySpec 的 JSON 字符串
     * @param editCommand 编辑指令（自然语言）
     * @return CompletableFuture<String> 更新后的 CitySpec JSON
     */
    public CompletableFuture<String> editCity(String cityId, String currentJson, String editCommand) {
        try {
            // 构建请求体
            java.util.Map<String, Object> requestBody = new java.util.HashMap<>();
            requestBody.put("cityId", cityId);
            
            // 解析 currentJson 为 Map
            @SuppressWarnings("unchecked")
            java.util.Map<String, Object> citySpecMap = (java.util.Map<String, Object>) 
                    com.formacraft.common.json.JsonUtil.get().fromJson(currentJson, java.util.Map.class);
            requestBody.put("currentCitySpec", citySpecMap);
            requestBody.put("editCommand", editCommand);
            requestBody.put("context", new java.util.HashMap<>());
            
            String requestJson = com.formacraft.common.json.JsonUtil.toJson(requestBody);
            FormacraftMod.LOGGER.info("Sending city edit request to orchestrator: {}", editCommand);

            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(endpoint + "/edit/city"))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(ORCHESTRATOR_TIMEOUT_SEC))
                    .POST(HttpRequest.BodyPublishers.ofString(requestJson));
            applySecret(builder);
            HttpRequest request = builder.build();

            return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                    .orTimeout(ORCHESTRATOR_TIMEOUT_SEC + 5, TimeUnit.SECONDS)
                    .thenApply(resp -> {
                        if (resp.statusCode() >= 200 && resp.statusCode() < 300) {
                            String body = resp.body();
                            FormacraftMod.LOGGER.info("Received city edit response from orchestrator");
                            try {
                                // 解析响应，提取 updatedCitySpec
                                java.util.Map<String, Object> response = JsonUtil.get().fromJson(body,
                                new com.google.gson.reflect.TypeToken<java.util.Map<String, Object>>(){}.getType());
                                @SuppressWarnings("unchecked")
                                java.util.Map<String, Object> updatedSpec = (java.util.Map<String, Object>) response.get("updatedCitySpec");
                                return com.formacraft.common.json.JsonUtil.toJson(updatedSpec);
                            } catch (Exception e) {
                                FormacraftMod.LOGGER.error("Failed to parse city edit response", e);
                                throw new RuntimeException("Failed to parse city edit response", e);
                            }
                        } else {
                            String body = resp.body();
                            FormacraftMod.LOGGER.error("Orchestrator returned error status: {} body={}", resp.statusCode(), body);
                            throw new RuntimeException("Orchestrator returned status: " + resp.statusCode() + " body=" + body);
                        }
                    });
        } catch (Exception e) {
            FormacraftMod.LOGGER.error("Failed to create HTTP request for city edit", e);
            return CompletableFuture.failedFuture(e);
        }
    }

    /**
     * 增量编辑建筑规格
     * @param buildingId 建筑 ID
     * @param currentJson 当前 BuildingSpec 的 JSON 字符串
     * @param editCommand 编辑指令（自然语言）
     * @return CompletableFuture<String> 更新后的 BuildingSpec JSON
     */
    public CompletableFuture<String> editBuilding(String buildingId, String currentJson, String editCommand) {
        try {
            // 构建请求体
            java.util.Map<String, Object> requestBody = new java.util.HashMap<>();
            requestBody.put("buildingId", buildingId);
            
            // 解析 currentJson 为 Map
            java.util.Map<String, Object> buildingSpecMap = JsonUtil.get().fromJson(currentJson,
            new com.google.gson.reflect.TypeToken<java.util.Map<String, Object>>(){}.getType());
            requestBody.put("currentBuildingSpec", buildingSpecMap);
            requestBody.put("editCommand", editCommand);
            requestBody.put("context", new java.util.HashMap<>());
            
            String requestJson = com.formacraft.common.json.JsonUtil.toJson(requestBody);
            FormacraftMod.LOGGER.info("Sending building edit request to orchestrator: {}", editCommand);

            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(endpoint + "/edit/building"))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(ORCHESTRATOR_TIMEOUT_SEC))
                    .POST(HttpRequest.BodyPublishers.ofString(requestJson));
            applySecret(builder);
            HttpRequest request = builder.build();

            return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                    .orTimeout(ORCHESTRATOR_TIMEOUT_SEC + 5, TimeUnit.SECONDS)
                    .thenApply(resp -> {
                        if (resp.statusCode() >= 200 && resp.statusCode() < 300) {
                            String body = resp.body();
                            FormacraftMod.LOGGER.info("Received building edit response from orchestrator");
                            try {
                                // 解析响应，提取 updatedBuildingSpec
                                java.util.Map<String, Object> response = JsonUtil.get().fromJson(body,
                                new com.google.gson.reflect.TypeToken<java.util.Map<String, Object>>(){}.getType());
                                @SuppressWarnings("unchecked")
                                java.util.Map<String, Object> updatedSpec = (java.util.Map<String, Object>) response.get("updatedBuildingSpec");
                                return com.formacraft.common.json.JsonUtil.toJson(updatedSpec);
                            } catch (Exception e) {
                                FormacraftMod.LOGGER.error("Failed to parse building edit response", e);
                                throw new RuntimeException("Failed to parse building edit response", e);
                            }
                        } else {
                            String body = resp.body();
                            FormacraftMod.LOGGER.error("Orchestrator returned error status: {} body={}", resp.statusCode(), body);
                            throw new RuntimeException("Orchestrator returned status: " + resp.statusCode() + " body=" + body);
                        }
                    });
        } catch (Exception e) {
            FormacraftMod.LOGGER.error("Failed to create HTTP request for building edit", e);
            return CompletableFuture.failedFuture(e);
        }
    }

    /**
     * 运行时验证 CompositeSpec 的关键字段
     */
    private static void validateCompositeSpec(CompositeSpec spec) {
        if (spec == null) {
            throw new RuntimeException("CompositeSpec is null");
        }
        if (spec.getStructures() == null || spec.getStructures().isEmpty()) {
            FormacraftMod.LOGGER.warn("CompositeSpec.structures is null or empty");
        }
    }

    /**
     * 运行时验证 CitySpec 的关键字段
     */
    private static void validateCitySpec(CitySpec spec) {
        if (spec == null) {
            throw new RuntimeException("CitySpec is null");
        }
        if (spec.getZones() == null || spec.getZones().isEmpty()) {
            FormacraftMod.LOGGER.warn("CitySpec.zones is null or empty");
        }
    }
}
