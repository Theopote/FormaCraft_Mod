package com.formacraft.common.network;

import com.formacraft.common.model.build.BuildingSpec;
import com.formacraft.common.model.request.FormaRequest;
import com.formacraft.common.network.packet.PreviewOutlinePacket;
import com.formacraft.common.network.packet.RequestBuildPacket;
import com.formacraft.common.network.packet.ResponseBuildSpecPacket;
import com.formacraft.common.network.ConfirmBuildPacket;
import com.formacraft.client.preview.OutlineBlock;
import com.formacraft.server.build.BuildExecutionService;
import com.formacraft.server.orchestrator.OrchestratorClient;
import com.formacraft.FormacraftMod;

import java.util.List;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
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
    public static final Identifier PREVIEW_OUTLINE = Identifier.of("formacraft", "preview_outline");
    public static final Identifier CLEAR_OUTLINE = Identifier.of("formacraft", "clear_outline");

    // 后端客户端（应该从配置读取，这里先硬编码）
    private static final OrchestratorClient ORCHESTRATOR = new OrchestratorClient("http://localhost:8000");

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

    /**
     * 注册 C2S 数据包（客户端 → 服务端）
     * 在服务端初始化时调用
     */
    public static void registerC2S() {
        // 注册数据包类型
        PayloadTypeRegistry.playC2S().register(RequestBuildPayload.ID, RequestBuildPayload.CODEC);

        // 注册接收器
        ServerPlayNetworking.registerGlobalReceiver(RequestBuildPayload.ID, (payload, context) -> {
            context.server().execute(() -> {
                ServerPlayerEntity player = context.player();
                if (player == null) {
                    FormacraftMod.LOGGER.warn("Received build request from null player");
                    return;
                }

                FormaRequest req = payload.request();
                FormacraftMod.LOGGER.info("Received build request from player {}: {}", 
                        player.getName().getString(), req.getRequestText());

                // 检查应该请求什么类型的结构
                String requestText = req.getRequestText().toLowerCase();
                boolean isCity = requestText.contains("城市") || requestText.contains("城镇") ||
                        requestText.contains("city") || requestText.contains("town") ||
                        requestText.contains("settlement") || requestText.contains("urban") ||
                        requestText.contains("城区") || requestText.contains("市中心") ||
                        requestText.contains("广场") || requestText.contains("集市");
                boolean isComposite = !isCity && (
                        requestText.contains("要塞") || requestText.contains("fort") ||
                        requestText.contains("复合") || requestText.contains("组合") ||
                        requestText.contains("village") || requestText.contains("multiple")
                );

                if (isCity) {
                    // 请求城市级结构
                    ORCHESTRATOR.requestCitySpec(req).thenAccept(citySpec -> {
                        if (citySpec == null) {
                            FormacraftMod.LOGGER.error("Failed to get CitySpec from orchestrator");
                            return;
                        }

                        // 在主线程中执行
                        context.server().execute(() -> {
                            // 对于城市结构，生成预览而不是直接建造
                            BlockPos origin = req.getPlayerPos();
                            if (origin != null && player.getEntityWorld() instanceof net.minecraft.server.world.ServerWorld serverWorld) {
                                // 生成城市结构
                                com.formacraft.server.city.CityBuilder cityBuilder = 
                                        new com.formacraft.server.city.CityBuilder();
                                com.formacraft.server.build.GeneratedStructure structure = 
                                        cityBuilder.generate(citySpec, origin, serverWorld);
                                
                                // 设置玩家 UUID
                                structure = new com.formacraft.server.build.GeneratedStructure(
                                        player.getUuid(),
                                        origin,
                                        structure.getDescription(),
                                        structure.getBlocks()
                                );
                                
                                // 存储结构用于预览
                                com.formacraft.server.preview.PreviewStorage.storeStructure(player, structure);
                                
                                // 自动发送预览
                                List<com.formacraft.client.preview.OutlineBlock> outline = 
                                        com.formacraft.server.preview.OutlineGenerator.fromPlannedBlocks(structure.getBlocks());
                                sendPreviewOutline(player, outline);
                                com.formacraft.server.preview.PreviewStorage.setPreview(player, true);
                                
                                // 保存 CitySpec 到 PlayerSpecRepository
                                String cityId = "player_" + player.getName().getString() + "_world_" + 
                                        serverWorld.getRegistryKey().getValue();
                                String cityJson = com.formacraft.common.json.JsonUtil.toJson(citySpec);
                                com.formacraft.server.state.PlayerSpecRepository.setCitySpec(player, cityId, cityJson);
                                
                                player.sendMessage(net.minecraft.text.Text.literal(
                                        String.format("City '%s' preview ready. Use /forma_confirm to build or /forma_cancel to cancel.", 
                                                citySpec.getCityName() != null ? citySpec.getCityName() : "Unnamed")),
                                        false);
                                
                                FormacraftMod.LOGGER.info("Generated city structure preview for player {}", player.getName().getString());
                            }
                        });
                    });
                } else if (isComposite) {
                    // 请求复合结构
                    ORCHESTRATOR.requestCompositeSpec(req).thenAccept(compositeSpec -> {
                        if (compositeSpec == null) {
                            FormacraftMod.LOGGER.error("Failed to get CompositeSpec from orchestrator");
                            return;
                        }

                        // 在主线程中执行
                        context.server().execute(() -> {
                            // 对于复合结构，生成预览而不是直接建造
                            BlockPos origin = req.getPlayerPos();
                            if (origin != null && player.getEntityWorld() instanceof net.minecraft.server.world.ServerWorld serverWorld) {
                                // 生成结构
                                com.formacraft.server.generator.composite.CompositeStructureGenerator generator = 
                                        new com.formacraft.server.generator.composite.CompositeStructureGenerator();
                                com.formacraft.server.build.GeneratedStructure structure = 
                                        generator.generate(compositeSpec, origin, serverWorld);
                                
                                // 设置玩家 UUID
                                structure = new com.formacraft.server.build.GeneratedStructure(
                                        player.getUuid(),
                                        origin,
                                        structure.getDescription(),
                                        structure.getBlocks()
                                );
                                
                                // 存储结构用于预览
                                com.formacraft.server.preview.PreviewStorage.storeStructure(player, structure);
                                
                                // 自动发送预览
                                List<com.formacraft.client.preview.OutlineBlock> outline = 
                                        com.formacraft.server.preview.OutlineGenerator.fromPlannedBlocks(structure.getBlocks());
                                sendPreviewOutline(player, outline);
                                com.formacraft.server.preview.PreviewStorage.setPreview(player, true);
                                
                                player.sendMessage(net.minecraft.text.Text.literal(
                                        "Composite structure preview ready. Use /forma_confirm to build or /forma_cancel to cancel."),
                                        false);
                                
                                FormacraftMod.LOGGER.info("Generated composite structure preview for player {}", player.getName().getString());
                            }
                        });
                    });
                } else {
                    // 请求单个建筑
                    ORCHESTRATOR.requestBuildingSpec(req).thenAccept(spec -> {
                        if (spec == null) {
                            FormacraftMod.LOGGER.error("Failed to get BuildingSpec from orchestrator");
                            return;
                        }

                        // 在主线程中执行
                        context.server().execute(() -> {
                            // 生成预览结构
                            BlockPos origin = req.getPlayerPos();
                            if (origin != null && player.getEntityWorld() instanceof net.minecraft.server.world.ServerWorld serverWorld) {
                                // 生成结构用于预览
                                com.formacraft.server.generator.StructureGenerator generator = 
                                        com.formacraft.server.generator.StructureGeneratorFactory.getGenerator(spec);
                                com.formacraft.server.build.GeneratedStructure structure = 
                                        generator.generate(spec, origin, serverWorld);
                                
                                // 设置玩家 UUID
                                structure = new com.formacraft.server.build.GeneratedStructure(
                                        player.getUuid(),
                                        origin,
                                        structure.getDescription(),
                                        structure.getBlocks()
                                );
                                
                                // 存储结构用于预览
                                com.formacraft.server.preview.PreviewStorage.storeStructure(player, structure);
                                
                                // 自动发送预览
                                List<com.formacraft.client.preview.OutlineBlock> outline = 
                                        com.formacraft.server.preview.OutlineGenerator.fromPlannedBlocks(structure.getBlocks());
                                sendPreviewOutline(player, outline);
                                com.formacraft.server.preview.PreviewStorage.setPreview(player, true);
                                
                                player.sendMessage(net.minecraft.text.Text.literal(
                                        "Building preview ready. Use /forma_confirm to build or /forma_cancel to cancel."),
                                        false);
                            }
                            
                            // 也发送 BuildingSpec 给客户端（用于 UI 显示）
                            ServerPlayNetworking.send(player, new ResponseBuildSpecPayload(spec));
                        });
                    });
                }
            });
        });

        // 注册确认建造数据包
        PayloadTypeRegistry.playC2S().register(ConfirmBuildPacket.ID, ConfirmBuildPacket.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(ConfirmBuildPacket.ID, (payload, context) -> {
            context.server().execute(() -> {
                ServerPlayerEntity player = context.player();
                if (player == null) {
                    FormacraftMod.LOGGER.warn("Received confirm build from null player");
                    return;
                }

                if (player.getEntityWorld() instanceof net.minecraft.server.world.ServerWorld serverWorld) {
                    BuildingSpec spec = payload.spec();
                    int[] originArray = payload.origin();
                    if (originArray != null && originArray.length == 3) {
                        net.minecraft.util.math.BlockPos origin = new net.minecraft.util.math.BlockPos(
                                originArray[0], originArray[1], originArray[2]
                        );
                        // 使用玩家 UUID 创建 GeneratedStructure
                        BuildExecutionService.getInstance().queueBuild(
                                serverWorld, 
                                origin, 
                                spec, 
                                player.getUuid()
                        );
                        FormacraftMod.LOGGER.info("Player {} confirmed build at {}", 
                                player.getName().getString(), origin);
                    }
                }
            });
        });
    }

    /**
     * 注册 S2C 数据包（服务端 → 客户端）
     * 在客户端初始化时调用
     */
    public static void registerS2C() {
        // 注册数据包类型
        PayloadTypeRegistry.playS2C().register(ResponseBuildSpecPayload.ID, ResponseBuildSpecPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(PreviewOutlinePayload.ID, PreviewOutlinePayload.CODEC);
        PayloadTypeRegistry.playS2C().register(ClearOutlinePayload.ID, ClearOutlinePayload.CODEC);

        // 注册接收器
        ClientPlayNetworking.registerGlobalReceiver(ResponseBuildSpecPayload.ID, (payload, context) -> {
            context.client().execute(() -> {
                BuildingSpec spec = payload.spec();
                FormacraftMod.LOGGER.info("Received BuildingSpec from server: {}", spec.getType());

                // 添加到聊天面板（显示 AI 回复）
                String aiResponse = "已生成建筑规格：" + (spec.getType() != null ? spec.getType().name() : "Unknown");
                if (spec.getNotes() != null && !spec.getNotes().isEmpty()) {
                    aiResponse += "\n" + spec.getNotes();
                }
                com.formacraft.client.ui.FormaCraftHudOverlay.CHAT_PANEL.addAIMessage(aiResponse, spec);
                
                // 显示确认面板（替代 BuildPreviewScreen）
                com.formacraft.client.ui.panel.BuildConfirmPanel.INSTANCE.show(spec);
            });
        });

        // 预览线框数据包接收器
        ClientPlayNetworking.registerGlobalReceiver(PreviewOutlinePayload.ID, (payload, context) -> {
            context.client().execute(() -> {
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
            });
        });

        // 清除预览数据包接收器
        ClientPlayNetworking.registerGlobalReceiver(ClearOutlinePayload.ID, (payload, context) -> {
            context.client().execute(() -> {
                com.formacraft.client.preview.OutlinePreviewState.clear();
                FormacraftMod.LOGGER.info("Preview outline cleared");
            });
        });
    }

    /**
     * 客户端发送建筑请求
     * @param request 建筑请求
     */
    public static void sendBuildRequest(FormaRequest request) {
        ClientPlayNetworking.send(new RequestBuildPayload(request));
    }

    /**
     * 客户端确认建造
     * @param spec 建筑规格
     * @param origin 建造原点 [x, y, z]
     */
    public static void sendConfirmBuild(com.formacraft.common.model.build.BuildingSpec spec, int[] origin) {
        ClientPlayNetworking.send(new ConfirmBuildPacket(spec, origin));
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
     * 服务端清除预览
     * @param player 目标玩家
     */
    public static void sendClearOutline(ServerPlayerEntity player) {
        ServerPlayNetworking.send(player, new ClearOutlinePayload());
    }
}

