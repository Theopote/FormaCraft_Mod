package com.formacraft.ai;

import com.formacraft.common.lang.StructureData;

public class AIResult {
    private final String rawResponse;
    private final StructureData structureData;

    public AIResult(String rawResponse, StructureData structureData) {
        this.rawResponse = rawResponse;
        this.structureData = structureData;
    }

    public String getRawResponse() {
        return rawResponse;
    }

    public StructureData getStructureData() {
        return structureData;
    }
}
