package com.formacraft.common.component.placement;

import com.formacraft.common.component.socket.SocketType;
import com.formacraft.common.component.socket.AlignmentPolicy;

import java.util.HashSet;
import java.util.Set;

/**
 * ComponentPlacementSpec v1：构件放置规格（面向 AI / 工具 / 生成器）。
 * <p>
 * 现在完整了：Component 如何声明自己"要什么 Socket"。
 */
public class ComponentPlacementSpec {
    public AttachmentType attachment = AttachmentType.NONE;
    public SpatialContext spatialContext = SpatialContext.ANY;
    public FacingPolicy facingPolicy = FacingPolicy.NONE;

    public PlacementConstraints constraints = new PlacementConstraints();

    /** 给 AI/Prompt 的语义标签（例如 entry/circulation/roof/ornament） */
    public Set<String> semanticTags = new HashSet<>();

    /** 是否存在"内外之分"（门/窗洞口等） */
    public boolean hasInteriorExterior = false;

    /** 可选：给 AI 的提示（短句） */
    public String aiHint = null;

    /* ----------------------------
     * Socket System（新增）
     * ---------------------------- */

    /** 可接受的 socket 类型 */
    public Set<SocketType> allowedSockets = new HashSet<>();

    /** 是否必须在外侧 */
    public boolean requireExterior = false;

    /** 是否必须在边缘 */
    public boolean requireEdge = false;

    /** 是否必须嵌入（门 / 窗） */
    public boolean requiresOpening = false;

    /** 是否允许多个 socket（转角阳台） */
    public boolean allowMultiAttach = false;

    /** 对齐策略 */
    public AlignmentPolicy alignment = AlignmentPolicy.CENTER;

    /**
     * 从 AttachmentType 推断 allowedSockets（如果未设置）
     */
    public void inferAllowedSockets() {
        if (allowedSockets != null && !allowedSockets.isEmpty()) {
            return; // 已经设置，不覆盖
        }

        if (allowedSockets == null) {
            allowedSockets = new HashSet<>();
        }

        // 根据 AttachmentType 推断
        switch (attachment) {
            case WALL_OPENING -> {
                allowedSockets.add(SocketType.WALL_OPENING);
                requiresOpening = true;
            }
            case WALL_SURFACE -> {
                allowedSockets.add(SocketType.WALL_SURFACE);
            }
            case EDGE -> {
                allowedSockets.add(SocketType.EDGE_OUTER);
                requireEdge = true;
            }
            case ROOF_SURFACE -> {
                allowedSockets.add(SocketType.ROOF_SLOPE);
            }
            case ROOF_EDGE -> {
                allowedSockets.add(SocketType.ROOF_RIDGE);
            }
            case ROOF_RIDGE -> {
                allowedSockets.add(SocketType.ROOF_RIDGE);
            }
            case FLOOR -> {
                allowedSockets.add(SocketType.FLOOR_SURFACE);
            }
            case CORNER -> {
                allowedSockets.add(SocketType.EDGE_OUTER);
                allowMultiAttach = true; // 转角可能需要多个 socket
            }
            case NONE -> {
                allowedSockets.add(SocketType.FREE_ATTACH);
            }
        }

        // 根据 hasInteriorExterior 设置 requireExterior
        if (hasInteriorExterior && constraints != null && constraints.forbidInterior) {
            requireExterior = true;
        }
    }
}
