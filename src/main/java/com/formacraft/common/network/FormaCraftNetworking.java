package com.formacraft.common.network;

import com.formacraft.common.model.build.BuildingSpec;
import com.formacraft.common.model.request.FormaRequest;
import com.formacraft.common.buildcontext.OutlineShape;
import com.formacraft.common.network.packet.BlockPatchPacket;
import com.formacraft.common.network.packet.OutlineShapePacket;
import com.formacraft.common.network.packet.PreviewOutlinePacket;
import com.formacraft.common.network.packet.PreviewSkeletonPacket;
import com.formacraft.common.network.packet.RequestBuildPacket;
import com.formacraft.common.network.packet.ResponseBuildErrorPacket;
import com.formacraft.common.network.packet.ResponseBuildSpecPacket;
import com.formacraft.common.network.packet.ResponseBuildStatusPacket;
import com.formacraft.client.preview.OutlineBlock;
import com.formacraft.server.build.BuildExecutionService;
import com.formacraft.server.build.BuildConstraintContext;
import com.formacraft.server.build.BuildConstraintClipper;
import com.formacraft.server.orchestrator.OrchestratorClient;
import com.formacraft.FormacraftMod;
import com.formacraft.common.model.constraint.ProtectedZone;
import com.formacraft.common.patch.BlockPatch;
import com.formacraft.common.component.ComponentCatalog;
import com.formacraft.common.component.ComponentDefinition;
import com.formacraft.common.component.ComponentStorage;
import com.formacraft.common.json.JsonUtil;
import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.dto.Dimensions;
import com.formacraft.common.llm.dto.LlmPlan;
import com.formacraft.common.llm.dto.Slot;
import com.formacraft.common.llm.dto.Vec3i;
import com.formacraft.common.llm.parser.LlmPlanParser;
import com.formacraft.common.llm.parser.PlanParseException;
import com.formacraft.common.compiler.ComponentPlanCompiler;
import com.formacraft.common.build.PlannedBlock;
import com.formacraft.server.foundation.FoundationPlanner;
import com.formacraft.server.foundation.FoundationType;
import com.formacraft.server.preview.PreviewStorage;
import com.formacraft.server.terrain.TerrainAdaptationEngine;
import com.formacraft.server.terrain.TerrainFit;

import java.util.List;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.client.MinecraftClient;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.network.packet.c2s.common.CustomPayloadC2SPacket;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Identifier;
import net.minecraft.util.WorldSavePath;
import net.minecraft.util.math.BlockPos;

/**
 * FormaCraft 网络通信统一管理类
 * 负责注册和处理所有 C2S 和 S2C 数据包
 */
public class FormaCraftNetworking {
    public static final Identifier REQUEST_BUILD = Identifier.of("formacraft", "request_build");
    public static final Identifier RESPONSE_BUILD_SPEC = Identifier.of("formacraft", "response_buildspec");
    public static final Identifier RESPONSE_BUILD_ERROR = Identifier.of("formacraft", "response_builderror");
    public static final Identifier RESPONSE_BUILD_STATUS = Identifier.of("formacraft", "response_buildstatus");
    public static final Identifier PREVIEW_OUTLINE = Identifier.of("formacraft", "preview_outline");
    public static final Identifier PREVIEW_SKELETON = Identifier.of("formacraft", "preview_skeleton");
    public static final Identifier PREVIEW_ADJUST = Identifier.of("formacraft", "preview_adjust");
    public static final Identifier PREVIEW_ORIGIN = Identifier.of("formacraft", "preview_origin");
    public static final Identifier CLEAR_OUTLINE = Identifier.of("formacraft", "clear_outline");
    public static final Identifier PATCH_UNDO = Identifier.of("formacraft", "patch_undo");
    public static final Identifier PATCH_REDO = Identifier.of("formacraft", "patch_redo");
    public static final Identifier PATCH_CONFIRM = Identifier.of("formacraft", "patch_confirm");
    public static final Identifier PATCH_PREVIEW = Identifier.of("formacraft", "patch_preview");
    public static final Identifier PATCH_PREVIEW_REQUEST = Identifier.of("formacraft", "patch_preview_request");
    public static final Identifier PROTECTED_ZONE_SYNC = Identifier.of("formacraft", "protected_zone_sync");
    public static final Identifier OUTLINE_SYNC = Identifier.of("formacraft", "outline_sync");

    // Component Library（v1）
    public static final Identifier COMPONENT_SAVE = Identifier.of("formacraft", "component_save");
    public static final Identifier COMPONENT_CATALOG_REQUEST = Identifier.of("formacraft", "component_catalog_request");
    public static final Identifier COMPONENT_CATALOG = Identifier.of("formacraft", "component_catalog");
    public static final Identifier COMPONENT_SAVE_ACK = Identifier.of("formacraft", "component_save_ack");
    public static final Identifier COMPONENT_GET_REQUEST = Identifier.of("formacraft", "component_get_request");
    public static final Identifier COMPONENT_DEFINITION = Identifier.of("formacraft", "component_definition");

    // 防止在客户端/集成服务器环境下重复注册导致崩溃（PayloadTypeRegistry 不允许重复 register）
    private static boolean registeredC2SPayloadTypes = false;
    private static boolean registeredS2CPayloadTypes = false;

    // C2S 数据包定义
    public record RequestBuildPayload(FormaRequest request) implements CustomPayload {
        public static final CustomPayload.Id<RequestBuildPayload> ID = new CustomPayload.Id<>(REQUEST_BUILD);
        public static final PacketCodec<PacketByteBuf, RequestBuildPayload> CODEC = PacketCodec.of(
                (payload, buf) -> RequestBuildPacket.write(buf, payload.request),
                buf -> new RequestBuildPayload(RequestBuildPacket.read(buf))
        );

