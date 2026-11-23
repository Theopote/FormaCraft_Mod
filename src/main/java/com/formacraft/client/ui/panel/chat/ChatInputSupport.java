package com.formacraft.client.ui.panel.chat;

import com.formacraft.client.ai.context.SelectionContext;
import com.formacraft.client.ui.panel.ChatPanel;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.tooltip.Tooltip;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

public final class ChatInputSupport {
    private ChatInputSupport() {}
    public static void initWidgets(ChatPanel host) {

        if (host.sendButton != null) return;

        host.sendButton = ButtonWidget.builder(Text.literal(">"), b -> {
                    host.sendCurrentMessage();
                    host.selectable.clearSelection();
                    host.selectableMsgIndex = -1;
                })
                .dimensions(0, 0, ChatPanel.SEND_BUTTON_SIZE, ChatPanel.SEND_BUTTON_SIZE)
                .tooltip(Tooltip.of(Text.translatable("formacraft.chat.send.tooltip")))
                .build();

        host.stopButton = ButtonWidget.builder(Text.literal("■"), b -> {
                    host.stopGenerating();
                    host.selectable.clearSelection();
                    host.selectableMsgIndex = -1;
                })
                .dimensions(0, 0, ChatPanel.STOP_BUTTON_SIZE, ChatPanel.STOP_BUTTON_SIZE)
                .tooltip(Tooltip.of(Text.translatable("formacraft.chat.stop.tooltip")))
                .build();
        host.referenceButton = ButtonWidget.builder(Text.literal("+"), b -> toggleReferenceImage(host))
                .dimensions(0, 0, ChatPanel.SEND_BUTTON_SIZE, ChatPanel.SEND_BUTTON_SIZE)
                .build();
    
    }

    public static void drawInputArea(ChatPanel host, DrawContext ctx, ChatPanel.ChatLayout layout) {

        int innerX = layout.innerX();
        int innerW = layout.innerW();
        int inputY = layout.inputY();
        int inputAreaHeight = layout.inputAreaHeight();
        int selectionHintHeight = layout.selectionHintHeight();
        ChatPanel.InputBounds bounds = host.computeInputBounds(layout);

        // 背景（更透明）
        ctx.fill(innerX, inputY, innerX + innerW, inputY + inputAreaHeight, 0x401A1A1A);

        initWidgets(host);

        boolean generating = host.currentPrinter != null;

        double mouseX = host.getScaledMouseX();
        double mouseY = host.getScaledMouseY();

        host.sendButton.setPosition(bounds.btnX(), bounds.btnY());
        host.sendButton.visible = true;
        host.sendButton.active = true;
        host.sendButton.render(ctx, (int) mouseX, (int) mouseY, 0.0f);
        host.referenceButton.setPosition(bounds.btnX(), bounds.btnY() - ChatPanel.SEND_BUTTON_SIZE - 1);
        host.referenceButton.setMessage(Text.literal(host.readingReferenceImage ? "…" : hasReferenceImage(host) ? "×" : "+"));
        host.referenceButton.render(ctx, (int) mouseX, (int) mouseY, 0.0f);

        host.stopButton.setPosition(bounds.btnX(), bounds.stopY());
        host.stopButton.visible = generating;
        host.stopButton.active = generating;
        if (generating) {
            host.stopButton.render(ctx, (int) mouseX, (int) mouseY, 0.0f);
        }

        if (selectionHintHeight > 0) {
            String hint = Text.translatable(
                    "formacraft.chat.selection.hint",
                    SelectionContext.sizeX(),
                    SelectionContext.sizeY(),
                    SelectionContext.sizeZ()
            ).getString();
            ctx.drawTextWithShadow(host.client.textRenderer, Text.literal(hint), bounds.inputBoxX(), inputY + 1, 0xFFAAAAAA);
        }

        host.inputBox.setBounds(bounds.inputBoxX(), bounds.inputBoxY(), bounds.inputBoxW(), bounds.inputBoxH());
        ChatPanel.enableScissor(ctx, bounds.inputBoxX(), bounds.inputBoxY(),
                bounds.inputBoxX() + bounds.inputBoxW(), bounds.inputBoxY() + bounds.inputBoxH());
        try {
            host.inputBox.render(ctx);
        } finally {
            ChatPanel.disableScissor(ctx, bounds.inputBoxX(), bounds.inputBoxY(),
                    bounds.inputBoxX() + bounds.inputBoxW(), bounds.inputBoxY() + bounds.inputBoxH());
        }
    
    }

