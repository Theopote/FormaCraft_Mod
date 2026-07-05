package com.formacraft.client.network;

import com.formacraft.FormacraftMod;
import com.formacraft.common.buildcontext.OutlineShape;
import com.formacraft.common.model.build.BuildingSpec;
import com.formacraft.common.model.constraint.ProtectedZone;
import com.formacraft.common.model.request.FormaRequest;
import com.formacraft.common.network.FormaCraftNetworking;
import com.formacraft.common.preview.OutlineBlock;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.MinecraftClient;
import net.minecraft.network.packet.c2s.common.CustomPayloadC2SPacket;
import net.minecraft.util.math.BlockPos;

import java.util.List;
import java.util.UUID;

/**
 * 客户端 S2C 接收器与 C2S 发送辅助。
 * Payload 定义见 {@link FormaCraftNetworking}。
 */
public final class FormaCraftClientNetworking {
    private FormaCraftClientNetworking() {}

    public static void registerS2C() {
        // 重要：客户端不仅要注册 S2C，还必须注册 C2S payload type，
        // 否则发送自定义 payload 时编码会走 UnknownCustomPayload 并 ClassCastException 断连。
        FormaCraftNetworking.registerPayloadTypesC2S();
        FormaCraftNetworking.registerPayloadTypesS2C();

        // 注册接收器
        ClientPlayNetworking.registerGlobalReceiver(FormaCraftNetworking.ResponseBuildSpecPayload.ID, (payload, context) -> context.client().execute(() -> {
            BuildingSpec spec = payload.spec();
            FormacraftMod.LOGGER.info("Received BuildingSpec from server: {}", spec.getType());

            // 添加到聊天面板（显示 AI 回复）
            String aiResponse = "已生成建筑规格：" + (spec.getType() != null ? spec.getType().name() : "Unknown");
            if (spec.getNotes() != null && !spec.getNotes().isEmpty()) {
                aiResponse += "\n" + spec.getNotes();
            }
            // Debug: show backend warnings (LLM normalization / fallbacks), gated by settings
            try {
                java.util.List<String> warnings = spec.getDebugWarnings();
                if (com.formacraft.config.SettingsConfig.INSTANCE.showDebugWarnings && !warnings.isEmpty()) {
                    StringBuilder sb = new StringBuilder("\n\n[debugWarnings]\n");
                    int n = 0;
                    for (String s : warnings) {
                        sb.append("- ").append(s).append("\n");
                        if (++n >= 20) break;
                    }
                    aiResponse += sb.toString().trim();
                }
            } catch (Throwable t) {
                FormacraftMod.LOGGER.debug("[FormaCraftClientNetworking] Failed to append debugWarnings to build spec response", t);
            }
            com.formacraft.client.ui.FormaCraftHudOverlay.CHAT_PANEL.addAIMessage(aiResponse, spec);

            // 显示确认面板（替代 BuildPreviewScreen）
            com.formacraft.client.ui.panel.BuildConfirmPanel.INSTANCE.show(spec);
        }));

        ClientPlayNetworking.registerGlobalReceiver(FormaCraftNetworking.ResponseBuildErrorPayload.ID, (payload, context) -> context.client().execute(() -> {
            String msg = payload.message();
            FormacraftMod.LOGGER.warn("Received build error from server: {}", msg);
            String display;
            if (msg == null || msg.isBlank()) {
                display = "未知错误：后端未返回具体原因。";
            } else if (msg.startsWith("【ASSEMBLY 能力缺口】") || msg.startsWith("[Capability gap]")) {
                display = msg;
            } else if (shouldShowOrchestratorMessageDirectly(msg)) {
                display = msg;
            } else {
                display = "请求失败：" + msg;
            }
            com.formacraft.client.ui.FormaCraftHudOverlay.CHAT_PANEL.addAIError(display);
        }));

        ClientPlayNetworking.registerGlobalReceiver(FormaCraftNetworking.ResponseBuildStatusPayload.ID, (payload, context) -> context.client().execute(() -> {
            String msg = payload.message();
            if (msg == null || msg.isBlank()) return;
            FormacraftMod.LOGGER.info("Received build status from server: {}", msg);
            com.formacraft.client.ui.FormaCraftHudOverlay.CHAT_PANEL.addAIStatus(msg);
            if (msg.contains("【注意】") || msg.contains("不建议直接应用")) {
                com.formacraft.client.ui.panel.BuildConfirmPanel.INSTANCE.notePreviewQualityError();
            }
        }));

        // 预览线框数据包接收器
        ClientPlayNetworking.registerGlobalReceiver(FormaCraftNetworking.PreviewOutlinePayload.ID, (payload, context) -> context.client().execute(() -> {
            List<OutlineBlock> blocks = payload.blocks();
            com.formacraft.client.preview.OutlinePreviewState.setBlocks(blocks);
            FormacraftMod.LOGGER.info("Received preview outline: {} blocks", blocks != null ? blocks.size() : 0);

            // 无 BuildingSpec 的预览链路（LlmPlan / Composite / City）：服务端只下发轮廓 + “preview ready”，
            // 不会发 ResponseBuildSpecPayload。这里在收到轮廓时弹出 PREVIEW 模式确认面板（确认/取消按钮，
            // 走 /forma_confirm、/forma_cancel）。若已存在带 spec 的 BUILD 面板则不覆盖。
            if (blocks != null && !blocks.isEmpty()
                    && !com.formacraft.client.ui.panel.BuildConfirmPanel.INSTANCE.isVisible()) {
                com.formacraft.client.ui.panel.BuildConfirmPanel.INSTANCE.showPreviewActions();
            }
        }));

        // 预览骨架数据包接收器（J-layer skeleton layout preview）
        ClientPlayNetworking.registerGlobalReceiver(FormaCraftNetworking.PreviewSkeletonPayload.ID, (payload, context) -> context.client().execute(() -> {
            String json = payload.json();
            com.formacraft.client.preview.SkeletonPreviewState.setFromJson(json);
            FormacraftMod.LOGGER.info("Received preview skeleton: {} chars", json != null ? json.length() : 0);
        }));

        // 预览原点更新（用于预览移动后确认建造）
        ClientPlayNetworking.registerGlobalReceiver(FormaCraftNetworking.PreviewOriginPayload.ID, (payload, context) -> context.client().execute(() -> {
            BlockPos origin = payload.origin();
            com.formacraft.client.preview.BuildingPreviewState.setOrigin(origin);
            FormacraftMod.LOGGER.info("Preview origin updated: {}", origin);
        }));

        // 清除预览数据包接收器
        ClientPlayNetworking.registerGlobalReceiver(FormaCraftNetworking.ClearOutlinePayload.ID, (payload, context) -> context.client().execute(() -> {
            com.formacraft.client.preview.OutlinePreviewState.clear();
            com.formacraft.client.preview.SkeletonPreviewState.clear();
            FormacraftMod.LOGGER.info("Preview outline cleared");
        }));

        // Component Library: catalog 下发（服务端 -> 客户端）
        ClientPlayNetworking.registerGlobalReceiver(FormaCraftNetworking.ComponentCatalogPayload.ID, (payload, context) -> context.client().execute(() -> {
            String json = payload.json();
            com.formacraft.client.component.ClientComponentCatalogState.setFromJson(json);
        }));

        // Component Library: 保存结果确认（服务端 -> 客户端）
        ClientPlayNetworking.registerGlobalReceiver(FormaCraftNetworking.ComponentSaveAckPayload.ID, (payload, context) -> context.client().execute(() -> {
            try {
                com.formacraft.client.tool.ComponentTool.INSTANCE.onSaveAckFromServer(
                        payload.id(), payload.name(), payload.success(), payload.message());
            } catch (Throwable t) {
                FormacraftMod.LOGGER.warn("[FormaCraftClientNetworking] ComponentSaveAck handler failed componentId={}", payload.id(), t);
            }
        }));

        // Component Library: 单个构件定义下发（服务端 -> 客户端）
        ClientPlayNetworking.registerGlobalReceiver(FormaCraftNetworking.ComponentDefinitionPayload.ID, (payload, context) -> context.client().execute(() -> {
            String json = payload.json();
            try {
                com.formacraft.client.tool.ComponentTool.INSTANCE.onComponentDefinitionFromServer(json);
            } catch (Throwable t) {
                FormacraftMod.LOGGER.warn("[FormaCraftClientNetworking] ComponentDefinition handler failed", t);
            }
        }));

        // Patch 预览（服务端签发 PreviewTicket）
        ClientPlayNetworking.registerGlobalReceiver(FormaCraftNetworking.PatchPreviewPayload.ID, (payload, context) -> context.client().execute(() -> {
            try {
                com.formacraft.client.preview.PatchPreviewClientState.onPatchPreviewFromServer(
                        payload.ticketId(),
                        payload.origin(),
                        payload.accepted(),
                        payload.rejected()
                );
            } catch (Throwable t) {
                FormacraftMod.LOGGER.warn("[FormaCraftClientNetworking] PatchPreview handler failed ticketId={}", payload.ticketId(), t);
            }
        }));

        ClientPlayNetworking.registerGlobalReceiver(FormaCraftNetworking.PatchApplyResultPayload.ID, (payload, context) -> context.client().execute(() -> {
            String summary = payload.summary();
            com.formacraft.client.ui.panel.BuildConfirmPanel.INSTANCE.onPatchApplyResult(
                    payload.operation(), summary, payload.canUndo(), payload.canRedo());
            if (summary != null && !summary.isBlank()) {
                com.formacraft.client.ui.FormaCraftHudOverlay.CHAT_PANEL.addAIStatus(summary);
            }
        }));
    }

