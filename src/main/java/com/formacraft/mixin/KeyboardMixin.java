package com.formacraft.mixin;

import com.formacraft.client.ui.input.InputRouter;
import com.formacraft.client.ui.FormacraftUIState;
import net.minecraft.client.Keyboard;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.input.CharInput;
import net.minecraft.client.input.KeyInput;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Keyboard.class)
public class KeyboardMixin {

    @Shadow @Final private MinecraftClient client;

    @Inject(method = "onKey", at = @At("HEAD"), cancellable = true)
    private void onKey(long window, int key, KeyInput keyInput, CallbackInfo ci) {
        if (!FormacraftUIState.isOpen) return;

        // KeyInput record fields: key(), scancode(), modifiers()
        // Note: KeyInput doesn't have action() method, so we process all key events
        // The InputRouter will handle filtering if needed
        boolean consumed = InputRouter.onKeyPressed(key, keyInput.scancode(), keyInput.modifiers());
        if (consumed) ci.cancel();
    }

    @Inject(method = "onChar", at = @At("HEAD"), cancellable = true)
    private void onChar(long window, CharInput charInput, CallbackInfo ci) {
        if (!FormacraftUIState.isOpen) return;

        // CharInput record fields: codepoint(), modifiers()
        boolean consumed = InputRouter.onCharTyped((char) charInput.codepoint(), charInput.modifiers());
        if (consumed) ci.cancel();
    }
}
