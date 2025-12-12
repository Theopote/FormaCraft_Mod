package com.formacraft.mixin;

import com.formacraft.client.ui.input.InputRouter;
import com.formacraft.client.ui.FormacraftUIState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.Mouse;
import net.minecraft.client.input.MouseInput;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Mouse.class)
public class MouseMixin {

    @Shadow @Final
    private MinecraftClient client;

    @Shadow private double cursorDeltaX;
    @Shadow private double cursorDeltaY;

    @Unique private boolean middleDown = false;

    @Inject(method = "onCursorPos", at = @At("HEAD"))
    private void onCursorPos(long window, double x, double y, CallbackInfo ci) {
        InputRouter.updateMouse(x, y);

        if (!FormacraftUIState.isOpen) return;

        boolean inside = InputRouter.isMouseInsideUI(x, y);

        // UI 外且中键按住 → 允许视角移动
        if (!inside && middleDown) {
            double sens = client.options.getMouseSensitivity().getValue() * 0.6 + 0.2;
            sens *= sens * sens * 8.0;

            if (client.player != null)
                client.player.changeLookDirection(cursorDeltaX * sens, cursorDeltaY * sens);
        }
    }

    @Inject(method = "onMouseButton", at = @At("HEAD"), cancellable = true)
    private void onMouseButton(long window, MouseInput mouseInput, int action, CallbackInfo ci) {
        int button = mouseInput.button();

        if (button == 2) middleDown = (action == 1);

        if (!FormacraftUIState.isOpen) return;

        boolean consumed = InputRouter.onMouseClick(
                InputRouter.getMouseX(), InputRouter.getMouseY(),
                button, action
        );

        if (consumed) ci.cancel();
    }

    @Inject(method = "onMouseScroll", at = @At("HEAD"), cancellable = true)
    private void onMouseScroll(long window, double horizontalAmount, double verticalAmount, CallbackInfo ci) {
        if (!FormacraftUIState.isOpen) return;

        // 使用垂直滚动量（verticalAmount），这是主要的滚动方向
        boolean consumed = InputRouter.onMouseScroll(
                InputRouter.getMouseX(), InputRouter.getMouseY(), verticalAmount
        );

        if (consumed) ci.cancel();
    }
}
