package com.formacraft.common.llm.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record GlobalConstraints(
        @JsonProperty("facing") Facing facing,
        @JsonProperty("symmetry") Symmetry symmetry,
        @JsonProperty("terrain_strategy") TerrainStrategy terrainStrategy
) {
    public enum Facing { NORTH, SOUTH, EAST, WEST }
    public enum Symmetry { NONE, MIRROR_X, MIRROR_Z, RADIAL }
    public enum TerrainStrategy { 
        PRESERVE,      // 保护地形（不削山、不填谷）
        ADAPTIVE,      // 自适应（默认推荐，单体建筑各自处理底座）
        TERRACE,       // 梯田/台地（把地形离散为几个高度平台）
        FLATTEN        // 强制平整（大范围填平）
    }
}

