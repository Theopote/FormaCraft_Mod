package com.formacraft.server.network;

import com.formacraft.FormacraftMod;
import com.formacraft.common.build.PlannedBlock;
import com.formacraft.common.component.ComponentCatalog;
import com.formacraft.common.component.ComponentDefinition;
import com.formacraft.common.component.ComponentStorage;
import com.formacraft.common.json.JsonUtil;
import com.formacraft.common.model.build.BuildingSpec;
import com.formacraft.common.model.constraint.ProtectedZone;
import com.formacraft.common.network.ConfirmBuildPacket;
import com.formacraft.common.network.FormaCraftNetworking;
import com.formacraft.common.patch.BlockPatch;
import com.formacraft.common.patch.PatchExecutor;
import com.formacraft.common.preview.OutlineBlock;
import com.formacraft.server.build.BuildExecutionService;
import com.formacraft.server.preview.PreviewStorage;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.WorldSavePath;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 服务端 C2S 处理器与 S2C 发送辅助方法。
 * Payload 定义与 codec 留在 {@link FormaCraftNetworking}。
 */
public final class FormaCraftServerNetworking {
    private FormaCraftServerNetworking() {}

    public static void registerC2S() {
        FormaCraftNetworking.registerPayloadTypesC2S();
        // 服务端也需要注册 S2C payload types（用于编码回包）。否则可能出现“服务端处理了但发不出包”，客户端只能超时。
        FormaCraftNetworking.registerPayloadTypesS2C();

        // 注册确认建造数据包
        FormaCraftNetworking.registerPayloadTypesC2S();
        ServerPlayNetworking.registerGlobalReceiver(ConfirmBuildPacket.ID, (payload, context) -> context.server().execute(() -> {
            ServerPlayerEntity player = context.player();
            if (player == null) {
                FormacraftMod.LOGGER.warn("Received confirm build from null player");
                return;
            }

            if (player.getEntityWorld() instanceof net.minecraft.server.world.ServerWorld serverWorld) {
                // 优先：按"预览已生成的结构"执行，保证与预览一致（也包含禁区/轮廓硬裁剪）
                com.formacraft.common.build.GeneratedStructure preview = com.formacraft.server.preview.PreviewStorage.getStructure(player);
                boolean hasPreview = com.formacraft.server.preview.PreviewStorage.hasPreview(player);
                if (hasPreview && preview != null) {
                    // 验证预览结构有效性
                    if (!com.formacraft.server.preview.PreviewStorage.validatePreview(player)) {
                        FormacraftMod.LOGGER.warn("Player {} preview structure validation failed, falling back to regenerate", 
                                player.getName().getString());
                        // 继续执行回退逻辑
                    } else {
                        try { sendClearOutline(player); } catch (Throwable t) {
                            FormacraftMod.LOGGER.warn("[FormaCraftServerNetworking] Failed to clear outline for player {}", player.getName().getString(), t);
                        }
                        com.formacraft.server.preview.PreviewStorage.setPreview(player, false);
                        BuildExecutionService.getInstance().enqueueBuild(serverWorld, preview);
                        try {
                            ServerPlayNetworking.send(player, new FormaCraftNetworking.ResponseBuildStatusPayload(
                                    String.format("已确认建造：开始放置方块…（按预览结果执行，共 %d 个方块）", 
                                            preview.getBlocks() != null ? preview.getBlocks().size() : 0)));
                        } catch (Throwable t) {
                            FormacraftMod.LOGGER.warn("[FormaCraftServerNetworking] Failed to send build status to player {}", player.getName().getString(), t);
                        }
                        FormacraftMod.LOGGER.info("Player {} confirmed build (from preview) at {} with {} blocks",
                                player.getName().getString(), preview.getOrigin(),
                                preview.getBlocks() != null ? preview.getBlocks().size() : 0);
                        return;
                    }
                }

                // 回退：重新生成（兼容旧流程/无预览时）
                BuildingSpec spec = payload.spec();
                int[] originArray = payload.origin();
                if (originArray != null && originArray.length == 3) {
                    BlockPos origin = new BlockPos(originArray[0], originArray[1], originArray[2]);
                    // 保存 BuildingSpec 到 PlayerSpecRepository（供 PATCH/编辑使用）
                    try {
                        String buildingId = "player_" + player.getName().getString() + "_world_" +
                                serverWorld.getRegistryKey().getValue();
                        String buildingJson = JsonUtil.toJson(spec);
                        com.formacraft.server.state.PlayerSpecRepository.setBuildingSpec(player, buildingId, buildingJson);
                    } catch (Throwable t) {
                        FormacraftMod.LOGGER.warn("[FormaCraftServerNetworking] Failed to persist building spec for player {}", player.getName().getString(), t);
                    }

                    BuildExecutionService.getInstance().queueBuild(serverWorld, origin, spec, player.getUuid());
                    try {
                        ServerPlayNetworking.send(player, new FormaCraftNetworking.ResponseBuildStatusPayload("已确认建造：开始放置方块…（重新生成）"));
                    } catch (Throwable t) {
                        FormacraftMod.LOGGER.warn("[FormaCraftServerNetworking] Failed to send regenerate status to player {}", player.getName().getString(), t);
                    }
                    FormacraftMod.LOGGER.info("Player {} confirmed build at {}",
                            player.getName().getString(), origin);
                }
            }
        }));

        // Patch Undo/Redo（服务端执行）
        FormaCraftNetworking.registerPayloadTypesC2S();
        ServerPlayNetworking.registerGlobalReceiver(FormaCraftNetworking.PatchUndoPayload.ID, (payload, context) -> context.server().execute(() -> {
            ServerPlayerEntity player = context.player();
            if (player == null) return;
            if (player.getEntityWorld() instanceof net.minecraft.server.world.ServerWorld sw) {
                int changed = com.formacraft.common.patch.history.PatchHistoryManager.undo(sw, player.getUuid());
                sendPatchHistoryResult(player, "undo", changed);
            }
        }));
        ServerPlayNetworking.registerGlobalReceiver(FormaCraftNetworking.PatchRedoPayload.ID, (payload, context) -> context.server().execute(() -> {
            ServerPlayerEntity player = context.player();
            if (player == null) return;
            if (player.getEntityWorld() instanceof net.minecraft.server.world.ServerWorld sw) {
                int changed = com.formacraft.common.patch.history.PatchHistoryManager.redo(sw, player.getUuid());
                sendPatchHistoryResult(player, "redo", changed);
            }
        }));

        ServerPlayNetworking.registerGlobalReceiver(FormaCraftNetworking.PatchConfirmPayload.ID, (payload, context) -> context.server().execute(() -> {
            ServerPlayerEntity player = context.player();
            if (player == null) return;
            com.formacraft.server.patch.PatchPreviewService.confirm(player, payload.previewTicketId());
        }));

        ServerPlayNetworking.registerGlobalReceiver(FormaCraftNetworking.OutlineSyncPayload.ID, (payload, context) -> context.server().execute(() -> {
            ServerPlayerEntity player = context.player();
            if (player == null) return;
            com.formacraft.server.state.PlayerOutlineStorage.set(player, payload.outline());
        }));

        ServerPlayNetworking.registerGlobalReceiver(FormaCraftNetworking.ProtectedZoneSyncPayload.ID, (payload, context) -> context.server().execute(() -> {
            ServerPlayerEntity player = context.player();
            if (player == null) return;
            com.formacraft.server.state.PlayerProtectedZoneStorage.set(player, payload.zones());
        }));

        ServerPlayNetworking.registerGlobalReceiver(FormaCraftNetworking.RequestPatchPreviewPayload.ID, (payload, context) -> context.server().execute(() -> {
            ServerPlayerEntity player = context.player();
            if (player == null) return;
            if (!(player.getEntityWorld() instanceof net.minecraft.server.world.ServerWorld sw)) return;

            BlockPos origin = payload.origin();
            if (origin == null) return;

            List<BlockPatch> rawPatches;
            String componentId = payload.componentId();
            if (componentId != null && !componentId.isBlank()) {
                java.nio.file.Path worldDir = context.server().getSavePath(WorldSavePath.ROOT);
                ComponentDefinition def = ComponentStorage.loadComponent(worldDir, componentId.trim());
                if (def == null) {
                    FormacraftMod.LOGGER.warn("Patch preview request: unknown component {}", componentId);
                    return;
                }
                long seed = origin.asLong();
                rawPatches = com.formacraft.server.patch.ComponentPatchPreviewBuilder.fromComponentDefinition(
                        def,
                        com.formacraft.server.patch.ComponentPatchPreviewBuilder.parseFacing(payload.facing()),
                        com.formacraft.server.patch.ComponentPatchPreviewBuilder.parseMirror(payload.mirror()),
                        payload.semanticSkin(),
                        payload.semanticStyleId(),
                        seed
                );
            } else {
                rawPatches = com.formacraft.server.patch.ComponentPatchPreviewBuilder.fromWorldSelection(
                        sw, origin, payload.selectionMin(), payload.selectionMax());
            }

            if (rawPatches == null || rawPatches.isEmpty()) return;

            List<ProtectedZone> zones = com.formacraft.server.state.PlayerProtectedZoneStorage.get(player);
            if (zones.isEmpty() && payload.protectedZones() != null && !payload.protectedZones().isEmpty()) {
                com.formacraft.server.state.PlayerProtectedZoneStorage.set(player, payload.protectedZones());
                zones = com.formacraft.server.state.PlayerProtectedZoneStorage.get(player);
            }

            com.formacraft.server.preview.PreviewTicket ticket = com.formacraft.server.patch.PatchPreviewService.issuePreview(
                    player,
                    origin,
                    rawPatches,
                    zones,
                    com.formacraft.server.state.PlayerOutlineStorage.get(player),
                    payload.restrictToSelection(),
                    payload.selectionMin(),
                    payload.selectionMax(),
                    !payload.autoConfirm()
            );

            if (ticket != null && payload.autoConfirm()) {
                com.formacraft.server.patch.PatchPreviewService.confirm(player, ticket.id());
            }
        }));

        // Component Library: 保存构件（客户端 -> 服务端）
        ServerPlayNetworking.registerGlobalReceiver(FormaCraftNetworking.ComponentSavePayload.ID, (payload, context) -> context.server().execute(() -> {
            ServerPlayerEntity player = context.player();
            if (player == null) return;
            net.minecraft.server.MinecraftServer server = context.server();
            if (server == null) return;

            String json = payload.json();
            if (json == null || json.isBlank()) {
                sendComponentSaveAck(player, "", "", false, "保存构件失败：空数据");
                return;
            }

            ComponentDefinition def;
            try {
                def = JsonUtil.fromJson(json, ComponentDefinition.class);
            } catch (Throwable t) {
                sendComponentSaveAck(player, "", "", false, "保存构件失败：JSON 解析失败");
                return;
            }
            if (def == null || def.id == null || def.id.isBlank()) {
                sendComponentSaveAck(player, "", "", false, "保存构件失败：缺少 id");
                return;
            }

            java.nio.file.Path worldDir = server.getSavePath(WorldSavePath.ROOT);
            try {
                ComponentStorage.saveComponent(worldDir, def);
            } catch (Throwable t) {
                FormacraftMod.LOGGER.error("Failed to save component {}", def.id, t);
                sendComponentSaveAck(player, def.id, def.name, false, "保存构件失败：" + t.getMessage());
                return;
            }

            // 保存缩略图（如果有）
            byte[] thumbnailPng = payload.thumbnailPng();
            if (thumbnailPng != null && thumbnailPng.length > 0) {
                try {
                    java.nio.file.Path globalDir = ComponentStorage.getGlobalComponentDir();
                    java.nio.file.Files.createDirectories(globalDir);
                    java.nio.file.Path thumbFile = globalDir.resolve(def.id + ".png");
                    java.nio.file.Files.write(thumbFile, thumbnailPng);
                } catch (Throwable t) {
                    FormacraftMod.LOGGER.warn("Failed to save thumbnail for component {}: {}", def.id, t.getMessage());
                }
            }

            String ackMessage = "已保存构件：" + def.name + "（" + def.id + "）";
            sendComponentSaveAck(player, def.id, def.name, true, ackMessage);

            // 刷新 catalog（仅数据同步，不驱动保存 UI 反馈）
            ComponentCatalog cat = ComponentStorage.loadCatalogWithSockets(worldDir);
            String catJson = JsonUtil.toJson(cat);
            ServerPlayNetworking.send(player, new FormaCraftNetworking.ComponentCatalogPayload(catJson));
        }));

        // Component Library: 请求 catalog（客户端 -> 服务端）
        ServerPlayNetworking.registerGlobalReceiver(FormaCraftNetworking.ComponentCatalogRequestPayload.ID, (payload, context) -> context.server().execute(() -> {
            ServerPlayerEntity player = context.player();
            if (player == null) return;
            net.minecraft.server.MinecraftServer server = context.server();
            if (server == null) return;

            java.nio.file.Path worldDir = server.getSavePath(WorldSavePath.ROOT);
            ComponentCatalog cat = ComponentStorage.loadCatalogWithSockets(worldDir);
            String catJson = JsonUtil.toJson(cat);
            ServerPlayNetworking.send(player, new FormaCraftNetworking.ComponentCatalogPayload(catJson));
        }));

        // Component Library: 请求单个构件定义（客户端 -> 服务端）
        ServerPlayNetworking.registerGlobalReceiver(FormaCraftNetworking.ComponentGetRequestPayload.ID, (payload, context) -> context.server().execute(() -> {
            ServerPlayerEntity player = context.player();
            if (player == null) return;
            net.minecraft.server.MinecraftServer server = context.server();
            if (server == null) return;

            String id = payload.id();
            if (id == null || id.isBlank()) {
                ServerPlayNetworking.send(player, new FormaCraftNetworking.ComponentDefinitionPayload(""));
                return;
            }

            java.nio.file.Path worldDir = server.getSavePath(WorldSavePath.ROOT);
            ComponentDefinition def = ComponentStorage.loadComponent(worldDir, id.trim());
            if (def == null) {
                ServerPlayNetworking.send(player, new FormaCraftNetworking.ComponentDefinitionPayload(""));
                return;
            }
            String defJson = JsonUtil.toJson(def);
            ServerPlayNetworking.send(player, new FormaCraftNetworking.ComponentDefinitionPayload(defJson));
        }));

        // 预览位置微调（服务端执行）
        ServerPlayNetworking.registerGlobalReceiver(FormaCraftNetworking.PreviewAdjustPayload.ID, (payload, context) -> context.server().execute(() -> {
            ServerPlayerEntity player = context.player();
            if (player == null) return;
            if (!PreviewStorage.hasPreview(player)) {
                try {
                    ServerPlayNetworking.send(player, new FormaCraftNetworking.ResponseBuildStatusPayload("当前没有可调整的预览。"));
                } catch (Throwable t) {
                    FormacraftMod.LOGGER.warn("[FormaCraftServerNetworking] Failed to send preview-adjust status to player {}", player.getName().getString(), t);
                }
                return;
            }
            com.formacraft.common.build.GeneratedStructure structure = PreviewStorage.getStructure(player);
            if (structure == null || structure.getBlocks() == null || structure.getBlocks().isEmpty()) {
                try {
                    ServerPlayNetworking.send(player, new FormaCraftNetworking.ResponseBuildStatusPayload("预览结构为空，无法调整。"));
                } catch (Throwable t) {
                    FormacraftMod.LOGGER.warn("[FormaCraftServerNetworking] Failed to send empty-preview status to player {}", player.getName().getString(), t);
                }
                return;
            }

            int dx = payload.dx();
            int dy = payload.dy();
            int dz = payload.dz();
            int max = 32;
            dx = Math.max(-max, Math.min(max, dx));
            dy = Math.max(-max, Math.min(max, dy));
            dz = Math.max(-max, Math.min(max, dz));
            if (dx == 0 && dy == 0 && dz == 0) return;

            com.formacraft.common.build.GeneratedStructure shifted = shiftStructure(structure, dx, dy, dz);
            PreviewStorage.updateStructure(player, shifted);
            PreviewStorage.setPreview(player, true);

            List<OutlineBlock> outline =
                    com.formacraft.server.preview.OutlineGenerator.fromPlannedBlocks(shifted.getBlocks());
            sendPreviewOutline(player, outline);
            sendPreviewOrigin(player, shifted.getOrigin());

            Object layout = PreviewStorage.getSkeletonLayout(player);
            if (layout != null) {
                java.util.Map<String, Object> extra = new java.util.HashMap<>();
                extra.put("skeletonLayout", layout);
                sendPreviewSkeleton(player, shifted.getOrigin(), extra);
            }

            try {
                ServerPlayNetworking.send(player, new FormaCraftNetworking.ResponseBuildStatusPayload(
                        "预览已调整：dx=" + dx + " dy=" + dy + " dz=" + dz));
            } catch (Throwable t) {
                FormacraftMod.LOGGER.warn("[FormaCraftServerNetworking] Failed to send preview-adjusted status to player {}", player.getName().getString(), t);
            }
        }));
    }

