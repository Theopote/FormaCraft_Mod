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
    private void onCursorPosHead(long window, double x, double y, CallbackInfo ci) {
        // 重要：InputRouter 使用 GUI scaled 坐标做命中测试（与面板布局一致）
        // onCursorPos 的 x/y 是窗口坐标，这里转换为 scaled 坐标再写入 InputRouter
        if (!FormacraftUIState.isOpen) return;
        double scale = client.getWindow().getScaleFactor();
        InputRouter.updateMouse(x / scale, y / scale);
    }

    @Inject(method = "onCursorPos", at = @At("RETURN"))
    private void onCursorPosReturn(long window, double x, double y, CallbackInfo ci) {
        // 在 onCursorPos 方法执行后，cursorDeltaX 和 cursorDeltaY 已经被计算
        // 现在根据 UI 状态决定是否阻止视角移动
        // 重要：UI 关闭时，完全不修改任何值，让游戏使用默认行为
        
        if (!FormacraftUIState.isOpen) {
            // UI 关闭：完全不影响，让游戏使用默认行为
            return;
        }

        // 命中测试使用 scaled 坐标
        double scale = client.getWindow().getScaleFactor();
        double sx = x / scale;
        double sy = y / scale;
        boolean inside = InputRouter.isMouseInsideUI(sx, sy);

        if (inside) {
            // UI 内：完全阻止视角移动
            cursorDeltaX = 0;
            cursorDeltaY = 0;
        } else {
            // UI 外：只有按住中键时才允许视角移动
            if (middleDown) {
                // 中键按住：允许视角移动，手动处理
                double sens = client.options.getMouseSensitivity().getValue() * 0.6 + 0.2;
                sens *= sens * sens * 8.0;

                if (client.player != null)
                    client.player.changeLookDirection(cursorDeltaX * sens, cursorDeltaY * sens);
                
                // 处理完后清零，防止 Minecraft 默认处理再次移动视角
                cursorDeltaX = 0;
                cursorDeltaY = 0;
            } else {
                // 未按住中键：阻止视角移动
                cursorDeltaX = 0;
                cursorDeltaY = 0;
            }
        }
    }

    @Inject(method = "onMouseButton", at = @At("HEAD"), cancellable = true)
    private void onMouseButton(long window, MouseInput mouseInput, int action, CallbackInfo ci) {
        // 重要：UI 关闭时，完全不处理，让游戏使用默认行为
        if (!FormacraftUIState.isOpen) {
            // UI 关闭时重置中键状态，确保不影响游戏
            middleDown = false;
            return;
        }

        int button = mouseInput.button();
        
        // 重要：先获取最新的鼠标位置（从窗口坐标转换为scaled坐标）
        double rawMouseX = client.mouse.getX();
        double rawMouseY = client.mouse.getY();
        double mouseX = rawMouseX / client.getWindow().getScaleFactor();
        double mouseY = rawMouseY / client.getWindow().getScaleFactor();
        
        // 更新 InputRouter 的鼠标位置（确保是最新的）
        InputRouter.updateMouse(mouseX, mouseY);

        // 先尝试让 UI 处理点击事件
        // 如果 UI 处理了（返回true），说明点击的是UI元素，必须cancel防止游戏处理
        boolean uiHandled = InputRouter.onMouseClick(mouseX, mouseY, button, action);
        if (uiHandled) {
            // UI 已处理，完全阻止游戏处理
            ci.cancel();
            return;
        }

        // UI 未处理（点击在UI外）：允许游戏处理鼠标按钮
        // 但要跟踪中键按住状态用于"按住中键转视角"
        if (button == 2) {
            middleDown = (action == 1);
        }
    }

    @Inject(method = "onMouseScroll", at = @At("HEAD"), cancellable = true)
    private void onMouseScroll(long window, double horizontalAmount, double verticalAmount, CallbackInfo ci) {
        // 重要：UI 关闭时，完全不处理，让游戏使用默认行为
        if (!FormacraftUIState.isOpen) {
            return;
        }

        double mouseX = InputRouter.getMouseX();
        double mouseY = InputRouter.getMouseY();

        boolean inside = InputRouter.isMouseInsideUI(mouseX, mouseY);
        if (!inside) return; // UI 外：不拦截，交给游戏

        // UI 内：滚轮仅操作 UI，必须屏蔽游戏（不依赖 consumed）
        InputRouter.onMouseScroll(mouseX, mouseY, verticalAmount);
        ci.cancel();
    }
}
