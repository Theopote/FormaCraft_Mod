package com.formacraft.common.model.request;

import net.minecraft.util.math.BlockPos;

/**
 * 玩家请求数据结构（Minecraft → Python）
 * 发送到后端的请求结构
 */
public class FormaRequest {
    private String requestText;
    private BlockPos playerPos;
    private String facing;
    private String dimension;
    private String biome;
    private BlockPos selectionMin;
    private BlockPos selectionMax;
    private String sessionId;
    private java.util.List<String> chatHistory;

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
}