    private static com.formacraft.common.build.GeneratedStructure shiftStructure(
            com.formacraft.common.build.GeneratedStructure structure,
            int dx,
            int dy,
            int dz
    ) {
        if (structure == null) return null;
        BlockPos origin = structure.getOrigin();
        if (origin == null) return structure;
        BlockPos newOrigin = origin.add(dx, dy, dz);
        List<PlannedBlock> shifted = new ArrayList<>(structure.getBlocks().size());
        for (PlannedBlock block : structure.getBlocks()) {
            if (block == null || block.getPos() == null) continue;
            BlockPos pos = block.getPos();
            BlockPos newPos = pos.add(dx, dy, dz);
            shifted.add(new PlannedBlock(newPos, block.getTargetState()));
        }
        return new com.formacraft.common.build.GeneratedStructure(
                structure.getOwner(),
                newOrigin,
                structure.getDescription(),
                shifted
        );
    }

    private static void sendComponentSaveAck(
            ServerPlayerEntity player,
            String id,
            String name,
            boolean success,
            String message
    ) {
        if (player == null) {
            return;
        }
        try {
            ServerPlayNetworking.send(player, new FormaCraftNetworking.ComponentSaveAckPayload(
                    id == null ? "" : id,
                    name == null ? "" : name,
                    success,
                    message == null ? "" : message
            ));
        } catch (Throwable t) {
            FormacraftMod.LOGGER.warn("[FormaCraftNetworking] Failed to send ComponentSaveAck componentId={} player={}",
                    id, player.getName().getString(), t);
        }
    }