    /**
     * 客户端发送建筑请求
     * @param request 建筑请求
     */
    public static void sendBuildRequest(FormaRequest request) {
        // 某些环境下 Fabric API 的 send() 会尝试把 payload cast 成 UnknownCustomPayload（导致 ClassCastException）。
        // 这里直接走原版 CustomPayloadC2SPacket，避免该兼容问题。
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc != null && mc.getNetworkHandler() != null) {
            mc.getNetworkHandler().sendPacket(new CustomPayloadC2SPacket(new FormaCraftNetworking.RequestBuildPayload(request)));
            return;
        }
        // fallback：如果还没进 play 阶段，按原方式尝试（不会阻塞）
        ClientPlayNetworking.send(new FormaCraftNetworking.RequestBuildPayload(request));
    }

    /** 客户端请求 Patch Undo */
    public static void sendPatchUndo() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc != null && mc.getNetworkHandler() != null) {
            mc.getNetworkHandler().sendPacket(new CustomPayloadC2SPacket(new FormaCraftNetworking.PatchUndoPayload()));
            return;
        }
        ClientPlayNetworking.send(new FormaCraftNetworking.PatchUndoPayload());
    }

    /** 客户端请求 Patch Redo */
    public static void sendPatchRedo() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc != null && mc.getNetworkHandler() != null) {
            mc.getNetworkHandler().sendPacket(new CustomPayloadC2SPacket(new FormaCraftNetworking.PatchRedoPayload()));
            return;
        }
        ClientPlayNetworking.send(new FormaCraftNetworking.PatchRedoPayload());
    }

    /** 客户端确认 Patch 预览（仅发送 ticketId）。 */
    public static void sendPatchConfirm(UUID previewTicketId) {
        if (previewTicketId == null) return;
        MinecraftClient mc = MinecraftClient.getInstance();
        FormaCraftNetworking.PatchConfirmPayload payload = new FormaCraftNetworking.PatchConfirmPayload(previewTicketId);
        if (mc != null && mc.getNetworkHandler() != null) {
            mc.getNetworkHandler().sendPacket(new CustomPayloadC2SPacket(payload));
            return;
        }
        ClientPlayNetworking.send(payload);
    }

    /** 客户端请求服务端生成 Patch 预览。 */
    public static void sendRequestPatchPreview(FormaCraftNetworking.RequestPatchPreviewPayload payload) {
        if (payload == null) return;
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc != null && mc.getNetworkHandler() != null) {
            mc.getNetworkHandler().sendPacket(new CustomPayloadC2SPacket(payload));
            return;
        }
        ClientPlayNetworking.send(payload);
    }

    /** 客户端同步轮廓/Footprint 到服务端。 */
    public static void sendOutlineSync(OutlineShape outline) {
        MinecraftClient mc = MinecraftClient.getInstance();
        FormaCraftNetworking.OutlineSyncPayload payload = new FormaCraftNetworking.OutlineSyncPayload(outline);
        if (mc != null && mc.getNetworkHandler() != null) {
            mc.getNetworkHandler().sendPacket(new CustomPayloadC2SPacket(payload));
            return;
        }
        ClientPlayNetworking.send(payload);
    }

    /** 客户端同步禁区/保护区到服务端。 */
    public static void sendProtectedZoneSync(List<ProtectedZone> zones) {
        MinecraftClient mc = MinecraftClient.getInstance();
        FormaCraftNetworking.ProtectedZoneSyncPayload payload = new FormaCraftNetworking.ProtectedZoneSyncPayload(zones != null ? zones : List.of());
        if (mc != null && mc.getNetworkHandler() != null) {
            mc.getNetworkHandler().sendPacket(new CustomPayloadC2SPacket(payload));
            return;
        }
        ClientPlayNetworking.send(payload);
    }

    /** 客户端请求预览位置微调 */
    public static void sendPreviewAdjust(int dx, int dy, int dz) {
        MinecraftClient mc = MinecraftClient.getInstance();
        FormaCraftNetworking.PreviewAdjustPayload payload = new FormaCraftNetworking.PreviewAdjustPayload(dx, dy, dz);
        if (mc != null && mc.getNetworkHandler() != null) {
            mc.getNetworkHandler().sendPacket(new CustomPayloadC2SPacket(payload));
            return;
        }
        ClientPlayNetworking.send(payload);
    }

    /** 客户端请求：拉取服务端构件目录（catalog） */
    public static void sendComponentCatalogRequest() {
        MinecraftClient mc = MinecraftClient.getInstance();
        FormaCraftNetworking.ComponentCatalogRequestPayload payload = new FormaCraftNetworking.ComponentCatalogRequestPayload();
        if (mc != null && mc.getNetworkHandler() != null) {
            mc.getNetworkHandler().sendPacket(new CustomPayloadC2SPacket(payload));
            return;
        }
        ClientPlayNetworking.send(payload);
    }

    /** 客户端请求：保存一个构件（服务端落盘到 world save） */
    public static void sendSaveComponent(String componentJson, byte[] thumbnailPng) {
        MinecraftClient mc = MinecraftClient.getInstance();
        FormaCraftNetworking.ComponentSavePayload payload = new FormaCraftNetworking.ComponentSavePayload(componentJson, thumbnailPng);
        if (mc != null && mc.getNetworkHandler() != null) {
            mc.getNetworkHandler().sendPacket(new CustomPayloadC2SPacket(payload));
            return;
        }
        ClientPlayNetworking.send(payload);
    }

    /** 客户端请求：拉取某个构件定义（ComponentDefinition JSON）。 */
    public static void sendComponentGetRequest(String componentId) {
        MinecraftClient mc = MinecraftClient.getInstance();
        FormaCraftNetworking.ComponentGetRequestPayload payload = new FormaCraftNetworking.ComponentGetRequestPayload(componentId);
        if (mc != null && mc.getNetworkHandler() != null) {
            mc.getNetworkHandler().sendPacket(new CustomPayloadC2SPacket(payload));
            return;
        }
        ClientPlayNetworking.send(payload);
    }

    /** 已由 OrchestratorErrorHumanizer 格式化的多行说明，不再叠加「请求失败：」前缀。 */
    private static boolean shouldShowOrchestratorMessageDirectly(String msg) {
        if (msg == null || msg.isBlank()) return false;
        return msg.startsWith("无法连接到后端服务")
                || msg.startsWith("LLM 调用失败")
                || msg.startsWith("Anthropic（Claude）")
                || msg.startsWith("DeepSeek ")
                || msg.startsWith("OpenAI ")
                || msg.startsWith("API Key")
                || msg.startsWith("API 访问被拒绝")
                || msg.startsWith("LLM 账户余额")
                || msg.contains("\n原因：")
                || msg.contains("\n建议：");
    }

}
