package com.formacraft.client.network;

import com.formacraft.FormacraftMod;
import com.formacraft.common.buildcontext.OutlineShape;
import com.formacraft.common.model.build.BuildingSpec;
import com.formacraft.common.model.constraint.ProtectedZone;
import com.formacraft.common.model.request.FormaRequest;
import com.formacraft.common.network.ConfirmBuildPacket;
import com.formacraft.common.network.FormaCraftNetworking;
import com.formacraft.common.preview.OutlineBlock;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.MinecraftClient;
import net.minecraft.network.packet.c2s.common.CustomPayloadC2SPacket;

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
                if (com.formacraft.config.SettingsConfig.INSTANCE.showDebugWarnings
                        && spec.getExtra() != null
                        && spec.getExtra().get("debugWarnings") != null) {
                    Object dw = spec.getExtra().get("debugWarnings");
                    StringBuilder sb = new StringBuilder();
                    sb.append("\n\n[debugWarnings]\n");
                    if (dw instanceof java.util.List<?> list) {
                        int n = 0;
                        for (Object it : list) {
                            if (it == null) continue;
                            String s = String.valueOf(it).trim();
                            if (s.isEmpty()) continue;
                            sb.append("- ").append(s).append("\n");
                            n++;
                            if (n >= 20) break;
                        }
                    } else {
                        String s = String.valueOf(dw).trim();
                        if (!s.isEmpty()) sb.append("- ").append(s).append("\n");
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
            com.formacraft.client.ui.FormaCraftHudOverlay.CHAT_PANEL.addAIError(
                    (msg == null || msg.isBlank()) ? "请求失败：未知错误" : ("请求失败：" + msg)
            );
        }));

        ClientPlayNetworking.registerGlobalReceiver(FormaCraftNetworking.ResponseBuildStatusPayload.ID, (payload, context) -> context.client().execute(() -> {
            String msg = payload.message();
            if (msg == null || msg.isBlank()) return;
            FormacraftMod.LOGGER.info("Received build status from server: {}", msg);
            com.formacraft.client.ui.FormaCraftHudOverlay.CHAT_PANEL.addAIStatus(msg);
        }));

        // 预览线框数据包接收器
        ClientPlayNetworking.registerGlobalReceiver(FormaCraftNetworking.PreviewOutlinePayload.ID, (payload, context) -> context.client().execute(() -> {
            List<OutlineBlock> blocks = payload.blocks();
            com.formacraft.client.preview.OutlinePreviewState.setBlocks(blocks);
            FormacraftMod.LOGGER.info("Received preview outline: {} blocks", blocks != null ? blocks.size() : 0);

            // 显示确认面板（如果有 BuildingSpec，则显示详细信息）
            // 注意：这里可能需要从 PreviewStorage 获取 BuildingSpec
            // 暂时先显示一个简单的确认面板
            if (blocks != null && !blocks.isEmpty()) {
                // 可以创建一个临时的 BuildingSpec 用于显示
                // 或者只显示简单的确认信息
                // 这里先不显示，等待后续完善
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

    /**
     * 客户端确认建造
     * @param spec 建筑规格
     * @param origin 建造原点 [x, y, z]
     */
    public static void sendConfirmBuild(com.formacraft.common.model.build.BuildingSpec spec, int[] origin) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc != null && mc.getNetworkHandler() != null) {
            mc.getNetworkHandler().sendPacket(new CustomPayloadC2SPacket(new ConfirmBuildPacket(spec, origin)));
            return;
        }
        ClientPlayNetworking.send(new ConfirmBuildPacket(spec, origin));
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

}