        @Override
        public Id<? extends CustomPayload> getId() {
            return ID;
        }
    }

    // S2C 数据包定义
    public record ResponseBuildSpecPayload(BuildingSpec spec) implements CustomPayload {
        public static final CustomPayload.Id<ResponseBuildSpecPayload> ID = new CustomPayload.Id<>(RESPONSE_BUILD_SPEC);
        public static final PacketCodec<PacketByteBuf, ResponseBuildSpecPayload> CODEC = PacketCodec.of(
                (payload, buf) -> ResponseBuildSpecPacket.write(buf, payload.spec),
                buf -> new ResponseBuildSpecPayload(ResponseBuildSpecPacket.read(buf))
        );

        @Override
        public Id<? extends CustomPayload> getId() {
            return ID;
        }
    }

    // S2C：错误信息（用于替换客户端“AI 正在思考...”占位）
    public record ResponseBuildErrorPayload(String message) implements CustomPayload {
        public static final CustomPayload.Id<ResponseBuildErrorPayload> ID = new CustomPayload.Id<>(RESPONSE_BUILD_ERROR);
        public static final PacketCodec<PacketByteBuf, ResponseBuildErrorPayload> CODEC = PacketCodec.of(
                (payload, buf) -> ResponseBuildErrorPacket.write(buf, payload.message),
                buf -> new ResponseBuildErrorPayload(ResponseBuildErrorPacket.read(buf))
        );

        @Override
        public Id<? extends CustomPayload> getId() {
            return ID;
        }
    }

    // S2C：状态信息（用于更新客户端“思考中/等待中”提示，避免黑盒）
    public record ResponseBuildStatusPayload(String message) implements CustomPayload {
        public static final CustomPayload.Id<ResponseBuildStatusPayload> ID = new CustomPayload.Id<>(RESPONSE_BUILD_STATUS);
        public static final PacketCodec<PacketByteBuf, ResponseBuildStatusPayload> CODEC = PacketCodec.of(
                (payload, buf) -> ResponseBuildStatusPacket.write(buf, payload.message),
                buf -> new ResponseBuildStatusPayload(ResponseBuildStatusPacket.read(buf))
        );

        @Override
        public Id<? extends CustomPayload> getId() {
            return ID;
        }
    }

    // 预览线框数据包
    public record PreviewOutlinePayload(List<OutlineBlock> blocks) implements CustomPayload {
        public static final CustomPayload.Id<PreviewOutlinePayload> ID = new CustomPayload.Id<>(PREVIEW_OUTLINE);
        public static final PacketCodec<PacketByteBuf, PreviewOutlinePayload> CODEC = PacketCodec.of(
                (payload, buf) -> PreviewOutlinePacket.write(buf, payload.blocks),
                buf -> new PreviewOutlinePayload(PreviewOutlinePacket.read(buf))
        );

        @Override
        public Id<? extends CustomPayload> getId() {
            return ID;
        }
    }

    /**
     * J-layer skeleton preview payload (server -> client).
     * Payload body is a JSON string:
     * {
     *   "origin": {"x": int, "y": int, "z": int},
     *   "skeletonLayout": {...}   // same shape as spec.extra.skeletonLayout
     * }
     */
    public record PreviewSkeletonPayload(String json) implements CustomPayload {
        public static final CustomPayload.Id<PreviewSkeletonPayload> ID = new CustomPayload.Id<>(PREVIEW_SKELETON);
        public static final PacketCodec<PacketByteBuf, PreviewSkeletonPayload> CODEC = PacketCodec.of(
                (payload, buf) -> PreviewSkeletonPacket.write(buf, payload.json),
                buf -> new PreviewSkeletonPayload(PreviewSkeletonPacket.read(buf))
        );

        @Override
        public Id<? extends CustomPayload> getId() {
            return ID;
        }
    }

    /** 预览位置微调请求（客户端 -> 服务端） */
    public record PreviewAdjustPayload(int dx, int dy, int dz) implements CustomPayload {
        public static final CustomPayload.Id<PreviewAdjustPayload> ID = new CustomPayload.Id<>(PREVIEW_ADJUST);
        public static final PacketCodec<PacketByteBuf, PreviewAdjustPayload> CODEC = PacketCodec.of(
                (payload, buf) -> {
                    buf.writeVarInt(payload.dx());
                    buf.writeVarInt(payload.dy());
                    buf.writeVarInt(payload.dz());
                },
                buf -> new PreviewAdjustPayload(buf.readVarInt(), buf.readVarInt(), buf.readVarInt())
        );

        @Override
        public Id<? extends CustomPayload> getId() {
            return ID;
        }
    }

    /** 预览原点更新（服务端 -> 客户端） */
    public record PreviewOriginPayload(BlockPos origin) implements CustomPayload {
        public static final CustomPayload.Id<PreviewOriginPayload> ID = new CustomPayload.Id<>(PREVIEW_ORIGIN);
        public static final PacketCodec<PacketByteBuf, PreviewOriginPayload> CODEC = PacketCodec.of(
                (payload, buf) -> buf.writeBlockPos(payload.origin()),
                buf -> new PreviewOriginPayload(buf.readBlockPos())
        );

        @Override
        public Id<? extends CustomPayload> getId() {
            return ID;
        }
    }

    // 清除预览数据包
    public record ClearOutlinePayload() implements CustomPayload {
        public static final CustomPayload.Id<ClearOutlinePayload> ID = new CustomPayload.Id<>(CLEAR_OUTLINE);
        public static final PacketCodec<PacketByteBuf, ClearOutlinePayload> CODEC = PacketCodec.of(
                (payload, buf) -> {}, // 空数据包
                buf -> new ClearOutlinePayload()
        );

