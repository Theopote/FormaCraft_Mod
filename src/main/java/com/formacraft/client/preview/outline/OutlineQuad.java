package com.formacraft.client.preview.outline;

import net.minecraft.util.math.Direction;

/**
 * 合并后的外轮廓面片（单位网格上的矩形）。
 *
 * - dir: 面法线方向
 * - d:   所在平面坐标（沿 dir.axis 的绝对坐标）
 * - [u0,u1) x [v0,v1): 在该平面上的矩形区域（u/v 为另外两个轴的坐标）
 */
public record OutlineQuad(Direction dir, int d, int u0, int v0, int u1, int v1) {}