    public static int getInputHeight(ChatPanel host, int selectionHintHeight) {

        int lineHeight = ChatPanel.chatLineHeight();

        int lines = Math.max(1, host.inputBox.getLineCount());
        int visible = Math.max(
                ChatPanel.CHAT_INPUT_MIN_VISIBLE_LINES,
                Math.min(ChatPanel.CHAT_INPUT_MAX_VISIBLE_LINES, lines)
        );

        // 与 MultilineTextInput.render 一致：maxLinesVisible = (height - 8) / lineHeight
        int inputBoxH = ChatPanel.CHAT_INPUT_BOX_VERTICAL_PAD + visible * lineHeight;
        int buttonsHeight = ChatPanel.SEND_BUTTON_SIZE * 2 + ChatPanel.STOP_BUTTON_SIZE + 4;
        return Math.max(buttonsHeight, inputBoxH + 2 + selectionHintHeight);
    
    }

    public static void toggleReferenceImage(ChatPanel host) {

        if (host.readingReferenceImage) return;
        if (hasReferenceImage(host)) {
            host.referenceImageUrl = null;
            host.referenceImageFile = null;
            return;
        }
        String clipboard = host.client.keyboard.getClipboard().strip();
        String selected;
        try {
            selected = org.lwjgl.util.tinyfd.TinyFileDialogs.tinyfd_inputBox(
                    Text.translatable("formacraft.chat.reference.title").getString(),
                    Text.translatable("formacraft.chat.reference.invalid").getString(),
                    isReferenceImageUrl(clipboard) ? clipboard : "");
        } catch (RuntimeException | LinkageError unavailable) {
            host.messages.add(ChatMessage.error(Text.translatable("formacraft.chat.reference.dialog_unavailable").getString()));
            host.scrollOffset = 0;
            return;
        }
        if (selected == null) return;
        String value = selected.strip();
        if (isReferenceImageUrl(value)) {
            int separator = value.indexOf(':');
            host.referenceImageUrl = value.substring(0, separator).toLowerCase(Locale.ROOT) + value.substring(separator);
        } else {
            host.messages.add(ChatMessage.error(Text.translatable("formacraft.chat.reference.invalid").getString()));
            host.scrollOffset = 0;
        }
    
    }

    public static boolean hasReferenceImage(ChatPanel host) {

        return host.referenceImageUrl != null || host.referenceImageFile != null;
    
    }

    public static void chooseReferenceImageFile(ChatPanel host) {

        if (host.readingReferenceImage) return;
        if (!net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.canSend(
                com.formacraft.common.network.ReferenceImageChunk.ID)) {
            host.addAIError(Text.translatable("formacraft.chat.reference.upload_failed").getString());
            return;
        }
        String selected;
        try (var stack = org.lwjgl.system.MemoryStack.stackPush()) {
            var filters = stack.mallocPointer(3);
            filters.put(stack.UTF8("*.png")).put(stack.UTF8("*.jpg")).put(stack.UTF8("*.jpeg")).flip();
            selected = org.lwjgl.util.tinyfd.TinyFileDialogs.tinyfd_openFileDialog(
                    Text.translatable("formacraft.chat.reference.title").getString(), null, filters, "PNG / JPEG", false);
        } catch (RuntimeException | LinkageError unavailable) {
            host.addAIError(Text.translatable("formacraft.chat.reference.dialog_unavailable").getString());
            return;
        }
        if (selected == null) return;
        host.readingReferenceImage = true;
        var connection = host.client.getNetworkHandler();
        CompletableFuture.supplyAsync(() -> {
            try (var input = java.nio.file.Files.newInputStream(java.nio.file.Path.of(selected))) {
                return input.readNBytes(com.formacraft.common.network.ReferenceImageChunk.MAX_BYTES + 1);
            } catch (java.io.IOException | RuntimeException failure) {
                throw new java.util.concurrent.CompletionException(failure);
            }
        }).whenComplete((bytes, failure) -> host.client.execute(() -> {
            host.readingReferenceImage = false;
            if (host.client.getNetworkHandler() != connection) return;
            if (failure != null || bytes.length == 0
                    || bytes.length > com.formacraft.common.network.ReferenceImageChunk.MAX_BYTES
                    || com.formacraft.common.network.ReferenceImageChunk.mimeType(bytes) == null) {
                host.addAIError(Text.translatable("formacraft.chat.reference.file_invalid").getString());
                return;
            }
            host.referenceImageFile = bytes;
            host.referenceImageUrl = null;
        }));
    
    }

    public static boolean isReferenceImageUrl(String value) {

        if (value.length() > com.formacraft.common.model.request.FormaRequestCompactor.MAX_REFERENCE_CONTENT_CHARS) {
            return false;
        }
        try {
            var uri = java.net.URI.create(value);
            return ("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
                    && uri.getHost() != null && uri.getUserInfo() == null;
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    
    }
}