    /** 服务端下发 Patch 预览（含 PreviewTicket id）。 */
    public static void sendPatchPreview(
            ServerPlayerEntity player,
            UUID ticketId,
            BlockPos origin,
            List<BlockPatch> accepted,
            List<BlockPatch> rejected
    ) {
        if (player == null || ticketId == null || origin == null) return;
        ServerPlayNetworking.send(player, new FormaCraftNetworking.PatchPreviewPayload(
                ticketId,
                origin,
                accepted != null ? accepted : List.of(),
                rejected != null ? rejected : List.of()
        ));
    }

    /** 服务端下发 Patch 应用结果（供 BuildConfirmPanel / Chat 展示）。 */
    public static void sendPatchApplyResult(ServerPlayerEntity player, PatchExecutor.ApplyResult result) {
        if (player == null || result == null) return;
        java.util.UUID id = player.getUuid();
        ServerPlayNetworking.send(player, new FormaCraftNetworking.PatchApplyResultPayload(
                "apply",
                result.applied(),
                result.skippedWorldHeight(),
                result.skippedUnloaded(),
                result.skippedIllegal(),
                result.summaryZh(),
                com.formacraft.common.patch.history.PatchHistoryManager.canUndo(id),
                com.formacraft.common.patch.history.PatchHistoryManager.canRedo(id)
        ));
    }

