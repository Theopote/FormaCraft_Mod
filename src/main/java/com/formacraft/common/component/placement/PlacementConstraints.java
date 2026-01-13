package com.formacraft.common.component.placement;

/**
 * PlacementConstraints：几何/拓扑/语义约束（v1）。
 *
 * 说明：
 * - v1 先作为“声明式约束”给 Prompt/工具/后续过滤器使用；
 * - 是否强制执行，可逐步接入（例如：requiresEdge / requiresSupportBelow 等）。
 */
public class PlacementConstraints {

    /** true：必须贴附（墙/屋面/边缘等） */
    public boolean requiresAttachment = false;

    /** 附着面数量范围（如阳台：1~2 个外墙面） */
    public int minAttachments = 0;
    public int maxAttachments = 1;

    /** 是否要求下方承重/支撑（柱子/斗拱） */
    public boolean requiresSupportBelow = false;

    /** 是否必须位于边缘（栏杆/护栏） */
    public boolean requiresEdge = false;

    /** 是否禁止侵入室内（外部构件） */
    public boolean forbidInterior = false;

    /** 是否必须尊重 protected zones（默认 true） */
    public boolean respectProtectedZones = true;

    /** 是否偏好连续（栏杆/城墙） */
    public boolean prefersContinuity = false;

    public Integer minHeight = null;
    public Integer maxHeight = null;
}

