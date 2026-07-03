package com.formacraft.common.network;

import com.formacraft.common.buildcontext.OutlineShape;
import com.formacraft.common.model.build.BuildingSpec;
import com.formacraft.common.model.constraint.ProtectedZone;
import com.formacraft.common.model.request.FormaRequest;
import com.formacraft.common.network.packet.BlockPatchPacket;
import com.formacraft.common.network.packet.OutlineShapePacket;
import com.formacraft.common.network.packet.PreviewOutlinePacket;
import com.formacraft.common.network.packet.PreviewSkeletonPacket;
import com.formacraft.common.network.packet.RequestBuildPacket;
import com.formacraft.common.network.packet.ResponseBuildErrorPacket;
import com.formacraft.common.network.packet.ResponseBuildSpecPacket;
import com.formacraft.common.network.packet.ResponseBuildStatusPacket;
import com.formacraft.common.preview.OutlineBlock;
import com.formacraft.common.patch.BlockPatch;

import java.util.List;
import java.util.UUID;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;

/**
 * FormaCraft 网络 payload 定义与 codec 注册。
 * 服务端 C2S 见 {@link com.formacraft.server.network.FormaCraftServerNetworking}；
 * 客户端 S2C/C2S 发送见 {@link com.formacraft.client.network.FormaCraftClientNetworking}。
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
    public static final Identifier PATCH_APPLY_RESULT = Identifier.of("formacraft", "patch_apply_result");
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

    /** S2C：Patch 应用结果（{@link com.formacraft.common.patch.PatchExecutor.ApplyResult}）。 */
    public record PatchApplyResultPayload(
            int applied,
            int skippedWorldHeight,
            int skippedUnloaded,
            int skippedIllegal,
            String summary
    ) implements CustomPayload {
        public static final CustomPayload.Id<PatchApplyResultPayload> ID = new CustomPayload.Id<>(PATCH_APPLY_RESULT);
        public static final PacketCodec<PacketByteBuf, PatchApplyResultPayload> CODEC = PacketCodec.of(
                (payload, buf) -> {
                    buf.writeVarInt(payload.applied());
                    buf.writeVarInt(payload.skippedWorldHeight());
                    buf.writeVarInt(payload.skippedUnloaded());
                    buf.writeVarInt(payload.skippedIllegal());
                    buf.writeString(payload.summary() == null ? "" : payload.summary());
                },
                buf -> new PatchApplyResultPayload(
                        buf.readVarInt(),
                        buf.readVarInt(),
                        buf.readVarInt(),
                        buf.readVarInt(),
                        buf.readString()
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

    /** 注册所有 C2S PayloadType（客户端编码 & 服务端解码都需要）。 */
    public static void registerPayloadTypesC2S() {
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
    public static void registerPayloadTypesS2C() {
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
        PayloadTypeRegistry.playS2C().register(PatchApplyResultPayload.ID, PatchApplyResultPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(ComponentCatalogPayload.ID, ComponentCatalogPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(ComponentSaveAckPayload.ID, ComponentSaveAckPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(ComponentDefinitionPayload.ID, ComponentDefinitionPayload.CODEC);
    }
}