        @Override
        public Id<? extends CustomPayload> getId() {
            return ID;
        }
    }

    // Patch Undo/Redo（空数据包）
    public record PatchUndoPayload() implements CustomPayload {
        public static final CustomPayload.Id<PatchUndoPayload> ID = new CustomPayload.Id<>(PATCH_UNDO);
        public static final PacketCodec<PacketByteBuf, PatchUndoPayload> CODEC = PacketCodec.of(
                (payload, buf) -> {},
                buf -> new PatchUndoPayload()
        );

        @Override
        public Id<? extends CustomPayload> getId() { return ID; }
    }

    public record PatchRedoPayload() implements CustomPayload {
        public static final CustomPayload.Id<PatchRedoPayload> ID = new CustomPayload.Id<>(PATCH_REDO);
        public static final PacketCodec<PacketByteBuf, PatchRedoPayload> CODEC = PacketCodec.of(
                (payload, buf) -> {},
                buf -> new PatchRedoPayload()
        );

        @Override
        public Id<? extends CustomPayload> getId() { return ID; }
    }

    /** Patch 确认：客户端仅发送 preview ticketId，服务端从 PreviewTicket 取 patches 并应用。 */
    public record PatchConfirmPayload(UUID previewTicketId) implements CustomPayload {
        public static final CustomPayload.Id<PatchConfirmPayload> ID = new CustomPayload.Id<>(PATCH_CONFIRM);
        public static final PacketCodec<PacketByteBuf, PatchConfirmPayload> CODEC = PacketCodec.of(
                (payload, buf) -> buf.writeUuid(payload.previewTicketId()),
                buf -> new PatchConfirmPayload(buf.readUuid())
        );

        @Override
        public Id<? extends CustomPayload> getId() { return ID; }
    }

    /** S2C：Patch 预览（含 ticketId；确认时只回传 ticketId）。 */
    public record PatchPreviewPayload(
            UUID ticketId,
            BlockPos origin,
            List<BlockPatch> accepted,
            List<BlockPatch> rejected
    ) implements CustomPayload {
        public static final CustomPayload.Id<PatchPreviewPayload> ID = new CustomPayload.Id<>(PATCH_PREVIEW);
        public static final PacketCodec<PacketByteBuf, PatchPreviewPayload> CODEC = PacketCodec.of(
                (payload, buf) -> {
                    buf.writeUuid(payload.ticketId());
                    buf.writeBlockPos(payload.origin());
                    BlockPatchPacket.writePatches(buf, payload.accepted());
                    BlockPatchPacket.writePatches(buf, payload.rejected());
                },
                buf -> new PatchPreviewPayload(
                        buf.readUuid(),
                        buf.readBlockPos(),
                        BlockPatchPacket.readPatches(buf),
                        BlockPatchPacket.readPatches(buf)
                )
        );

        @Override
        public Id<? extends CustomPayload> getId() { return ID; }
    }

    /**
     * C2S：请求服务端生成 Patch 预览（服务端生成 patches、过滤、签发 PreviewTicket）。
     * componentId 非空时从构件库加载；否则按 selectionMin/Max 扫描世界选区。
     */
    public record RequestPatchPreviewPayload(
            BlockPos origin,
            String componentId,
            String facing,
            String mirror,
            boolean semanticSkin,
            String semanticStyleId,
            boolean restrictToSelection,
            BlockPos selectionMin,
            BlockPos selectionMax,
            List<ProtectedZone> protectedZones,
            boolean autoConfirm
    ) implements CustomPayload {
        public static final CustomPayload.Id<RequestPatchPreviewPayload> ID = new CustomPayload.Id<>(PATCH_PREVIEW_REQUEST);
        public static final PacketCodec<PacketByteBuf, RequestPatchPreviewPayload> CODEC = PacketCodec.of(
                (payload, buf) -> {
                    buf.writeBlockPos(payload.origin());
                    buf.writeString(payload.componentId() == null ? "" : payload.componentId());
                    buf.writeString(payload.facing() == null ? "" : payload.facing());
                    buf.writeString(payload.mirror() == null ? "" : payload.mirror());
                    buf.writeBoolean(payload.semanticSkin());
                    buf.writeString(payload.semanticStyleId() == null ? "" : payload.semanticStyleId());
                    buf.writeBoolean(payload.restrictToSelection());
                    buf.writeBoolean(payload.selectionMin() != null);
                    if (payload.selectionMin() != null) buf.writeBlockPos(payload.selectionMin());
                    buf.writeBoolean(payload.selectionMax() != null);
                    if (payload.selectionMax() != null) buf.writeBlockPos(payload.selectionMax());
                    BlockPatchPacket.writeProtectedZones(buf, payload.protectedZones());
                    buf.writeBoolean(payload.autoConfirm());
                },
                buf -> {
                    BlockPos origin = buf.readBlockPos();
                    String componentId = buf.readString();
                    String facing = buf.readString();
                    String mirror = buf.readString();
                    boolean semanticSkin = buf.readBoolean();
                    String semanticStyleId = buf.readString();
                    boolean restrictToSelection = buf.readBoolean();
                    BlockPos selectionMin = buf.readBoolean() ? buf.readBlockPos() : null;
                    BlockPos selectionMax = buf.readBoolean() ? buf.readBlockPos() : null;
                    List<ProtectedZone> zones = BlockPatchPacket.readProtectedZones(buf);
                    boolean autoConfirm = buf.readBoolean();
                    if (componentId != null && componentId.isEmpty()) componentId = null;
                    if (facing != null && facing.isEmpty()) facing = null;
                    if (mirror != null && mirror.isEmpty()) mirror = null;
                    if (semanticStyleId != null && semanticStyleId.isEmpty()) semanticStyleId = null;
                    return new RequestPatchPreviewPayload(
                            origin, componentId, facing, mirror, semanticSkin, semanticStyleId,
                            restrictToSelection, selectionMin, selectionMax, zones, autoConfirm
                    );
                }
        );

        @Override
        public Id<? extends CustomPayload> getId() { return ID; }
    }

