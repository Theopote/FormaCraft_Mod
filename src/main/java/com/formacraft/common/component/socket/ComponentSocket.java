package com.formacraft.common.component.socket;

/**
 * ComponentSocket（构件插槽）：
 * - 定义在“承载构件”（如墙体/外壳）上
 * - 用于安装门/窗/装饰，并提供标准化的清洞体积
 *
 * 坐标：相对 ComponentDefinition.anchor 的局部坐标（dx/dy/dz）
 */
public record ComponentSocket(
        String id,
        SocketType type,
        int x,
        int y,
        int z,
        String facing, // "NORTH|SOUTH|EAST|WEST"
        int width,
        int height,
        int depth
) {}

