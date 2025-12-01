package com.formacraft.ai;

import net.minecraft.util.math.BlockPos;
import java.util.List;
import java.util.Collections;

public class BuildingRequest {
    private final String prompt;
    private final BlockPos origin;
    private final String dimensionId;
    private final String sessionId;
    private final List<String> chatHistory;

    public BuildingRequest(String prompt, BlockPos origin, String dimensionId) {
        this(prompt, origin, dimensionId, null, null);
    }

    public BuildingRequest(String prompt, BlockPos origin, String dimensionId, String sessionId, List<String> chatHistory) {
        this.prompt = prompt;
        this.origin = origin;
        this.dimensionId = dimensionId;
        this.sessionId = sessionId;
        this.chatHistory = chatHistory != null ? chatHistory : Collections.emptyList();
    }

    public String getPrompt() {
        return prompt;
    }

    public BlockPos getOrigin() {
        return origin;
    }

    public String getDimensionId() {
        return dimensionId;
    }

    public String getSessionId() {
        return sessionId;
    }

    public List<String> getChatHistory() {
        return chatHistory;
    }
}
