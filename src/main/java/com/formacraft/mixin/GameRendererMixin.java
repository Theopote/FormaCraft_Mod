package com.formacraft.mixin;

import com.formacraft.client.interaction.CursorRaycastHelper;
import com.formacraft.client.ui.FormacraftUIState;
import com.formacraft.client.ui.input.InputRouter;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.util.hit.HitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 关键 Mixin：把“屏幕中心十字准星 RayCast”替换成“系统光标 RayCast”。
 *
 * 原版会在 GameRenderer#updateCrosshairTarget 中更新 MinecraftClient.crosshairTarget。
 * 我们在 UI 打开时拦截它，改为用 CursorRaycastHelper 的结果写回。
 */
@Mixin(GameRenderer.class)
public class GameRendererMixin {

    @Inject(method = "updateCrosshairTarget(F)V", at = @At("HEAD"), cancellable = true)
    private void formacraft$overrideCrosshairTarget(float tickDelta, CallbackInfo ci) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (!FormacraftUIState.isOpen || client == null || client.currentScreen != null) {
            return;
        }

        // 更新 InputRouter 的鼠标位置（scaled）
        double scale = client.getWindow().getScaleFactor();
        InputRouter.updateMouse(client.mouse.getX() / scale, client.mouse.getY() / scale);

        // 鼠标在面板内：不更新世界目标（避免隔着 UI 选中方块）
        if (InputRouter.isMouseInsideUI()) {
            CursorRaycastHelper.clear();
            client.crosshairTarget = null;
            ci.cancel();
            return;
        }

        // 鼠标在面板外：用光标 RayCast 更新目标
        HitResult hit = CursorRaycastHelper.raycastFromCursor(tickDelta, getReachDistance(client));
        client.crosshairTarget = hit;
        ci.cancel();
    }

    private double getReachDistance(MinecraftClient client) {
        try {
            if (client.player != null) {
                return client.player.getBlockInteractionRange();
            }
        } catch (Throwable ignored) {}
        return 4.5;
    }
}