    /** C2S：同步客户端禁区/保护区到服务端（Patch 过滤的权威数据源）。 */
    public record ProtectedZoneSyncPayload(List<ProtectedZone> zones) implements CustomPayload {
        public static final CustomPayload.Id<ProtectedZoneSyncPayload> ID = new CustomPayload.Id<>(PROTECTED_ZONE_SYNC);
        public static final PacketCodec<PacketByteBuf, ProtectedZoneSyncPayload> CODEC = PacketCodec.of(
                (payload, buf) -> BlockPatchPacket.writeProtectedZones(buf, payload.zones()),
                buf -> new ProtectedZoneSyncPayload(BlockPatchPacket.readProtectedZones(buf))
        );

        @Override
        public Id<? extends CustomPayload> getId() { return ID; }
    }

    /** C2S：同步客户端轮廓/Footprint 到服务端（Patch 过滤的权威数据源）。 */
    public record OutlineSyncPayload(OutlineShape outline) implements CustomPayload {
        public static final CustomPayload.Id<OutlineSyncPayload> ID = new CustomPayload.Id<>(OUTLINE_SYNC);
        public static final PacketCodec<PacketByteBuf, OutlineSyncPayload> CODEC = PacketCodec.of(
                (payload, buf) -> OutlineShapePacket.writeOutline(buf, payload.outline()),
                buf -> new OutlineSyncPayload(OutlineShapePacket.readOutline(buf))
        );

        @Override
        public Id<? extends CustomPayload> getId() { return ID; }
    }

    public record ComponentSavePayload(String json, byte[] thumbnailPng) implements CustomPayload {
        public static final CustomPayload.Id<ComponentSavePayload> ID = new CustomPayload.Id<>(COMPONENT_SAVE);
        public static final PacketCodec<PacketByteBuf, ComponentSavePayload> CODEC = PacketCodec.of(
                (payload, buf) -> {
                    buf.writeString(payload.json == null ? "" : payload.json);
                    byte[] png = payload.thumbnailPng;
                    if (png != null && png.length > 0 && png.length < 1024 * 1024) { // 限制 1MB
                        buf.writeVarInt(png.length);
                        buf.writeBytes(png);
                    } else {
                        buf.writeVarInt(0);
                    }
                },
                buf -> {
                    String json = buf.readString();
                    int len = buf.readVarInt();
                    byte[] png = null;
                    if (len > 0 && len < 1024 * 1024) {
                        png = new byte[len];
                        buf.readBytes(png);
                    }
                    return new ComponentSavePayload(json, png);
                }
        );

        @Override
        public Id<? extends CustomPayload> getId() { return ID; }
    }

    /** C2S：请求服务端下发构件目录（catalog.json） */
    public record ComponentCatalogRequestPayload() implements CustomPayload {
        public static final CustomPayload.Id<ComponentCatalogRequestPayload> ID = new CustomPayload.Id<>(COMPONENT_CATALOG_REQUEST);
        public static final PacketCodec<PacketByteBuf, ComponentCatalogRequestPayload> CODEC = PacketCodec.of(
                (payload, buf) -> {},
                buf -> new ComponentCatalogRequestPayload()
        );

        @Override
        public Id<? extends CustomPayload> getId() { return ID; }
    }

    /** S2C：下发构件目录（ComponentCatalog 的 JSON 字符串） */
    public record ComponentCatalogPayload(String json) implements CustomPayload {
        public static final CustomPayload.Id<ComponentCatalogPayload> ID = new CustomPayload.Id<>(COMPONENT_CATALOG);
        public static final PacketCodec<PacketByteBuf, ComponentCatalogPayload> CODEC = PacketCodec.of(
                (payload, buf) -> buf.writeString(payload.json == null ? "" : payload.json),
                buf -> new ComponentCatalogPayload(buf.readString())
        );

        @Override
        public Id<? extends CustomPayload> getId() { return ID; }
    }

    /** S2C：构件保存结果确认（与 catalog 刷新解耦） */
    public record ComponentSaveAckPayload(
            String id,
            String name,
            boolean success,
            String message
    ) implements CustomPayload {
        public static final CustomPayload.Id<ComponentSaveAckPayload> ID = new CustomPayload.Id<>(COMPONENT_SAVE_ACK);
        public static final PacketCodec<PacketByteBuf, ComponentSaveAckPayload> CODEC = PacketCodec.of(
                (payload, buf) -> {
                    buf.writeString(payload.id == null ? "" : payload.id);
                    buf.writeString(payload.name == null ? "" : payload.name);
                    buf.writeBoolean(payload.success);
                    buf.writeString(payload.message == null ? "" : payload.message);
                },
                buf -> new ComponentSaveAckPayload(
                        buf.readString(),
                        buf.readString(),
                        buf.readBoolean(),
                        buf.readString()
                )
        );

        @Override
        public Id<? extends CustomPayload> getId() { return ID; }
    }

    /** C2S：请求服务端下发某个构件定义（payload 为 component id） */
    public record ComponentGetRequestPayload(String id) implements CustomPayload {
        public static final CustomPayload.Id<ComponentGetRequestPayload> ID = new CustomPayload.Id<>(COMPONENT_GET_REQUEST);
        public static final PacketCodec<PacketByteBuf, ComponentGetRequestPayload> CODEC = PacketCodec.of(
                (payload, buf) -> buf.writeString(payload.id == null ? "" : payload.id),
                buf -> new ComponentGetRequestPayload(buf.readString())
        );

