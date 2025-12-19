package com.formacraft.client.tool;

import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.util.math.MatrixStack;

/**
 * Tool 世界渲染上下文（极简封装，避免依赖 Fabric WorldRenderEvents）。
 */
public final class ToolWorldRenderContext {
    public final MatrixStack matrices;
    public final VertexConsumer vertexConsumer;
    public final double cameraX;
    public final double cameraY;
    public final double cameraZ;

    public ToolWorldRenderContext(MatrixStack matrices, VertexConsumer vertexConsumer,
                                  double cameraX, double cameraY, double cameraZ) {
        this.matrices = matrices;
        this.vertexConsumer = vertexConsumer;
        this.cameraX = cameraX;
        this.cameraY = cameraY;
        this.cameraZ = cameraZ;
    }
}

