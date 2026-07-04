package com.formacraft.common.model.request;

import net.minecraft.util.math.BlockPos;
import com.formacraft.common.buildcontext.OutlineShape;
import com.formacraft.common.model.constraint.ProtectedZone;

/**
 * 玩家请求数据结构（Minecraft → Python）
 * 发送到后端的请求结构
 */
public class FormaRequest {
    private String requestText;
    /** 可选：玩家原始输入（不含系统拼接），用于 PATCH/编辑模式 */
    private String userMessage;
    /** 可选：BUILD/PATCH/MODIFY_REGION */
    private String promptMode;
    /** 
     * 可选：输出格式（"llmplan" | "buildingspec" | "auto"）
     * - "llmplan": 强制使用 LlmPlan 格式
     * - "buildingspec": 强制使用 BuildingSpec 格式
     * - "auto": 自动决定（默认，基于 promptMode 和 requestText）
     */
    private String outputFormat;
    private BlockPos playerPos;
    private String facing;
    private String dimension;
    private String biome;
    private BlockPos selectionMin;
    private BlockPos selectionMax;
    /** 可选：笔刷选中区域边界（AABB），用于服务端生成阶段约束 */
    private BlockPos brushMin;
    private BlockPos brushMax;
    /** 可选：轮廓/Footprint（用于服务端生成阶段硬裁剪；与客户端 OutlineTool 同源） */
    private OutlineShape outline;
    /** 可选：禁区/保护区（用于服务端生成阶段硬裁剪；与客户端 ProtectedZoneTool 同源） */
    private java.util.List<ProtectedZone> protectedZones;
    /** 可选：路径（PathTool）节点，世界坐标；用于服务端"走廊"硬裁剪与沿线生成（Phase 9） */
    private java.util.List<BlockPos> pathNodes;
    /** 可选：路径走廊半径（水平方块数）；<=0 视为未设置 */
    private Integer pathRadius;
    private String sessionId;
    private java.util.List<String> chatHistory;

    /** PR-4: 参考图 / 网页链接 */
    private java.util.List<ReferenceInput> references;

    // LLM 覆盖配置（从客户端 Settings 传入，优先于后端环境变量）
    private String apiKey;
    private String model;
    private Float temperature;
    /** auto / deepseek / openai / openai_compat / ollama ... */
    private String llmProvider;
    /** OpenAI-compatible base URL，例如 https://api.deepseek.com/v1 */
    private String llmBaseUrl;

    public FormaRequest() {
        this.chatHistory = java.util.Collections.emptyList();
        this.protectedZones = java.util.Collections.emptyList();
        this.references = java.util.Collections.emptyList();
    }

    public FormaRequest(String requestText, BlockPos playerPos, String facing, String dimension,
                        String biome, BlockPos selectionMin, BlockPos selectionMax) {
        this.requestText = requestText;
        this.playerPos = playerPos;
        this.facing = facing;
        this.dimension = dimension;
        this.biome = biome;
        this.selectionMin = selectionMin;
        this.selectionMax = selectionMax;
        this.protectedZones = java.util.Collections.emptyList();
        this.chatHistory = java.util.Collections.emptyList();
    }

    // Getters and Setters
    public String getRequestText() {
        return requestText;
    }

    public void setRequestText(String requestText) {
        this.requestText = requestText;
    }

    public String getUserMessage() {
        return userMessage;
    }

    public void setUserMessage(String userMessage) {
        this.userMessage = userMessage;
    }

    public String getPromptMode() {
        return promptMode;
    }

    public void setPromptMode(String promptMode) {
        this.promptMode = promptMode;
    }

    public BlockPos getPlayerPos() {
        return playerPos;
    }

    public void setPlayerPos(BlockPos playerPos) {
        this.playerPos = playerPos;
    }

    public String getFacing() {
        return facing;
    }

    public void setFacing(String facing) {
        this.facing = facing;
    }

    public String getDimension() {
        return dimension;
    }

    public void setDimension(String dimension) {
        this.dimension = dimension;
    }

    public String getBiome() {
        return biome;
    }

    public void setBiome(String biome) {
        this.biome = biome;
    }

    public BlockPos getSelectionMin() {
        return selectionMin;
    }

    public void setSelectionMin(BlockPos selectionMin) {
        this.selectionMin = selectionMin;
    }

    public BlockPos getSelectionMax() {
        return selectionMax;
    }

    public void setSelectionMax(BlockPos selectionMax) {
        this.selectionMax = selectionMax;
    }

    public BlockPos getBrushMin() {
        return brushMin;
    }

    public void setBrushMin(BlockPos brushMin) {
        this.brushMin = brushMin;
    }

    public BlockPos getBrushMax() {
        return brushMax;
    }

    public void setBrushMax(BlockPos brushMax) {
        this.brushMax = brushMax;
    }

    public OutlineShape getOutline() {
        return outline;
    }

    public void setOutline(OutlineShape outline) {
        this.outline = outline;
    }

    public java.util.List<ProtectedZone> getProtectedZones() {
        return protectedZones;
    }

    public void setProtectedZones(java.util.List<ProtectedZone> protectedZones) {
        this.protectedZones = protectedZones != null ? protectedZones : java.util.Collections.emptyList();
    }

    public java.util.List<BlockPos> getPathNodes() {
        return pathNodes;
    }

    public void setPathNodes(java.util.List<BlockPos> pathNodes) {
        this.pathNodes = pathNodes;
    }

    public Integer getPathRadius() {
        return pathRadius;
    }

    public void setPathRadius(Integer pathRadius) {
        this.pathRadius = pathRadius;
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public java.util.List<String> getChatHistory() {
        return chatHistory;
    }

    public void setChatHistory(java.util.List<String> chatHistory) {
        this.chatHistory = chatHistory != null ? chatHistory : java.util.Collections.emptyList();
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public Float getTemperature() {
        return temperature;
    }

    public void setTemperature(Float temperature) {
        this.temperature = temperature;
    }

    public String getLlmProvider() {
        return llmProvider;
    }

    public void setLlmProvider(String llmProvider) {
        this.llmProvider = llmProvider;
    }

    public String getLlmBaseUrl() {
        return llmBaseUrl;
    }

    public void setLlmBaseUrl(String llmBaseUrl) {
        this.llmBaseUrl = llmBaseUrl;
    }

    public String getOutputFormat() {
        return outputFormat;
    }

    public void setOutputFormat(String outputFormat) {
        this.outputFormat = outputFormat;
    }

    public java.util.List<ReferenceInput> getReferences() {
        return references;
    }

    public void setReferences(java.util.List<ReferenceInput> references) {
        this.references = references != null ? references : java.util.Collections.emptyList();
    }
}