        @Override
        public Id<? extends CustomPayload> getId() { return ID; }
    }

    /** S2C：下发某个构件定义（payload 为 ComponentDefinition 的 JSON 字符串；空表示找不到） */
    public record ComponentDefinitionPayload(String json) implements CustomPayload {
        public static final CustomPayload.Id<ComponentDefinitionPayload> ID = new CustomPayload.Id<>(COMPONENT_DEFINITION);
        public static final PacketCodec<PacketByteBuf, ComponentDefinitionPayload> CODEC = PacketCodec.of(
                (payload, buf) -> buf.writeString(payload.json == null ? "" : payload.json),
                buf -> new ComponentDefinitionPayload(buf.readString())
        );

        @Override
        public Id<? extends CustomPayload> getId() { return ID; }
    }

    /**
     * 注册 C2S 数据包（客户端 → 服务端）
     * 在服务端初始化时调用
     */
    public static void registerC2S() {
        registerPayloadTypesC2S();
        // 服务端也需要注册 S2C payload types（用于编码回包）。否则可能出现“服务端处理了但发不出包”，客户端只能超时。
        registerPayloadTypesS2C();

        BuildRequestProcessor.register();

        // 注册确认建造数据包
        registerPayloadTypesC2S();
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
                            FormacraftMod.LOGGER.warn("[FormaCraftNetworking] Failed to clear outline for player {}", player.getName().getString(), t);
                        }
                        com.formacraft.server.preview.PreviewStorage.setPreview(player, false);
                        BuildExecutionService.getInstance().enqueueBuild(serverWorld, preview);
                        try {
                            ServerPlayNetworking.send(player, new ResponseBuildStatusPayload(
                                    String.format("已确认建造：开始放置方块…（按预览结果执行，共 %d 个方块）", 
                                            preview.getBlocks() != null ? preview.getBlocks().size() : 0)));
                        } catch (Throwable t) {
                            FormacraftMod.LOGGER.warn("[FormaCraftNetworking] Failed to send build status to player {}", player.getName().getString(), t);
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
                        FormacraftMod.LOGGER.warn("[FormaCraftNetworking] Failed to persist building spec for player {}", player.getName().getString(), t);
                    }

