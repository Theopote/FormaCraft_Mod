package com.formacraft.mixin;

import com.formacraft.client.ui.FormacraftUIState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArgs;
import org.spongepowered.asm.mixin.injection.invoke.arg.Args;
import net.minecraft.client.render.WorldRenderer;

/**
 * 方块选中框颜色调整：
 * - UI 打开时：使用 Formacraft 风格的淡青蓝色作为 hover 边框
 * - UI 关闭时：保持原版颜色
 *
 * 说明：我们用 CursorWorldInteraction 在 tick 中更新 crosshairTarget，
 * 这里仅负责把“原版画框”的颜色改掉，避免自写世界渲染事件的依赖。
 */
@Mixin(WorldRenderer.class)
public class WorldRendererMixin {

    @ModifyArgs(
            method = "drawBlockOutline",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/render/WorldRenderer;drawCuboidShapeOutline(Lnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/VertexConsumer;Lnet/minecraft/util/shape/VoxelShape;DDDFFFF)V"
            ),
            require = 0
    )
    private void formacraft$outlineColor(Args args) {
        if (FormacraftUIState.isOpen) {
            // Formacraft 风格：淡青蓝（更像 3D 建模软件的 hover）
            args.set(6, 0.25f); // red
            args.set(7, 0.90f); // green
            args.set(8, 1.00f); // blue
            args.set(9, 0.85f); // alpha
        }
    }
}

