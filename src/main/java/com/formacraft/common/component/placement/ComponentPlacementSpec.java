package com.formacraft.common.component.placement;

import java.util.HashSet;
import java.util.Set;

/**
 * ComponentPlacementSpec v1：构件放置规格（面向 AI / 工具 / 生成器）。
 */
public class ComponentPlacementSpec {
    public AttachmentType attachment = AttachmentType.NONE;
    public SpatialContext spatialContext = SpatialContext.ANY;
    public FacingPolicy facingPolicy = FacingPolicy.NONE;

    public PlacementConstraints constraints = new PlacementConstraints();

    /** 给 AI/Prompt 的语义标签（例如 entry/circulation/roof/ornament） */
    public Set<String> semanticTags = new HashSet<>();

    /** 是否存在“内外之分”（门/窗洞口等） */
    public boolean hasInteriorExterior = false;

    /** 可选：给 AI 的提示（短句） */
    public String aiHint = null;
}