                    BuildExecutionService.getInstance().queueBuild(serverWorld, origin, spec, player.getUuid());
                    try {
                        ServerPlayNetworking.send(player, new ResponseBuildStatusPayload("已确认建造：开始放置方块…（重新生成）"));
                    } catch (Throwable t) {
                        FormacraftMod.LOGGER.warn("[FormaCraftNetworking] Failed to send regenerate status to player {}", player.getName().getString(), t);
                    }
                    FormacraftMod.LOGGER.info("Player {} confirmed build at {}",
                            player.getName().getString(), origin);
                }
            }
        }));

        // Patch Undo/Redo（服务端执行）
        registerPayloadTypesC2S();
        ServerPlayNetworking.registerGlobalReceiver(PatchUndoPayload.ID, (payload, context) -> context.server().execute(() -> {
            ServerPlayerEntity player = context.player();
            if (player == null) return;
            if (player.getEntityWorld() instanceof net.minecraft.server.world.ServerWorld sw) {
                com.formacraft.common.patch.history.PatchHistoryManager.undo(sw, player.getUuid());
            }
        }));
        ServerPlayNetworking.registerGlobalReceiver(PatchRedoPayload.ID, (payload, context) -> context.server().execute(() -> {
            ServerPlayerEntity player = context.player();
            if (player == null) return;
            if (player.getEntityWorld() instanceof net.minecraft.server.world.ServerWorld sw) {
                com.formacraft.common.patch.history.PatchHistoryManager.redo(sw, player.getUuid());
            }
        }));

        ServerPlayNetworking.registerGlobalReceiver(PatchConfirmPayload.ID, (payload, context) -> context.server().execute(() -> {
            ServerPlayerEntity player = context.player();
            if (player == null) return;
            com.formacraft.server.patch.PatchPreviewService.confirm(player, payload.previewTicketId());
        }));

        ServerPlayNetworking.registerGlobalReceiver(OutlineSyncPayload.ID, (payload, context) -> context.server().execute(() -> {
            ServerPlayerEntity player = context.player();
            if (player == null) return;
            com.formacraft.server.state.PlayerOutlineStorage.set(player, payload.outline());
        }));

        ServerPlayNetworking.registerGlobalReceiver(ProtectedZoneSyncPayload.ID, (payload, context) -> context.server().execute(() -> {
            ServerPlayerEntity player = context.player();
            if (player == null) return;
            com.formacraft.server.state.PlayerProtectedZoneStorage.set(player, payload.zones());
        }));

        ServerPlayNetworking.registerGlobalReceiver(RequestPatchPreviewPayload.ID, (payload, context) -> context.server().execute(() -> {
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
        ServerPlayNetworking.registerGlobalReceiver(ComponentSavePayload.ID, (payload, context) -> context.server().execute(() -> {
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
            ServerPlayNetworking.send(player, new ComponentCatalogPayload(catJson));
        }));

        // Component Library: 请求 catalog（客户端 -> 服务端）
        ServerPlayNetworking.registerGlobalReceiver(ComponentCatalogRequestPayload.ID, (payload, context) -> context.server().execute(() -> {
            ServerPlayerEntity player = context.player();
            if (player == null) return;
            net.minecraft.server.MinecraftServer server = context.server();
            if (server == null) return;

            java.nio.file.Path worldDir = server.getSavePath(WorldSavePath.ROOT);
            ComponentCatalog cat = ComponentStorage.loadCatalogWithSockets(worldDir);
            String catJson = JsonUtil.toJson(cat);
            ServerPlayNetworking.send(player, new ComponentCatalogPayload(catJson));
        }));

        // Component Library: 请求单个构件定义（客户端 -> 服务端）
        ServerPlayNetworking.registerGlobalReceiver(ComponentGetRequestPayload.ID, (payload, context) -> context.server().execute(() -> {
            ServerPlayerEntity player = context.player();
            if (player == null) return;
            net.minecraft.server.MinecraftServer server = context.server();
            if (server == null) return;

            String id = payload.id();
            if (id == null || id.isBlank()) {
                ServerPlayNetworking.send(player, new ComponentDefinitionPayload(""));
                return;
            }

            java.nio.file.Path worldDir = server.getSavePath(WorldSavePath.ROOT);
            ComponentDefinition def = ComponentStorage.loadComponent(worldDir, id.trim());
            if (def == null) {
                ServerPlayNetworking.send(player, new ComponentDefinitionPayload(""));
                return;
            }
            String defJson = JsonUtil.toJson(def);
            ServerPlayNetworking.send(player, new ComponentDefinitionPayload(defJson));
        }));

        // 预览位置微调（服务端执行）
        ServerPlayNetworking.registerGlobalReceiver(PreviewAdjustPayload.ID, (payload, context) -> context.server().execute(() -> {
            ServerPlayerEntity player = context.player();
            if (player == null) return;
            if (!PreviewStorage.hasPreview(player)) {
                try {
                    ServerPlayNetworking.send(player, new ResponseBuildStatusPayload("当前没有可调整的预览。"));
                } catch (Throwable t) {
                    FormacraftMod.LOGGER.warn("[FormaCraftNetworking] Failed to send preview-adjust status to player {}", player.getName().getString(), t);
                }
                return;
            }
            com.formacraft.common.build.GeneratedStructure structure = PreviewStorage.getStructure(player);
            if (structure == null || structure.getBlocks() == null || structure.getBlocks().isEmpty()) {
                try {
                    ServerPlayNetworking.send(player, new ResponseBuildStatusPayload("预览结构为空，无法调整。"));
                } catch (Throwable t) {
                    FormacraftMod.LOGGER.warn("[FormaCraftNetworking] Failed to send empty-preview status to player {}", player.getName().getString(), t);
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
                ServerPlayNetworking.send(player, new ResponseBuildStatusPayload(
                        "预览已调整：dx=" + dx + " dy=" + dy + " dz=" + dz));
            } catch (Throwable t) {
                FormacraftMod.LOGGER.warn("[FormaCraftNetworking] Failed to send preview-adjusted status to player {}", player.getName().getString(), t);
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

    public static void registerS2C() {
        // 重要：客户端不仅要注册 S2C，还必须注册 C2S payload type，
        // 否则发送自定义 payload 时编码会走 UnknownCustomPayload 并 ClassCastException 断连。
        registerPayloadTypesC2S();
        registerPayloadTypesS2C();

        // 注册接收器
        ClientPlayNetworking.registerGlobalReceiver(ResponseBuildSpecPayload.ID, (payload, context) -> context.client().execute(() -> {
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
                FormacraftMod.LOGGER.debug("[FormaCraftNetworking] Failed to append debugWarnings to build spec response", t);
            }
            com.formacraft.client.ui.FormaCraftHudOverlay.CHAT_PANEL.addAIMessage(aiResponse, spec);

            // 显示确认面板（替代 BuildPreviewScreen）
            com.formacraft.client.ui.panel.BuildConfirmPanel.INSTANCE.show(spec);
        }));

        ClientPlayNetworking.registerGlobalReceiver(ResponseBuildErrorPayload.ID, (payload, context) -> context.client().execute(() -> {
            String msg = payload.message();
            FormacraftMod.LOGGER.warn("Received build error from server: {}", msg);
            com.formacraft.client.ui.FormaCraftHudOverlay.CHAT_PANEL.addAIError(
                    (msg == null || msg.isBlank()) ? "请求失败：未知错误" : ("请求失败：" + msg)
            );
        }));

        ClientPlayNetworking.registerGlobalReceiver(ResponseBuildStatusPayload.ID, (payload, context) -> context.client().execute(() -> {
            String msg = payload.message();
            if (msg == null || msg.isBlank()) return;
            FormacraftMod.LOGGER.info("Received build status from server: {}", msg);
            com.formacraft.client.ui.FormaCraftHudOverlay.CHAT_PANEL.addAIStatus(msg);
        }));

        // 预览线框数据包接收器
        ClientPlayNetworking.registerGlobalReceiver(PreviewOutlinePayload.ID, (payload, context) -> context.client().execute(() -> {
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
        ClientPlayNetworking.registerGlobalReceiver(PreviewSkeletonPayload.ID, (payload, context) -> context.client().execute(() -> {
            String json = payload.json();
            com.formacraft.client.preview.SkeletonPreviewState.setFromJson(json);
            FormacraftMod.LOGGER.info("Received preview skeleton: {} chars", json != null ? json.length() : 0);
        }));

        // 预览原点更新（用于预览移动后确认建造）
        ClientPlayNetworking.registerGlobalReceiver(PreviewOriginPayload.ID, (payload, context) -> context.client().execute(() -> {
            BlockPos origin = payload.origin();
            com.formacraft.client.preview.BuildingPreviewState.setOrigin(origin);
            FormacraftMod.LOGGER.info("Preview origin updated: {}", origin);
        }));

        // 清除预览数据包接收器
        ClientPlayNetworking.registerGlobalReceiver(ClearOutlinePayload.ID, (payload, context) -> context.client().execute(() -> {
            com.formacraft.client.preview.OutlinePreviewState.clear();
            com.formacraft.client.preview.SkeletonPreviewState.clear();
            FormacraftMod.LOGGER.info("Preview outline cleared");
        }));

        // Component Library: catalog 下发（服务端 -> 客户端）
        ClientPlayNetworking.registerGlobalReceiver(ComponentCatalogPayload.ID, (payload, context) -> context.client().execute(() -> {
            String json = payload.json();
            com.formacraft.client.component.ClientComponentCatalogState.setFromJson(json);
        }));

        // Component Library: 保存结果确认（服务端 -> 客户端）
        ClientPlayNetworking.registerGlobalReceiver(ComponentSaveAckPayload.ID, (payload, context) -> context.client().execute(() -> {
            try {
                com.formacraft.client.tool.ComponentTool.INSTANCE.onSaveAckFromServer(
                        payload.id(), payload.name(), payload.success(), payload.message());
            } catch (Throwable t) {
                FormacraftMod.LOGGER.warn("[FormaCraftNetworking] ComponentSaveAck handler failed componentId={}", payload.id(), t);
            }
        }));

        // Component Library: 单个构件定义下发（服务端 -> 客户端）
        ClientPlayNetworking.registerGlobalReceiver(ComponentDefinitionPayload.ID, (payload, context) -> context.client().execute(() -> {
            String json = payload.json();
            try {
                com.formacraft.client.tool.ComponentTool.INSTANCE.onComponentDefinitionFromServer(json);
            } catch (Throwable t) {
                FormacraftMod.LOGGER.warn("[FormaCraftNetworking] ComponentDefinition handler failed", t);
            }
        }));

        // Patch 预览（服务端签发 PreviewTicket）
        ClientPlayNetworking.registerGlobalReceiver(PatchPreviewPayload.ID, (payload, context) -> context.client().execute(() -> {
            try {
                com.formacraft.client.preview.PatchPreviewClientState.onPatchPreviewFromServer(
                        payload.ticketId(),
                        payload.origin(),
                        payload.accepted(),
                        payload.rejected()
                );
            } catch (Throwable t) {
                FormacraftMod.LOGGER.warn("[FormaCraftNetworking] PatchPreview handler failed ticketId={}", payload.ticketId(), t);
            }
        }));
    }

    /** 注册所有 C2S PayloadType（客户端编码 & 服务端解码都需要）。 */
    private static void registerPayloadTypesC2S() {
        if (registeredC2SPayloadTypes) return;
        registeredC2SPayloadTypes = true;

        PayloadTypeRegistry.playC2S().register(RequestBuildPayload.ID, RequestBuildPayload.CODEC);
        PayloadTypeRegistry.playC2S().register(ConfirmBuildPacket.ID, ConfirmBuildPacket.CODEC);
        PayloadTypeRegistry.playC2S().register(PatchUndoPayload.ID, PatchUndoPayload.CODEC);
        PayloadTypeRegistry.playC2S().register(PatchRedoPayload.ID, PatchRedoPayload.CODEC);
        PayloadTypeRegistry.playC2S().register(PatchConfirmPayload.ID, PatchConfirmPayload.CODEC);
        PayloadTypeRegistry.playC2S().register(RequestPatchPreviewPayload.ID, RequestPatchPreviewPayload.CODEC);
        PayloadTypeRegistry.playC2S().register(ProtectedZoneSyncPayload.ID, ProtectedZoneSyncPayload.CODEC);
        PayloadTypeRegistry.playC2S().register(OutlineSyncPayload.ID, OutlineSyncPayload.CODEC);
        PayloadTypeRegistry.playC2S().register(PreviewAdjustPayload.ID, PreviewAdjustPayload.CODEC);
        PayloadTypeRegistry.playC2S().register(ComponentSavePayload.ID, ComponentSavePayload.CODEC);
        PayloadTypeRegistry.playC2S().register(ComponentCatalogRequestPayload.ID, ComponentCatalogRequestPayload.CODEC);
        PayloadTypeRegistry.playC2S().register(ComponentGetRequestPayload.ID, ComponentGetRequestPayload.CODEC);
    }

    /** 注册所有 S2C PayloadType（客户端解码 & 服务端编码都需要）。 */
    private static void registerPayloadTypesS2C() {
        if (registeredS2CPayloadTypes) return;
        registeredS2CPayloadTypes = true;

        PayloadTypeRegistry.playS2C().register(ResponseBuildSpecPayload.ID, ResponseBuildSpecPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(ResponseBuildErrorPayload.ID, ResponseBuildErrorPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(ResponseBuildStatusPayload.ID, ResponseBuildStatusPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(PreviewOutlinePayload.ID, PreviewOutlinePayload.CODEC);
        PayloadTypeRegistry.playS2C().register(PreviewSkeletonPayload.ID, PreviewSkeletonPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(PreviewOriginPayload.ID, PreviewOriginPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(ClearOutlinePayload.ID, ClearOutlinePayload.CODEC);
        PayloadTypeRegistry.playS2C().register(PatchPreviewPayload.ID, PatchPreviewPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(ComponentCatalogPayload.ID, ComponentCatalogPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(ComponentSaveAckPayload.ID, ComponentSaveAckPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(ComponentDefinitionPayload.ID, ComponentDefinitionPayload.CODEC);
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
            ServerPlayNetworking.send(player, new ComponentSaveAckPayload(
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

    /**
     * 客户端发送建筑请求
     * @param request 建筑请求
     */
    public static void sendBuildRequest(FormaRequest request) {
        // 某些环境下 Fabric API 的 send() 会尝试把 payload cast 成 UnknownCustomPayload（导致 ClassCastException）。
        // 这里直接走原版 CustomPayloadC2SPacket，避免该兼容问题。
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc != null && mc.getNetworkHandler() != null) {
            mc.getNetworkHandler().sendPacket(new CustomPayloadC2SPacket(new RequestBuildPayload(request)));
            return;
        }
        // fallback：如果还没进 play 阶段，按原方式尝试（不会阻塞）
        ClientPlayNetworking.send(new RequestBuildPayload(request));
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
            mc.getNetworkHandler().sendPacket(new CustomPayloadC2SPacket(new PatchUndoPayload()));
            return;
        }
        ClientPlayNetworking.send(new PatchUndoPayload());
    }

    /** 客户端请求 Patch Redo */
    public static void sendPatchRedo() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc != null && mc.getNetworkHandler() != null) {
            mc.getNetworkHandler().sendPacket(new CustomPayloadC2SPacket(new PatchRedoPayload()));
            return;
        }
        ClientPlayNetworking.send(new PatchRedoPayload());
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
        ServerPlayNetworking.send(player, new PatchPreviewPayload(
                ticketId,
                origin,
                accepted != null ? accepted : List.of(),
                rejected != null ? rejected : List.of()
        ));
    }

    /** 客户端确认 Patch 预览（仅发送 ticketId）。 */
    public static void sendPatchConfirm(UUID previewTicketId) {
        if (previewTicketId == null) return;
        MinecraftClient mc = MinecraftClient.getInstance();
        PatchConfirmPayload payload = new PatchConfirmPayload(previewTicketId);
        if (mc != null && mc.getNetworkHandler() != null) {
            mc.getNetworkHandler().sendPacket(new CustomPayloadC2SPacket(payload));
            return;
        }
        ClientPlayNetworking.send(payload);
    }

    /** 客户端请求服务端生成 Patch 预览。 */
    public static void sendRequestPatchPreview(RequestPatchPreviewPayload payload) {
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
        OutlineSyncPayload payload = new OutlineSyncPayload(outline);
        if (mc != null && mc.getNetworkHandler() != null) {
            mc.getNetworkHandler().sendPacket(new CustomPayloadC2SPacket(payload));
            return;
        }
        ClientPlayNetworking.send(payload);
    }

    /** 客户端同步禁区/保护区到服务端。 */
    public static void sendProtectedZoneSync(List<ProtectedZone> zones) {
        MinecraftClient mc = MinecraftClient.getInstance();
        ProtectedZoneSyncPayload payload = new ProtectedZoneSyncPayload(zones != null ? zones : List.of());
        if (mc != null && mc.getNetworkHandler() != null) {
            mc.getNetworkHandler().sendPacket(new CustomPayloadC2SPacket(payload));
            return;
        }
        ClientPlayNetworking.send(payload);
    }

    /** 客户端请求预览位置微调 */
    public static void sendPreviewAdjust(int dx, int dy, int dz) {
        MinecraftClient mc = MinecraftClient.getInstance();
        PreviewAdjustPayload payload = new PreviewAdjustPayload(dx, dy, dz);
        if (mc != null && mc.getNetworkHandler() != null) {
            mc.getNetworkHandler().sendPacket(new CustomPayloadC2SPacket(payload));
            return;
        }
        ClientPlayNetworking.send(payload);
    }

    /** 客户端请求：拉取服务端构件目录（catalog） */
    public static void sendComponentCatalogRequest() {
        MinecraftClient mc = MinecraftClient.getInstance();
        ComponentCatalogRequestPayload payload = new ComponentCatalogRequestPayload();
        if (mc != null && mc.getNetworkHandler() != null) {
            mc.getNetworkHandler().sendPacket(new CustomPayloadC2SPacket(payload));
            return;
        }
        ClientPlayNetworking.send(payload);
    }

    /** 客户端请求：保存一个构件（服务端落盘到 world save） */
    public static void sendSaveComponent(String componentJson, byte[] thumbnailPng) {
        MinecraftClient mc = MinecraftClient.getInstance();
        ComponentSavePayload payload = new ComponentSavePayload(componentJson, thumbnailPng);
        if (mc != null && mc.getNetworkHandler() != null) {
            mc.getNetworkHandler().sendPacket(new CustomPayloadC2SPacket(payload));
            return;
        }
        ClientPlayNetworking.send(payload);
    }

    /** 客户端请求：拉取某个构件定义（ComponentDefinition JSON）。 */
    public static void sendComponentGetRequest(String componentId) {
        MinecraftClient mc = MinecraftClient.getInstance();
        ComponentGetRequestPayload payload = new ComponentGetRequestPayload(componentId);
        if (mc != null && mc.getNetworkHandler() != null) {
            mc.getNetworkHandler().sendPacket(new CustomPayloadC2SPacket(payload));
            return;
        }
        ClientPlayNetworking.send(payload);
    }

    /**
     * 服务端发送预览线框
     * @param player 目标玩家
     * @param blocks 预览线框方块列表
     */
    public static void sendPreviewOutline(ServerPlayerEntity player, List<OutlineBlock> blocks) {
        ServerPlayNetworking.send(player, new PreviewOutlinePayload(blocks));
    }

    /**
     * 服务端发送预览原点更新
     * @param player 目标玩家
     * @param origin 预览原点
     */
    public static void sendPreviewOrigin(ServerPlayerEntity player, BlockPos origin) {
        if (player == null || origin == null) return;
        ServerPlayNetworking.send(player, new PreviewOriginPayload(origin));
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
        ServerPlayNetworking.send(player, new PreviewSkeletonPayload(json));
    }

    /**
     * 服务端清除预览
     * @param player 目标玩家
     */
    public static void sendClearOutline(ServerPlayerEntity player) {
        ServerPlayNetworking.send(player, new ClearOutlinePayload());
    }
}

