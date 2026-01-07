package com.formacraft.server.orchestrator;

import com.formacraft.common.json.JsonUtil;
import com.formacraft.common.llm.dto.LlmPlan;
import com.formacraft.common.llm.parser.LlmPlanParser;
import com.formacraft.common.llm.parser.PlanParseException;
import com.formacraft.common.model.build.BuildingSpec;
import com.formacraft.common.model.city.CitySpec;
import com.formacraft.common.model.composite.CompositeSpec;
import com.formacraft.common.model.request.FormaRequest;
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
    private final HttpClient httpClient;
    // 复合/城市规划类请求可能耗时较长（尤其是 deepseek/reasoner），这里给足时间，避免过早中断。
    // 从配置读取，默认 600 秒
    private static final long ORCHESTRATOR_TIMEOUT_SEC = getTimeoutFromConfig();
    
    private static long getTimeoutFromConfig() {
        // TODO: 从 SettingsConfig 读取（如果将来添加超时配置选项）
        // 目前暂时保持 600 秒的默认值
        return 600;
    }
    
    /**
     * 检查后端健康状态
     * @return true 如果后端可用，false 如果不可用
     */
    public boolean checkHealth() {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(endpoint + "/health"))
                    .timeout(Duration.ofSeconds(5))
                    .GET()
                    .build();
            
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            return response.statusCode() >= 200 && response.statusCode() < 300;
        } catch (Exception e) {
            FormacraftMod.LOGGER.debug("Health check failed for {}: {}", endpoint, e.getMessage());
            return false;
        }
    }

    public OrchestratorClient(String endpoint) {
        this.endpoint = endpoint;
        this.httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .build();
    }

    private static String redactApiKeyForLog(String json) {
        if (json == null || json.isEmpty()) return json;
        // 简单打码：避免在日志里泄露密钥（哪怕是本地开发也不安全）
        // e.g. "apiKey":"sk-..." -> "apiKey":"***"
        return json.replaceAll("(\"apiKey\"\\s*:\\s*\")([^\"]*)(\")", "$1***$3");
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
     * 向 Python 后端发送建筑请求
     * @param req 玩家的建筑请求
     * @return CompletableFuture<BuildingSpec> AI 生成的建筑规格
     */
    public CompletableFuture<BuildingSpec> requestBuildingSpec(FormaRequest req) {
        try {
            String json = JsonUtil.toJson(req);
            FormacraftMod.LOGGER.info("Sending request to orchestrator: {}", redactApiKeyForLog(json));

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(endpoint + "/build"))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(ORCHESTRATOR_TIMEOUT_SEC))
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .build();

            return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                    .orTimeout(ORCHESTRATOR_TIMEOUT_SEC + 5, TimeUnit.SECONDS)
                    .thenApply(resp -> {
                        if (resp.statusCode() >= 200 && resp.statusCode() < 300) {
                            String body = resp.body();
                            FormacraftMod.LOGGER.info("Received response from orchestrator: {}", body);
                            try {
                                // 首先尝试解析为 LlmPlan 格式（Java 端的新格式）
                                // LlmPlan 格式的特征：包含 "mode" 字段，且包含 "components" 或 "layout"
                                if (body.contains("\"mode\"") && (body.contains("\"components\"") || body.contains("\"layout\""))) {
                                    try {
                                        LlmPlan llmPlan = LlmPlanParser.parseAndValidate(body);
                                        FormacraftMod.LOGGER.info("Received LlmPlan from orchestrator, mode: {}", llmPlan.mode());
                                        
                                        // LlmPlan 需要特殊处理：不能直接转换为 BuildingSpec
                                        // 创建一个占位 BuildingSpec，在 extra 中存储 LlmPlan JSON，供后续处理
                                        BuildingSpec placeholder = new BuildingSpec();
                                        placeholder.setType(com.formacraft.common.model.build.BuildingType.CUSTOM);
                                        placeholder.setStyle(com.formacraft.common.model.build.BuildingStyle.DEFAULT);
                                        
                                        // 设置必需的 footprint（使用默认值）
                                        com.formacraft.common.model.build.Footprint defaultFootprint = 
                                                new com.formacraft.common.model.build.Footprint();
                                        defaultFootprint.setShape("rectangle");
                                        defaultFootprint.setWidth(10);
                                        defaultFootprint.setDepth(10);
                                        placeholder.setFootprint(defaultFootprint);
                                        
                                        placeholder.setHeight(10);
                                        placeholder.setFloors(1);
                                        placeholder.setMaterials(new com.formacraft.common.model.build.Materials());
                                        placeholder.setFeatures(new com.formacraft.common.model.build.Features());
                                        
                                        // 在 extra 中存储 LlmPlan JSON 和标志
                                        if (placeholder.getExtra() == null) {
                                            placeholder.setExtra(new java.util.HashMap<>());
                                        }
                                        placeholder.getExtra().put("llmPlanJson", body); // 存储原始 JSON
                                        placeholder.getExtra().put("llmPlanMode", llmPlan.mode().name());
                                        placeholder.getExtra().put("isLlmPlan", true);
                                        
                                        return placeholder;
                                    } catch (PlanParseException e) {
                                        FormacraftMod.LOGGER.warn("Failed to parse as LlmPlan, trying other formats: {}", e.getMessage());
                                        // 继续尝试其他格式
                                    }
                                }
                                
                                // 然后尝试解析为 CompositeSpec
                                if (body.contains("\"structures\"") && body.contains("\"type\"")) {
                                    CompositeSpec composite = JsonUtil.fromJson(body, CompositeSpec.class);
                                    // 如果是 CompositeSpec，转换为单个 BuildingSpec（临时方案）
                                    // 或者直接返回第一个结构
                                    if (composite != null && composite.getStructures() != null && 
                                        !composite.getStructures().isEmpty()) {
                                        // 返回第一个结构的 spec
                                        return composite.getStructures().getFirst().getSpec();
                                    }
                                }
                                // 否则解析为 BuildingSpec
                                BuildingSpec spec = JsonUtil.fromJson(body, BuildingSpec.class);
                                if (spec == null) {
                                    throw new RuntimeException("Orchestrator returned 200 but BuildingSpec is null. body=" + body);
                                }
                                // 运行时验证：检查关键字段
                                validateBuildingSpec(spec);
                                return spec;
                            } catch (Exception e) {
                                FormacraftMod.LOGGER.error("Failed to parse response from orchestrator", e);
                                throw new RuntimeException("Failed to parse response", e);
                            }
                        } else {
                            String body = resp.body();
                            FormacraftMod.LOGGER.error("Orchestrator returned error status: {} body={}", resp.statusCode(), body);
                            throw new RuntimeException("Orchestrator returned status: " + resp.statusCode() + " body=" + body);
                        }
                    });
        } catch (Exception e) {
            FormacraftMod.LOGGER.error("Failed to create HTTP request", e);
            return CompletableFuture.failedFuture(e);
        }
    }

    /**
     * 向 Python 后端发送建筑请求（支持 CompositeSpec）
     * @param req 玩家的建筑请求
     * @return CompletableFuture<CompositeSpec> AI 生成的复合结构规格
     */
    public CompletableFuture<CompositeSpec> requestCompositeSpec(FormaRequest req) {
        try {
            String json = JsonUtil.toJson(req);
            FormacraftMod.LOGGER.info("Sending composite request to orchestrator: {}", redactApiKeyForLog(json));

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(endpoint + "/build"))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(ORCHESTRATOR_TIMEOUT_SEC))
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .build();

            return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                    .orTimeout(ORCHESTRATOR_TIMEOUT_SEC + 5, TimeUnit.SECONDS)
                    .thenApply(resp -> {
                        if (resp.statusCode() >= 200 && resp.statusCode() < 300) {
                            String body = resp.body();
                            FormacraftMod.LOGGER.info("Received composite response from orchestrator: {}", body);
                            try {
                                // 尝试解析为 CompositeSpec
                                if (body.contains("\"structures\"")) {
                                    return JsonUtil.fromJson(body, CompositeSpec.class);
                                } else {
                                    // 如果不是 CompositeSpec，返回 null
                                    FormacraftMod.LOGGER.warn("Response is not a CompositeSpec, contains single BuildingSpec");
                                    return null;
                                }
                            } catch (Exception e) {
                                FormacraftMod.LOGGER.error("Failed to parse CompositeSpec from response", e);
                                throw new RuntimeException("Failed to parse CompositeSpec", e);
                            }
                        } else {
                            String body = resp.body();
                            FormacraftMod.LOGGER.error("Orchestrator returned error status: {} body={}", resp.statusCode(), body);
                            throw new RuntimeException("Orchestrator returned status: " + resp.statusCode() + " body=" + body);
                        }
                    });
        } catch (Exception e) {
            FormacraftMod.LOGGER.error("Failed to create HTTP request", e);
            return CompletableFuture.failedFuture(e);
        }
    }

    /**
     * 向 Python 后端发送城市级建筑请求
     * @param req 玩家的建筑请求
     * @return CompletableFuture<CitySpec> AI 生成的城市规格
     */
    public CompletableFuture<CitySpec> requestCitySpec(FormaRequest req) {
        try {
            String json = JsonUtil.toJson(req);
            FormacraftMod.LOGGER.info("Sending city request to orchestrator: {}", redactApiKeyForLog(json));

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(endpoint + "/build"))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(ORCHESTRATOR_TIMEOUT_SEC))
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .build();

            return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                    .orTimeout(ORCHESTRATOR_TIMEOUT_SEC + 5, TimeUnit.SECONDS)
                    .thenApply(resp -> {
                        if (resp.statusCode() >= 200 && resp.statusCode() < 300) {
                            String body = resp.body();
                            FormacraftMod.LOGGER.info("Received city response from orchestrator: {}", body);
                            try {
                                // 尝试解析为 CitySpec
                                if (body.contains("\"zones\"") || body.contains("\"cityName\"")) {
                                    CitySpec city = JsonUtil.fromJson(body, CitySpec.class);
                                    if (city != null) {
                                        // 验证 CitySpec 的关键字段
                                        validateCitySpec(city);
                                    }
                                    return city;
                                } else {
                                    FormacraftMod.LOGGER.warn("Response is not a CitySpec");
                                    return null;
                                }
                            } catch (Exception e) {
                                FormacraftMod.LOGGER.error("Failed to parse CitySpec from response", e);
                                throw new RuntimeException("Failed to parse CitySpec", e);
                            }
                        } else {
                            String body = resp.body();
                            FormacraftMod.LOGGER.error("Orchestrator returned error status: {} body={}", resp.statusCode(), body);
                            throw new RuntimeException("Orchestrator returned status: " + resp.statusCode() + " body=" + body);
                        }
                    });
        } catch (Exception e) {
            FormacraftMod.LOGGER.error("Failed to create HTTP request", e);
            return CompletableFuture.failedFuture(e);
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

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(endpoint + "/edit/city"))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(ORCHESTRATOR_TIMEOUT_SEC))
                    .POST(HttpRequest.BodyPublishers.ofString(requestJson))
                    .build();

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

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(endpoint + "/edit/building"))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(ORCHESTRATOR_TIMEOUT_SEC))
                    .POST(HttpRequest.BodyPublishers.ofString(requestJson))
                    .build();

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