    /**
     * 服务端下发 Patch 撤销/重做结果。
     * @param operation "undo" 或 "redo"
     * @param changed   恢复的方块数量；<0 表示无操作
     */
    public static void sendPatchHistoryResult(ServerPlayerEntity player, String operation, int changed) {
        if (player == null) return;
        java.util.UUID id = player.getUuid();
        boolean ok = changed >= 0;
        String verb = "undo".equals(operation) ? "撤销" : "重做";
        String summary = ok
                ? ("已" + verb + " " + changed + " 个方块")
                : ("没有可" + verb + "的修改");
        ServerPlayNetworking.send(player, new FormaCraftNetworking.PatchApplyResultPayload(
                operation,
                ok ? changed : 0,
                0, 0, 0,
                summary,
                com.formacraft.common.patch.history.PatchHistoryManager.canUndo(id),
                com.formacraft.common.patch.history.PatchHistoryManager.canRedo(id)
        ));
    }

    /**
     * 服务端发送预览线框
     * @param player 目标玩家
     * @param blocks 预览线框方块列表
     */
    public static void sendPreviewOutline(ServerPlayerEntity player, List<OutlineBlock> blocks) {
        ServerPlayNetworking.send(player, new FormaCraftNetworking.PreviewOutlinePayload(blocks));
    }

