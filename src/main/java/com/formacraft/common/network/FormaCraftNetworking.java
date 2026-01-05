package com.formacraft.common.network;

import com.formacraft.common.model.build.BuildingSpec;
import com.formacraft.common.model.request.FormaRequest;
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
import com.formacraft.common.json.JsonUtil;

import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.client.MinecraftClient;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.network.packet.c2s.common.CustomPayloadC2SPacket;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Identifier;
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
    public static final Identifier CLEAR_OUTLINE = Identifier.of("formacraft", "clear_outline");
    public static final Identifier PATCH_UNDO = Identifier.of("formacraft", "patch_undo");
    public static final Identifier PATCH_REDO = Identifier.of("formacraft", "patch_redo");
    public static final Identifier PATCH_APPLY = Identifier.of("formacraft", "patch_apply");

    // 后端客户端（延迟初始化，从配置读取）
    private static volatile OrchestratorClient orchestratorClient = null;
    private static String lastEndpoint = null;
    
    private static OrchestratorClient getOrchestrator() {
        String currentEndpoint = com.formacraft.common.config.ConfigManager.getOrchestratorEndpoint();
        // 如果端点改变或客户端未初始化，重新创建
        if (orchestratorClient == null || !currentEndpoint.equals(lastEndpoint)) {
            synchronized (FormaCraftNetworking.class) {
                if (orchestratorClient == null || !currentEndpoint.equals(lastEndpoint)) {
                    orchestratorClient = new OrchestratorClient(currentEndpoint);
                    lastEndpoint = currentEndpoint;
                }
            }
        }
        return orchestratorClient;
    }

    // 防止在客户端/集成服务器环境下重复注册导致崩溃（PayloadTypeRegistry 不允许重复 register）
    private static boolean registeredC2SPayloadTypes = false;
    private static boolean registeredS2CPayloadTypes = false;

    private static Throwable rootCause(Throwable t) {
        Throwable cur = t;
        // unwrap common wrappers
        while (cur instanceof java.util.concurrent.CompletionException
                || cur instanceof java.util.concurrent.ExecutionException) {
            Throwable c = cur.getCause();
            if (c == null) break;
            cur = c;
        }
        return cur;
    }

    private static String llmHint(FormaRequest req) {
        if (req == null) return "";
        String provider = (req.getLlmProvider() == null || req.getLlmProvider().isBlank()) ? "auto" : req.getLlmProvider().trim();
        String model = (req.getModel() == null || req.getModel().isBlank()) ? "auto" : req.getModel().trim();
        String base = (req.getLlmBaseUrl() == null || req.getLlmBaseUrl().isBlank()) ? "" : (" @ " + req.getLlmBaseUrl().trim());
        return "当前 LLM：" + provider + "/" + model + base;
    }

    private static final long STATUS_HEARTBEAT_SEC = 15;

    /**
     * 服务端“进度心跳”：每隔一段时间发送 ResponseBuildStatusPayload，让客户端知道仍在生成中。
     * - 发送必须在 server thread 上执行
     * - alive=false 时自动停止
     */
    private static void startStatusHeartbeat(net.minecraft.server.MinecraftServer server,
                                            ServerPlayerEntity player,
                                            AtomicBoolean alive,
                                            long startMs,
                                            AtomicReference<String> phaseRef) {
        if (server == null || player == null || alive == null) return;

        Runnable tick = new Runnable() {
            @Override
            public void run() {
                if (!alive.get()) return;
                long waitedSec = Math.max(0, (System.currentTimeMillis() - startMs) / 1000);
                String phase = (phaseRef == null || phaseRef.get() == null || phaseRef.get().isBlank())
                        ? "仍在生成中"
                        : phaseRef.get().trim();

                server.execute(() -> {
                    if (!alive.get()) return;
                    try {
                        ServerPlayNetworking.send(player, new ResponseBuildStatusPayload(
                                phase + "（已等待 " + waitedSec + " 秒）…"
                        ));
                    } catch (Throwable t) {
                        // 玩家断连/世界卸载等：停止心跳，避免刷屏
                        alive.set(false);
                    }
                });

                CompletableFuture.delayedExecutor(STATUS_HEARTBEAT_SEC, TimeUnit.SECONDS).execute(this);
            }
        };

        // 首次心跳延后发送（避免短请求刷屏）
        CompletableFuture.delayedExecutor(STATUS_HEARTBEAT_SEC, TimeUnit.SECONDS).execute(tick);
    }

    private static String tryExtractBodyJson(String message) {
        if (message == null) return null;
        int idx = message.indexOf(" body=");
        if (idx < 0) idx = message.indexOf("body=");
        if (idx < 0) return null;
        int start = message.indexOf('{', idx);
        if (start < 0) return null;
        // best-effort: take the rest
        return message.substring(start).trim();
    }

    private static String tryExtractDetailFromBody(String bodyJson) {
        if (bodyJson == null || bodyJson.isBlank()) return null;
        try {
            @SuppressWarnings("unchecked")
            java.util.Map<String, Object> m = (java.util.Map<String, Object>) JsonUtil.get().fromJson(bodyJson, java.util.Map.class);
            if (m == null) return null;
            Object d = m.get("detail");
            return d == null ? null : String.valueOf(d);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String summarizeKnownBillingOrAuthIssues(String detailOrMsgLower) {
        if (detailOrMsgLower == null) return null;
        String s = detailOrMsgLower.toLowerCase();

        // OpenAI quota
        if (s.contains("insufficient_quota") || s.contains("exceeded your current quota") || s.contains("error code: 429")) {
            return "OpenAI 额度/配额不足（429 insufficient_quota）。请检查 OpenAI 账单/余额/组织配额，或更换有额度的 API Key。";
        }
        // DeepSeek balance
        if (s.contains("insufficient balance") || s.contains("error code: 402")) {
            return "DeepSeek 余额不足（402 Insufficient Balance）。请充值或更换有余额的 DeepSeek API Key。";
        }
        // invalid key / unauthorized
        if (s.contains("invalid_api_key") || s.contains("incorrect api key") || s.contains("unauthorized") || s.contains("error code: 401")) {
            return "API Key 无效/未授权（401）。请检查 Key 是否正确、是否属于当前 Provider，以及是否已启用对应服务。";
        }
        // model not found
        if (s.contains("model_not_found") || s.contains("no such model") || s.contains("error code: 404")) {
            return "模型不存在/不可用（404）。请在设置中更换可用模型，或点击“刷新模型列表”选择。";
        }
        // rate limit (non-quota)
        if (s.contains("rate limit") || s.contains("too many requests")) {
            return "请求过于频繁（限流）。请稍后重试，或降低请求频率/更换模型。";
        }
        return null;
    }

    private static String humanizeOrchestratorFailure(String stage, FormaRequest req, Throwable ex) {
        Throwable root = rootCause(ex);
        String rawMsg = root == null ? "" : String.valueOf(root.getMessage());
        if (rawMsg == null) rawMsg = "";

        // 改进：检测连接错误
        String connectionError = detectConnectionError(root, rawMsg);
        if (connectionError != null) {
            String endpoint = com.formacraft.common.config.ConfigManager.getOrchestratorEndpoint();
            return String.format(
                    """
                            无法连接到后端服务（%s）。
                            %s
                            请检查：
                            1. Python 后端是否正在运行（运行：cd python_backend && uvicorn app.main:app --reload）
                            2. 后端地址是否正确（当前：%s，可在设置中修改）
                            3. 防火墙是否允许连接
                            4. 如果后端在其他机器，确保端口已开放且可访问""",
                    stage != null && !stage.isBlank() ? stage : "请求失败",
                    connectionError,
                    endpoint
            );
        }

        String body = tryExtractBodyJson(rawMsg);
        String detail = tryExtractDetailFromBody(body);

        String best = summarizeKnownBillingOrAuthIssues((detail != null ? detail : rawMsg));
        String header = (stage == null || stage.isBlank()) ? "后端请求失败。" : ("后端请求失败（" + stage + "）。");
        String hint = llmHint(req);

        // 一段短细节，方便用户直接截图
        String d = (detail != null && !detail.isBlank()) ? detail : rawMsg;
        if (d.length() > 360) d = d.substring(0, 360) + "...";
        String tail = d.isBlank() ? "" : ("\n细节：" + d);

        if (best != null) {
            return header + "\n原因：" + best + "\n" + hint + tail;
        }
        return header + "\n" + hint + tail;
    }

    /**
     * 检测连接相关的错误（ConnectException, UnknownHostException, SocketTimeoutException 等）
     */
    private static String detectConnectionError(Throwable root, String rawMsg) {
        if (root == null) return null;
        
        String className = root.getClass().getSimpleName();
        String msg = rawMsg.toLowerCase();
        
        // 连接拒绝
        if (className.contains("ConnectException") || msg.contains("connection refused") || 
            msg.contains("connect refused") || msg.contains("connection reset")) {
            return "连接被拒绝：后端服务可能未启动";
        }
        
        // 未知主机
        if (className.contains("UnknownHostException") || msg.contains("unknown host") || 
            msg.contains("name or service not known")) {
            return "无法解析主机名：请检查后端地址是否正确";
        }
        
        // 连接超时
        if (className.contains("ConnectTimeoutException") || msg.contains("connection timed out") ||
            msg.contains("connect timeout")) {
            return "连接超时：后端可能未响应，或网络有问题";
        }
        
        // 读取超时
        if (className.contains("SocketTimeoutException") || msg.contains("read timed out") ||
            msg.contains("socket timeout")) {
            return "读取超时：后端响应时间过长";
        }
        
        // HTTP 客户端错误
        if (className.contains("HttpConnectTimeoutException")) {
            return "HTTP 连接超时：无法连接到后端";
        }
        
        return null;
    }

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

    /** Patch Apply：origin + patches（dx/dy/dz/action/targetBlock） + protectedZones（强制过滤） */
    public record PatchApplyPayload(BlockPos origin, List<BlockPatch> patches, List<ProtectedZone> protectedZones) implements CustomPayload {
        public static final CustomPayload.Id<PatchApplyPayload> ID = new CustomPayload.Id<>(PATCH_APPLY);
        public static final PacketCodec<PacketByteBuf, PatchApplyPayload> CODEC = PacketCodec.of(
                (payload, buf) -> {
                    buf.writeBlockPos(payload.origin);
                    List<BlockPatch> ps = payload.patches != null ? payload.patches : java.util.Collections.emptyList();
                    buf.writeVarInt(ps.size());
                    for (BlockPatch p : ps) {
                        buf.writeVarInt(p.dx());
                        buf.writeVarInt(p.dy());
                        buf.writeVarInt(p.dz());
                        buf.writeString(p.action() == null ? "" : p.action());
                        buf.writeString(p.targetBlock() == null ? "" : p.targetBlock());
                    }

                    // protected zones
                    List<ProtectedZone> zs = payload.protectedZones != null ? payload.protectedZones : java.util.Collections.emptyList();
                    buf.writeVarInt(zs.size());
                    for (ProtectedZone z : zs) {
                        if (z == null || z.min() == null || z.max() == null) {
                            buf.writeBoolean(false);
                            continue;
                        }
                        buf.writeBoolean(true);
                        ProtectedZone n = z.normalized();
                        buf.writeBlockPos(n.min());
                        buf.writeBlockPos(n.max());
                    }
                },
                buf -> {
                    BlockPos origin = buf.readBlockPos();
                    int n = buf.readVarInt();
                    List<BlockPatch> ps = new ArrayList<>(Math.max(0, n));
                    for (int i = 0; i < n; i++) {
                        int dx = buf.readVarInt();
                        int dy = buf.readVarInt();
                        int dz = buf.readVarInt();
                        String action = buf.readString();
                        String target = buf.readString();
                        if (target != null && target.isEmpty()) target = null;
                        ps.add(new BlockPatch(action, dx, dy, dz, target));
                    }

                    int zn = buf.readVarInt();
                    List<ProtectedZone> zs = new ArrayList<>(Math.max(0, zn));
                    for (int i = 0; i < zn; i++) {
                        boolean present = buf.readBoolean();
                        if (!present) continue;
                        BlockPos min = buf.readBlockPos();
                        BlockPos max = buf.readBlockPos();
                        zs.add(new ProtectedZone(min, max).normalized());
                    }

                    return new PatchApplyPayload(origin, ps, zs);
                }
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

        // 注册接收器
        ServerPlayNetworking.registerGlobalReceiver(RequestBuildPayload.ID, (payload, context) -> context.server().execute(() -> {
            ServerPlayerEntity player = context.player();
            if (player == null) {
                FormacraftMod.LOGGER.warn("Received build request from null player");
                return;
            }

            FormaRequest req = payload.request();
            FormacraftMod.LOGGER.info("Received build request from player {}: {}",
                    player.getName().getString(), req.getRequestText());

            // 补齐“世界上下文”字段：让 Python 侧可以稳定获取 biome/facing/origin，而不是只依赖 prompt 文本解析。
            // 注意：客户端可能已填充这些字段；这里仅在缺失时兜底。
            try {
                // origin
                if (req.getPlayerPos() == null) {
                    req.setPlayerPos(player.getBlockPos());
                }

                // facing
                if (req.getFacing() == null || req.getFacing().isBlank()) {
                    req.setFacing(player.getHorizontalFacing().name());
                }

                // biome（服务端权威，避免客户端与服务端不同步）
                if ((req.getBiome() == null || req.getBiome().isBlank())
                        && req.getPlayerPos() != null
                        && player.getEntityWorld() instanceof net.minecraft.server.world.ServerWorld sw) {
                    java.util.Optional<net.minecraft.registry.RegistryKey<net.minecraft.world.biome.Biome>> key =
                            sw.getBiome(req.getPlayerPos()).getKey();
                    if (key != null && key.isPresent()) {
                        req.setBiome(key.get().getValue().toString());
                    }
                }
            } catch (Throwable ignored) {}

            // 状态：服务端已收到请求
            context.server().execute(() -> ServerPlayNetworking.send(player, new ResponseBuildStatusPayload("服务端已收到请求，正在请求后端…")));

            // 检查应该请求什么类型的结构
            String requestText = req.getRequestText().toLowerCase();
            boolean isCity = requestText.contains("城市") || requestText.contains("城镇") ||
                    requestText.contains("city") || requestText.contains("town") ||
                    requestText.contains("settlement") || requestText.contains("urban") ||
                    requestText.contains("城区") || requestText.contains("市中心") ||
                    requestText.contains("广场") || requestText.contains("集市");

            // 明清官式院落（四合院/宅院）已经有确定性生成器（ASIAN 大 footprint 会直接生成主殿+厢房+门楼+院墙）。
            // 这类请求如果走 Composite，LLM 往往会产出“多栋相同默认房子”，不符合用户预期。
            // 因此优先走单体 BuildingSpec 链路，让生成更稳定可控。
            boolean isComposite = isIsComposite(requestText, isCity);

            if (isCity) {
                AtomicBoolean hbAlive = new AtomicBoolean(true);
                AtomicReference<String> hbPhase = new AtomicReference<>("后端仍在生成城市方案");
                long hbStartMs = System.currentTimeMillis();
                startStatusHeartbeat(context.server(), player, hbAlive, hbStartMs, hbPhase);

                // 请求城市级结构
                getOrchestrator().requestCitySpec(req)
                        .orTimeout(605, TimeUnit.SECONDS)
                        .exceptionally(ex -> {
                            hbAlive.set(false);
                            FormacraftMod.LOGGER.error("Orchestrator city request failed", ex);
                            // IMPORTANT: always send packets on the server thread
                            String msg = humanizeOrchestratorFailure("CitySpec", req, ex);
                            context.server().execute(() -> ServerPlayNetworking.send(player, new ResponseBuildErrorPayload(msg)));
                            return null;
                        })
                        .thenAccept(citySpec -> {
                    if (citySpec == null) return; // already handled in exceptionally

                    // 在主线程中执行
                    context.server().execute(() -> {
                        hbPhase.set("已收到 AI 结果，正在生成城市预览");
                        ServerPlayNetworking.send(player, new ResponseBuildStatusPayload("已收到 AI 结果，正在生成城市预览…"));
                        // 对于城市结构，生成预览而不是直接建造
                        BlockPos origin = req.getPlayerPos();
                        if (origin != null && player.getEntityWorld() instanceof net.minecraft.server.world.ServerWorld serverWorld) {
                            // 生成城市结构
                            com.formacraft.server.city.CityBuilder cityBuilder =
                                    new com.formacraft.server.city.CityBuilder();
                            final com.formacraft.server.build.BuildReportContext.Reported<com.formacraft.server.build.GeneratedStructure> reported =
                                    com.formacraft.server.build.BuildReportContext.withNewReportReported(() ->
                                            BuildConstraintContext.withRequest(req, () -> cityBuilder.generate(citySpec, origin, serverWorld))
                                    );
                            final com.formacraft.server.build.GeneratedStructure generated = reported.value();
                            String terrainSummary = reported.report().summaryZh();
                            if (!terrainSummary.isBlank()) {
                                ServerPlayNetworking.send(player, new ResponseBuildStatusPayload(terrainSummary));
                            }

                            // H-layer (MVP): auto validation & repair before preview
                            com.formacraft.server.build.BuildAutoRepair.Result repair =
                                    BuildConstraintContext.withRequest(req, () ->
                                            com.formacraft.server.build.BuildAutoRepair.apply(serverWorld, java.util.Optional.empty(), generated.getBlocks())
                                    );
                            if (repair.summary() != null && !repair.summary().isBlank()) {
                                ServerPlayNetworking.send(player, new ResponseBuildStatusPayload(repair.summary()));
                            }

                            // 设置玩家 UUID
                            com.formacraft.server.build.GeneratedStructure structure = new com.formacraft.server.build.GeneratedStructure(
                                    player.getUuid(),
                                    origin,
                                    generated.getDescription(),
                                    BuildConstraintClipper.clipPlannedBlocks(repair.blocks(), req)
                            );

                            // 存储结构用于预览
                            com.formacraft.server.preview.PreviewStorage.storeStructure(player, structure);

                            // 自动发送预览
                            List<OutlineBlock> outline =
                                    com.formacraft.server.preview.OutlineGenerator.fromPlannedBlocks(structure.getBlocks());
                            sendPreviewOutline(player, outline);
                            // Send skeleton layout preview (if present in CitySpec's first structure extra)
                            try {
                                if (citySpec.getStructures() != null && !citySpec.getStructures().isEmpty()) {
                                    var sp0 = citySpec.getStructures().getFirst();
                                    if (sp0 != null && sp0.getSpec() != null && sp0.getSpec().getExtra() != null) {
                                        sendPreviewSkeleton(player, origin, sp0.getSpec().getExtra());
                                    }
                                }
                            } catch (Throwable ignored) {}
                            com.formacraft.server.preview.PreviewStorage.setPreview(player, true);

                            // 保存 CitySpec 到 PlayerSpecRepository
                            String cityId = "player_" + player.getName().getString() + "_world_" +
                                    serverWorld.getRegistryKey().getValue();
                            String cityJson = JsonUtil.toJson(citySpec);
                            com.formacraft.server.state.PlayerSpecRepository.setCitySpec(player, cityId, cityJson);

                            player.sendMessage(net.minecraft.text.Text.literal(
                                    String.format("City '%s' preview ready. Use /forma_confirm to build or /forma_cancel to cancel.",
                                            citySpec.getCityName() != null ? citySpec.getCityName() : "Unnamed")),
                                    false);
                            // 同步给自定义 ChatPanel：标记本次请求已完成（否则 120s 会误报超时）
                            ServerPlayNetworking.send(player, new ResponseBuildStatusPayload(
                                    String.format("City '%s' preview ready. Use /forma_confirm to build or /forma_cancel to cancel.",
                                            citySpec.getCityName() != null ? citySpec.getCityName() : "Unnamed")
                            ));
                            hbAlive.set(false);

                            FormacraftMod.LOGGER.info("Generated city structure preview for player {}", player.getName().getString());
                        }
                    });
                });
            } else if (isComposite) {
                AtomicBoolean hbAlive = new AtomicBoolean(true);
                AtomicReference<String> hbPhase = new AtomicReference<>("后端仍在生成复合结构方案");
                long hbStartMs = System.currentTimeMillis();
                startStatusHeartbeat(context.server(), player, hbAlive, hbStartMs, hbPhase);

                // 请求复合结构
                OrchestratorClient orchestrator = getOrchestrator();
                if (!orchestrator.checkHealth()) {
                    String endpoint = com.formacraft.common.config.ConfigManager.getOrchestratorEndpoint();
                    String errorMsg = "后端服务不可用：无法连接到 " + endpoint + "。请检查后端是否正在运行。";
                    ServerPlayNetworking.send(player, new ResponseBuildErrorPayload(errorMsg));
                    return;
                }
                orchestrator.requestCompositeSpec(req)
                        .orTimeout(605, TimeUnit.SECONDS)
                        .exceptionally(ex -> {
                            hbAlive.set(false);
                            FormacraftMod.LOGGER.error("Orchestrator composite request failed", ex);
                            String msg = humanizeOrchestratorFailure("CompositeSpec", req, ex);
                            context.server().execute(() -> ServerPlayNetworking.send(player, new ResponseBuildErrorPayload(msg)));
                            return null;
                        })
                        .thenAccept(compositeSpec -> {
                    if (compositeSpec == null) return; // already handled

                    // 在主线程中执行
                    context.server().execute(() -> {
                        hbPhase.set("已收到 AI 结果，正在生成复合结构预览");
                        ServerPlayNetworking.send(player, new ResponseBuildStatusPayload("已收到 AI 结果，正在生成复合结构预览…"));
                        // 对于复合结构，生成预览而不是直接建造
                        BlockPos origin = req.getPlayerPos();
                        if (origin != null && player.getEntityWorld() instanceof net.minecraft.server.world.ServerWorld serverWorld) {
                            // 生成结构
                            com.formacraft.server.generator.composite.CompositeStructureGenerator generator =
                                    new com.formacraft.server.generator.composite.CompositeStructureGenerator();
                            final com.formacraft.server.build.BuildReportContext.Reported<com.formacraft.server.build.GeneratedStructure> reported =
                                    com.formacraft.server.build.BuildReportContext.withNewReportReported(() ->
                                            BuildConstraintContext.withRequest(req, () -> generator.generate(compositeSpec, origin, serverWorld))
                                    );
                            final com.formacraft.server.build.GeneratedStructure generated = reported.value();
                            String terrainSummary = reported.report().summaryZh();
                            if (!terrainSummary.isBlank()) {
                                ServerPlayNetworking.send(player, new ResponseBuildStatusPayload(terrainSummary));
                            }

                            // H-layer (MVP): auto validation & repair before preview
                            com.formacraft.server.build.BuildAutoRepair.Result repair =
                                    BuildConstraintContext.withRequest(req, () ->
                                            com.formacraft.server.build.BuildAutoRepair.apply(serverWorld, java.util.Optional.empty(), generated.getBlocks())
                                    );
                            if (repair.summary() != null && !repair.summary().isBlank()) {
                                ServerPlayNetworking.send(player, new ResponseBuildStatusPayload(repair.summary()));
                            }

                            // 设置玩家 UUID
                            com.formacraft.server.build.GeneratedStructure structure = new com.formacraft.server.build.GeneratedStructure(
                                    player.getUuid(),
                                    origin,
                                    generated.getDescription(),
                                    BuildConstraintClipper.clipPlannedBlocks(repair.blocks(), req)
                            );

                            // 存储结构用于预览
                            com.formacraft.server.preview.PreviewStorage.storeStructure(player, structure);

                            // 自动发送预览
                            List<OutlineBlock> outline =
                                    com.formacraft.server.preview.OutlineGenerator.fromPlannedBlocks(structure.getBlocks());
                            sendPreviewOutline(player, outline);
                            // Send skeleton layout preview (if present in CompositeSpec's first structure extra)
                            try {
                                if (compositeSpec.getStructures() != null && !compositeSpec.getStructures().isEmpty()) {
                                    var s0 = compositeSpec.getStructures().getFirst();
                                    if (s0 != null && s0.getSpec() != null && s0.getSpec().getExtra() != null) {
                                        sendPreviewSkeleton(player, origin, s0.getSpec().getExtra());
                                    }
                                }
                            } catch (Throwable ignored) {}
                            com.formacraft.server.preview.PreviewStorage.setPreview(player, true);

                            player.sendMessage(net.minecraft.text.Text.translatable(
                                    "formacraft.preview.ready.composite"),
                                    false);
                            // 同步给自定义 ChatPanel：标记本次请求已完成（否则 120s 会误报超时）
                            ServerPlayNetworking.send(player, new ResponseBuildStatusPayload(
                                    "Composite structure preview ready. Use /forma_confirm to build or /forma_cancel to cancel."
                            ));
                            hbAlive.set(false);

                            FormacraftMod.LOGGER.info("Generated composite structure preview for player {}", player.getName().getString());
                        }
                    });
                });
            } else {
                // 请求单个建筑
                // 如果是 PATCH/MODIFY_REGION：走“增量编辑 BuildingSpec”链路
                String mode = req.getPromptMode();
                boolean isPatch = mode != null && !mode.isBlank() && !"BUILD".equalsIgnoreCase(mode.trim());
                if (isPatch) {
                    String buildingId = com.formacraft.server.state.PlayerSpecRepository.getBuildingId(player);
                    String currentJson = com.formacraft.server.state.PlayerSpecRepository.getBuildingJson(player);
                    if (buildingId == null || currentJson == null) {
                        player.sendMessage(net.minecraft.text.Text.literal("No current building spec. Generate a building first."), false);
                        return;
                    }

                    AtomicBoolean hbAlive = new AtomicBoolean(true);
                    AtomicReference<String> hbPhase = new AtomicReference<>("后端仍在生成更新方案");
                    long hbStartMs = System.currentTimeMillis();
                    startStatusHeartbeat(context.server(), player, hbAlive, hbStartMs, hbPhase);

                    OrchestratorClient orchestrator = getOrchestrator();
                    if (!orchestrator.checkHealth()) {
                        String endpoint = com.formacraft.common.config.ConfigManager.getOrchestratorEndpoint();
                        String errorMsg = "后端服务不可用：无法连接到 " + endpoint + "。请检查后端是否正在运行。";
                        ServerPlayNetworking.send(player, new ResponseBuildErrorPayload(errorMsg));
                        return;
                    }
                    orchestrator.editBuilding(buildingId, currentJson, req.getRequestText())
                            .orTimeout(605, TimeUnit.SECONDS)
                            .exceptionally(ex -> {
                                hbAlive.set(false);
                                FormacraftMod.LOGGER.error("Orchestrator edit building request failed", ex);
                                String msg = humanizeOrchestratorFailure("EditBuilding", req, ex);
                                context.server().execute(() -> ServerPlayNetworking.send(player, new ResponseBuildErrorPayload(msg)));
                                return null;
                            })
                            .thenAccept(updatedJson -> {
                        if (updatedJson == null) return; // already handled

                        context.server().execute(() -> {
                            hbPhase.set("已收到 AI 结果，正在生成更新后的预览");
                            ServerPlayNetworking.send(player, new ResponseBuildStatusPayload("已收到 AI 结果，正在生成更新后的预览…"));
                            // 更新 PlayerSpecRepository
                            com.formacraft.server.state.PlayerSpecRepository.setBuildingSpec(player, buildingId, updatedJson);

                            BuildingSpec updated = JsonUtil.fromJson(updatedJson, BuildingSpec.class);
                            if (updated == null) return;

                            BlockPos origin = req.getPlayerPos();
                            if (origin != null && player.getEntityWorld() instanceof net.minecraft.server.world.ServerWorld serverWorld) {
                                com.formacraft.server.generator.StructureGenerator generator =
                                        com.formacraft.server.generator.StructureGeneratorFactory.getGenerator(updated);
                                final com.formacraft.server.build.BuildReportContext.Reported<com.formacraft.server.build.GeneratedStructure> reported =
                                        com.formacraft.server.build.BuildReportContext.withNewReportReported(() ->
                                                BuildConstraintContext.withRequest(req, () -> generator.generate(updated, origin, serverWorld))
                                        );
                                final com.formacraft.server.build.GeneratedStructure generated = reported.value();
                                String terrainSummary = reported.report().summaryZh();
                                if (!terrainSummary.isBlank()) {
                                    ServerPlayNetworking.send(player, new ResponseBuildStatusPayload(terrainSummary));
                                }

                                // H-layer (MVP): auto validation & repair before preview
                                com.formacraft.server.build.BuildAutoRepair.Result repair =
                                        BuildConstraintContext.withRequest(req, () ->
                                                com.formacraft.server.build.BuildAutoRepair.apply(serverWorld, java.util.Optional.ofNullable(updated.getStyle()), generated.getBlocks())
                                        );
                                if (repair.summary() != null && !repair.summary().isBlank()) {
                                    ServerPlayNetworking.send(player, new ResponseBuildStatusPayload(repair.summary()));
                                }

                                // 生成阶段硬裁剪：禁区/轮廓/选区（由工具提供）
                                com.formacraft.server.build.GeneratedStructure structure = new com.formacraft.server.build.GeneratedStructure(
                                        player.getUuid(),
                                        origin,
                                        generated.getDescription(),
                                        BuildConstraintClipper.clipPlannedBlocks(repair.blocks(), req)
                                );

                                com.formacraft.server.preview.PreviewStorage.storeStructure(player, structure);
                                List<OutlineBlock> outline =
                                        com.formacraft.server.preview.OutlineGenerator.fromPlannedBlocks(structure.getBlocks());
                                sendPreviewOutline(player, outline);
                                try {
                                    if (updated.getExtra() != null) {
                                        sendPreviewSkeleton(player, origin, updated.getExtra());
                                    }
                                } catch (Throwable ignored) {}
                                com.formacraft.server.preview.PreviewStorage.setPreview(player, true);

                                player.sendMessage(net.minecraft.text.Text.translatable(
                                        "formacraft.preview.ready.updated_building"),
                                        false);
                                // 同步给自定义 ChatPanel：标记本次请求已完成（否则 120s 会误报超时）
                                ServerPlayNetworking.send(player, new ResponseBuildStatusPayload(
                                        "Updated building preview ready. Use /forma_confirm to rebuild or /forma_cancel to cancel."
                                ));
                                hbAlive.set(false);
                            }

                            // 同步给客户端，用于 UI 显示（notes 等）
                            ServerPlayNetworking.send(player, new ResponseBuildSpecPayload(updated));
                        });
                    });
                    return;
                }

                // 在发送请求前先检查后端健康状态
                OrchestratorClient orchestrator = getOrchestrator();
                if (!orchestrator.checkHealth()) {
                    String endpoint = com.formacraft.common.config.ConfigManager.getOrchestratorEndpoint();
                    String errorMsg = String.format(
                            """
                                    后端服务不可用：无法连接到 %s
                                    请检查：
                                    1. Python 后端是否正在运行
                                    2. 后端地址是否正确（可在设置中修改）
                                    3. 防火墙是否允许连接
                                    4. 如果是远程服务器，请确保端口已开放""",
                            endpoint
                    );
                    ServerPlayNetworking.send(player, new ResponseBuildErrorPayload(errorMsg));
                    FormacraftMod.LOGGER.error("Backend health check failed before sending request: {}", endpoint);
                    return;
                }

                AtomicBoolean hbAlive = new AtomicBoolean(true);
                AtomicReference<String> hbPhase = new AtomicReference<>("后端仍在生成建筑方案");
                long hbStartMs = System.currentTimeMillis();
                startStatusHeartbeat(context.server(), player, hbAlive, hbStartMs, hbPhase);

                orchestrator.requestBuildingSpec(req)
                        .orTimeout(605, TimeUnit.SECONDS)
                        .exceptionally(ex -> {
                            hbAlive.set(false);
                            FormacraftMod.LOGGER.error("Orchestrator building request failed", ex);
                            String msg = humanizeOrchestratorFailure("BuildingSpec", req, ex);
                            context.server().execute(() -> ServerPlayNetworking.send(player, new ResponseBuildErrorPayload(msg)));
                            // IMPORTANT: returning null here will still flow into thenAccept(spec -> ...),
                            // which would cause a second generic "未返回 BuildingSpec" error. We already sent the error above.
                            return null;
                        })
                        .thenAccept(spec -> {
                    // spec==null means we already handled an error in exceptionally() above.
                    if (spec == null) return;

                    // 在主线程中执行
                    context.server().execute(() -> {
                        hbPhase.set("已收到 AI 结果，正在生成建筑预览");
                        ServerPlayNetworking.send(player, new ResponseBuildStatusPayload("已收到 AI 结果，正在生成建筑预览…"));
                        // 生成预览结构
                        BlockPos origin = req.getPlayerPos();
                        if (origin != null && player.getEntityWorld() instanceof net.minecraft.server.world.ServerWorld serverWorld) {
                            // 生成结构用于预览
                            com.formacraft.server.generator.StructureGenerator generator =
                                    com.formacraft.server.generator.StructureGeneratorFactory.getGenerator(spec);
                            final com.formacraft.server.build.BuildReportContext.Reported<com.formacraft.server.build.GeneratedStructure> reported =
                                    com.formacraft.server.build.BuildReportContext.withNewReportReported(() ->
                                            BuildConstraintContext.withRequest(req, () -> generator.generate(spec, origin, serverWorld))
                                    );
                            final com.formacraft.server.build.GeneratedStructure generated = reported.value();
                            String terrainSummary = reported.report().summaryZh();
                            if (!terrainSummary.isBlank()) {
                                ServerPlayNetworking.send(player, new ResponseBuildStatusPayload(terrainSummary));
                            }

                            // H-layer (MVP): auto validation & repair before preview
                            com.formacraft.server.build.BuildAutoRepair.Result repair =
                                    BuildConstraintContext.withRequest(req, () ->
                                            com.formacraft.server.build.BuildAutoRepair.apply(serverWorld, java.util.Optional.ofNullable(spec.getStyle()), generated.getBlocks())
                                    );
                            if (repair.summary() != null && !repair.summary().isBlank()) {
                                ServerPlayNetworking.send(player, new ResponseBuildStatusPayload(repair.summary()));
                            }

                            // 生成阶段硬裁剪：禁区/轮廓/选区（由工具提供）
                            com.formacraft.server.build.GeneratedStructure structure = new com.formacraft.server.build.GeneratedStructure(
                                    player.getUuid(),
                                    origin,
                                    generated.getDescription(),
                                    BuildConstraintClipper.clipPlannedBlocks(repair.blocks(), req)
                            );

                            // 存储结构用于预览
                            com.formacraft.server.preview.PreviewStorage.storeStructure(player, structure);

                            // 自动发送预览
                            List<OutlineBlock> outline =
                                    com.formacraft.server.preview.OutlineGenerator.fromPlannedBlocks(structure.getBlocks());
                            sendPreviewOutline(player, outline);
                            try {
                                if (spec.getExtra() != null) {
                                    sendPreviewSkeleton(player, origin, spec.getExtra());
                                }
                            } catch (Throwable ignored) {}
                            com.formacraft.server.preview.PreviewStorage.setPreview(player, true);

                            player.sendMessage(net.minecraft.text.Text.translatable(
                                    "formacraft.preview.ready.building"),
                                    false);
                            // 同步给自定义 ChatPanel：标记本次请求已完成（否则 120s 会误报超时）
                            ServerPlayNetworking.send(player, new ResponseBuildStatusPayload(
                                    "Building preview ready. Use /forma_confirm to build or /forma_cancel to cancel."
                            ));
                            hbAlive.set(false);
                        }

                        // 也发送 BuildingSpec 给客户端（用于 UI 显示）
                        ServerPlayNetworking.send(player, new ResponseBuildSpecPayload(spec));
                    });
                });
            }
        }));

        // 注册确认建造数据包
        registerPayloadTypesC2S();
        ServerPlayNetworking.registerGlobalReceiver(ConfirmBuildPacket.ID, (payload, context) -> context.server().execute(() -> {
            ServerPlayerEntity player = context.player();
            if (player == null) {
                FormacraftMod.LOGGER.warn("Received confirm build from null player");
                return;
            }

            if (player.getEntityWorld() instanceof net.minecraft.server.world.ServerWorld serverWorld) {
                // 优先：按“预览已生成的结构”执行，保证与预览一致（也包含禁区/轮廓硬裁剪）
                com.formacraft.server.build.GeneratedStructure preview = com.formacraft.server.preview.PreviewStorage.getStructure(player);
                boolean hasPreview = com.formacraft.server.preview.PreviewStorage.hasPreview(player);
                if (hasPreview && preview != null) {
                    try { sendClearOutline(player); } catch (Throwable ignored) {}
                    com.formacraft.server.preview.PreviewStorage.setPreview(player, false);
                    BuildExecutionService.getInstance().enqueueBuild(serverWorld, preview);
                    try {
                        ServerPlayNetworking.send(player, new ResponseBuildStatusPayload("已确认建造：开始放置方块…（按预览结果执行）"));
                    } catch (Throwable ignored) {}
                    FormacraftMod.LOGGER.info("Player {} confirmed build (from preview) at {}",
                            player.getName().getString(), preview.getOrigin());
                    return;
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
                    } catch (Throwable ignored) {}

                    BuildExecutionService.getInstance().queueBuild(serverWorld, origin, spec, player.getUuid());
                    try {
                        ServerPlayNetworking.send(player, new ResponseBuildStatusPayload("已确认建造：开始放置方块…（重新生成）"));
                    } catch (Throwable ignored) {}
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

        ServerPlayNetworking.registerGlobalReceiver(PatchApplyPayload.ID, (payload, context) -> context.server().execute(() -> {
            ServerPlayerEntity player = context.player();
            if (player == null) return;
            if (!(player.getEntityWorld() instanceof net.minecraft.server.world.ServerWorld sw)) return;

            BlockPos origin = payload.origin();
            List<BlockPatch> patches = payload.patches();
            List<ProtectedZone> zones = payload.protectedZones();
            if (origin == null || patches == null || patches.isEmpty()) return;

            // 简单距离保护（避免恶意改图）
            double d2 = player.squaredDistanceTo(origin.getX() + 0.5, origin.getY() + 0.5, origin.getZ() + 0.5);
            double max = 96.0;
            if (d2 > max * max) return;

            // 强制过滤：禁区/保护区内的 patch 一律跳过
            List<BlockPatch> filtered = patches;
            if (zones != null && !zones.isEmpty()) {
                filtered = new ArrayList<>(patches.size());
                outer:
                for (BlockPatch p : patches) {
                    if (p == null) continue;
                    BlockPos abs = origin.add(p.dx(), p.dy(), p.dz());
                    for (ProtectedZone z : zones) {
                        if (z != null && z.contains(abs)) {
                            continue outer;
                        }
                    }
                    filtered.add(p);
                }
            }

            if (filtered.isEmpty()) return;
            com.formacraft.common.patch.history.PatchHistoryManager.applyWithHistory(sw, player.getUuid(), origin, filtered);
        }));
    }

    private static boolean isIsComposite(String requestText, boolean isCity) {
        boolean isMingQingCourtyard =
                (requestText.contains("明清") || requestText.contains("官式") || requestText.contains("ming") || requestText.contains("qing")) &&
                (requestText.contains("四合院") || requestText.contains("院落") || requestText.contains("宅院") || requestText.contains("大院") ||
                        requestText.contains("courtyard"));
        boolean isComposite = !isCity && (
                requestText.contains("要塞") || requestText.contains("fort") ||
                requestText.contains("复合") || requestText.contains("组合") ||
                requestText.contains("village") || requestText.contains("multiple") ||
                // 建筑群落/组团（避免被当成单体建筑，结果生成一个塔楼）
                requestText.contains("群落") || requestText.contains("建筑群") ||
                requestText.contains("建筑群落") || requestText.contains("组团") ||
                requestText.contains("组群") || requestText.contains("聚落") ||
                requestText.contains("多栋") || requestText.contains("多座") ||
                requestText.contains("院落群")
        );
        if (isMingQingCourtyard) {
            isComposite = false;
        }
        return isComposite;
    }

    /**
     * 注册 S2C 数据包（服务端 → 客户端）
     * 在客户端初始化时调用
     */
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
            } catch (Throwable ignored) {}
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

        // 清除预览数据包接收器
        ClientPlayNetworking.registerGlobalReceiver(ClearOutlinePayload.ID, (payload, context) -> context.client().execute(() -> {
            com.formacraft.client.preview.OutlinePreviewState.clear();
            com.formacraft.client.preview.SkeletonPreviewState.clear();
            FormacraftMod.LOGGER.info("Preview outline cleared");
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
        PayloadTypeRegistry.playC2S().register(PatchApplyPayload.ID, PatchApplyPayload.CODEC);
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
        PayloadTypeRegistry.playS2C().register(ClearOutlinePayload.ID, ClearOutlinePayload.CODEC);
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

    /** 客户端请求 Patch Apply（携带 protectedZones 以强制过滤） */
    public static void sendPatchApply(BlockPos origin, List<BlockPatch> patches, List<ProtectedZone> protectedZones) {
        MinecraftClient mc = MinecraftClient.getInstance();
        PatchApplyPayload payload = new PatchApplyPayload(origin, patches, protectedZones);
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
     * 服务端发送骨架预览（J-layer skeleton layout）。
     * @param player 目标玩家
     * @param origin 预览原点（世界坐标）
     * @param extra  任意 spec.extra（期望包含 skeletonLayout）
     */
    public static void sendPreviewSkeleton(ServerPlayerEntity player, BlockPos origin, java.util.Map<String, Object> extra) {
        if (player == null || origin == null || extra == null) return;
        Object sk = extra.get("skeletonLayout");
        if (sk == null) return;

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

