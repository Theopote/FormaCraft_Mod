package com.formacraft.common.model.request;

import net.minecraft.util.math.BlockPos;

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
    private BlockPos playerPos;
    private String facing;
    private String dimension;
    private String biome;
    private BlockPos selectionMin;
    private BlockPos selectionMax;
    private String sessionId;
    private java.util.List<String> chatHistory;

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
}