    /**
     * 服务端发送预览原点更新
     * @param player 目标玩家
     * @param origin 预览原点
     */
    public static void sendPreviewOrigin(ServerPlayerEntity player, BlockPos origin) {
        if (player == null || origin == null) return;
        ServerPlayNetworking.send(player, new FormaCraftNetworking.PreviewOriginPayload(origin));
    }

    /**
     * 服务端发送骨架预览（J-layer skeleton layout）。
     * @param player 目标玩家
     * @param origin 预览原点（世界坐标）
     * @param extra  任意 spec.extra（期望包含 skeletonLayout）
     */
    public static void sendPreviewSkeleton(ServerPlayerEntity player, BlockPos origin, java.util.Map<String, Object> extra) {
        if (player == null || origin == null || extra == null) return;
        Object sk = extra.get("skeletonLayout");
        if (sk == null) return;
        PreviewStorage.setSkeletonLayout(player, sk);

        java.util.Map<String, Object> payload = new java.util.HashMap<>();
        java.util.Map<String, Object> o = new java.util.HashMap<>();
        o.put("x", origin.getX());
        o.put("y", origin.getY());
        o.put("z", origin.getZ());
        payload.put("origin", o);
        payload.put("skeletonLayout", sk);

        String json = JsonUtil.toJson(payload);
        if (json == null) json = "";
        ServerPlayNetworking.send(player, new FormaCraftNetworking.PreviewSkeletonPayload(json));
    }

    /**
     * 服务端清除预览
     * @param player 目标玩家
     */
    public static void sendClearOutline(ServerPlayerEntity player) {
        ServerPlayNetworking.send(player, new FormaCraftNetworking.ClearOutlinePayload());
    }

}
