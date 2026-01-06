package com.formacraft.common.util;

import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3i;

/**
 * 朝向工具类
 * 
 * 提供朝向相关的方向向量计算
 */
public final class FacingUtil {
    private FacingUtil() {}

    /**
     * 获取朝向的前进方向向量
     */
    public static Vec3i forward(Direction dir) {
        return dir.getVector();
    }

    /**
     * 获取朝向的右侧方向向量
     */
    public static Vec3i right(Direction dir) {
        return dir.rotateYClockwise().getVector();
    }
    
    /**
     * 获取朝向的左侧方向向量
     */
    public static Vec3i left(Direction dir) {
        return dir.rotateYCounterclockwise().getVector();
    }
    
    /**
     * 获取朝向的后方方向向量
     */
    public static Vec3i backward(Direction dir) {
        return dir.getOpposite().getVector();
    }
}

