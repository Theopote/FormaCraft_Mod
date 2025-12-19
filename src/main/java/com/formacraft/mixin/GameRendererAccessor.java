package com.formacraft.mixin;

import net.minecraft.client.render.Camera;
import net.minecraft.client.render.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * 访问 GameRenderer 内部的 FOV 计算（含动态 FOV / 速度影响等）。
 */
@Mixin(GameRenderer.class)
public interface GameRendererAccessor {
    @Invoker("getFov")
    float formacraft$invokeGetFov(Camera camera, float tickDelta, boolean changingFov);
}

