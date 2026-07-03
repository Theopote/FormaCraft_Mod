package com.formacraft.common.tool;

/**
 * 对称/镜像约束模式（与 client.tool.SymmetryMode 值一致，供 common/server 使用）。
 */
public enum MirrorSymmetryMode {
    NONE,
    MIRROR_X,
    MIRROR_Z,
    BOTH,
    CUSTOM_AXIS
}
