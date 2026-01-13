package com.formacraft.common.component.group;

import com.formacraft.common.component.transform.Mirror;
import com.formacraft.common.skeleton.transform.YRotation;

/**
 * GroupComponentEntry：Group 内部子构件布局条目（相对 group 原点的局部坐标）。
 * <p>
 * 坐标约定：offsetX/offsetZ 为 group 局部平面坐标（以 SOUTH 为“前方”），offsetY 为高度。
 * rotation/mirror 是对子构件的额外局部修正（叠加在 group 的 facing/mirror 之后）。
 */
public record GroupComponentEntry(
        String componentId,
        int offsetX,
        int offsetY,
        int offsetZ,
        YRotation rotation,
        Mirror mirror
) {
    public GroupComponentEntry {
        if (rotation == null) rotation = YRotation.NONE;
        if (mirror == null) mirror = Mirror.NONE;
    }
}

