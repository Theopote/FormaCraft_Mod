package com.formacraft.server.command;

import com.formacraft.client.preview.OutlineBlock;
import com.formacraft.common.json.JsonUtil;
import com.formacraft.common.model.blueprint.Blueprint;
import com.formacraft.common.model.city.CitySpec;
import com.formacraft.common.model.build.BuildingSpec;
import com.formacraft.common.model.composite.CompositeSpec;
import com.formacraft.common.network.FormaCraftNetworking;
import com.formacraft.server.build.BuildExecutionService;
import com.formacraft.common.build.GeneratedStructure;
import com.formacraft.server.city.CityBuilder;
import com.formacraft.server.generation.GenerationHub;
import com.formacraft.common.generation.structure.StructureGenerator;
import com.formacraft.common.generation.structure.composite.CompositeStructureGenerator;
import com.formacraft.server.orchestrator.OrchestratorClient;
import com.formacraft.server.preview.OutlineGenerator;
import com.formacraft.server.preview.PreviewStorage;
import com.formacraft.server.state.PlayerSpecRepository;
import com.formacraft.server.storage.BlueprintStorage;
import com.google.gson.JsonParser;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;

import java.io.IOException;
import java.util.List;

import static net.minecraft.server.command.CommandManager.argument;
import static net.minecraft.server.command.CommandManager.literal;

/**
 * FormaCraft 命令系统
 */
public class FormaCraftCommands {
    private static boolean registered = false;

    public static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
        // 防止在 Dedicated + Common 初始化中重复注册同名命令导致崩溃
        if (registered) return;
        registered = true;

        // Undo 命令
        dispatcher.register(literal("formacraft_undo")
                .requires(source -> source.hasPermissionLevel(2))
                .executes(ctx -> {
                    var player = ctx.getSource().getPlayer();
                    if (player == null) {
                        ctx.getSource().sendError(
                                Text.literal("Only players can use this command.")
                        );
                        return 0;
                    }

                    boolean success = BuildExecutionService.getInstance()
                            .getUndoService()
                            .undoLast(player);

                    if (!success) {
                        ctx.getSource().sendError(
                                Text.literal("No FormaCraft actions to undo.")
                        );
                    } else {
                        ctx.getSource().sendFeedback(
                                () -> Text.literal("Last FormaCraft build undone."),
                                true
                        );
                    }
                    return 1;
                }));

        // 预览命令
        dispatcher.register(literal("forma_preview")
                .requires(source -> source.hasPermissionLevel(2))
                .executes(ctx -> {
                    ServerPlayerEntity player = ctx.getSource().getPlayer();
                    if (player == null) {
                        ctx.getSource().sendError(Text.translatable("formacraft.command.players_only"));
                        return 0;
                    }

                    GeneratedStructure structure = PreviewStorage.getStructure(player);
                    if (structure == null) {
                        ctx.getSource().sendError(Text.translatable("formacraft.command.preview.no_structure"));
                        return 0;
                    }

                    // 生成预览线框
                    List<OutlineBlock> outline = OutlineGenerator.fromPlannedBlocks(structure.getBlocks());
                    
                    // 发送到客户端
                    FormaCraftNetworking.sendPreviewOutline(player, outline);
                    PreviewStorage.setPreview(player, true);

                    ctx.getSource().sendFeedback(
                            () -> Text.translatable("formacraft.command.preview.showing"),
                            false
                    );
                    return 1;
                }));

        // 确认建造命令
        dispatcher.register(literal("forma_confirm")
                // 允许普通玩家确认自己当前的预览（不需要 OP 权限）
                .requires(source -> source.getEntity() instanceof ServerPlayerEntity)
                .executes(ctx -> {
                    ServerPlayerEntity player = ctx.getSource().getPlayer();
                    if (player == null) {
                        ctx.getSource().sendError(Text.literal("Only players can use this command."));
                        return 0;
                    }

                    if (!PreviewStorage.hasPreview(player)) {
                        ctx.getSource().sendError(Text.translatable("formacraft.command.preview.no_active"));
                        return 0;
                    }

                    GeneratedStructure structure = PreviewStorage.getStructure(player);
                    if (structure == null) {
                        ctx.getSource().sendError(Text.translatable("formacraft.command.preview.no_structure_to_build"));
                        return 0;
                    }

                    // 清除预览
                    FormaCraftNetworking.sendClearOutline(player);
                    PreviewStorage.setPreview(player, false);

                    // 执行建造
                    if (player.getEntityWorld() instanceof net.minecraft.server.world.ServerWorld serverWorld) {
                        BuildExecutionService.getInstance().enqueueBuild(serverWorld, structure);
                        ctx.getSource().sendFeedback(
                                () -> Text.translatable("formacraft.command.build.started"),
                                false
                        );
                    } else {
                        ctx.getSource().sendError(Text.translatable("formacraft.command.build.cannot_build_here"));
                        return 0;
                    }

                    // 清除存储（可选：保留以便再次预览）
                    // PreviewStorage.clear(player);

                    return 1;
                }));

        // 取消预览命令
        dispatcher.register(literal("forma_cancel")
                // 允许普通玩家取消自己当前的预览（不需要 OP 权限）
                .requires(source -> source.getEntity() instanceof ServerPlayerEntity)
                .executes(ctx -> {
                    ServerPlayerEntity player = ctx.getSource().getPlayer();
                    if (player == null) {
                        ctx.getSource().sendError(Text.translatable("formacraft.command.players_only"));
                        return 0;
                    }

                    // 清除预览
                    FormaCraftNetworking.sendClearOutline(player);
                    PreviewStorage.setPreview(player, false);
                    PreviewStorage.clear(player);

                    ctx.getSource().sendFeedback(
                            () -> Text.translatable("formacraft.command.preview.canceled"),
                            false
                    );
                    return 1;
                }));

        // 编辑城市命令
        dispatcher.register(literal("forma_edit_city")
                .requires(source -> source.hasPermissionLevel(2))
                .then(argument("command", StringArgumentType.greedyString())
                .executes(ctx -> {
                    ServerPlayerEntity player = ctx.getSource().getPlayer();
                    if (player == null) {
                        ctx.getSource().sendError(Text.literal("Only players can use this command."));
                        return 0;
                    }

                    String editCommand = StringArgumentType.getString(ctx, "command");
                    String cityId = PlayerSpecRepository.getCityId(player);
                    String currentJson = PlayerSpecRepository.getCityJson(player);

                    if (cityId == null || currentJson == null) {
                        ctx.getSource().sendError(Text.literal("No current city spec. Generate a city first."));
                        return 0;
                    }

                    // 调用 Orchestrator 编辑城市
                    if (!(player.getEntityWorld() instanceof ServerWorld serverWorld)) {
                        ctx.getSource().sendError(Text.literal("Cannot edit city in this world."));
                        return 0;
                    }

                    final ServerWorld finalServerWorld = serverWorld;
                    OrchestratorClient orchestrator = new OrchestratorClient("http://localhost:8000");
                    orchestrator.editCity(cityId, currentJson, editCommand)
                            .thenAcceptAsync(updatedJson -> {
                                if (updatedJson == null) {
                                    player.sendMessage(Text.literal("Failed to edit city. Please try again."), false);
                                    return;
                                }

                                // 更新 PlayerSpecRepository
                                PlayerSpecRepository.setCitySpec(player, cityId, updatedJson);

                                // 反序列化为 CitySpec
                                CitySpec updated = JsonUtil.fromJson(updatedJson, CitySpec.class);

                                // 生成新的结构
                                BlockPos origin = player.getBlockPos();
                                CityBuilder cityBuilder = new CityBuilder();
                                GeneratedStructure gs = cityBuilder.generate(updated, origin, finalServerWorld);

                                // 设置玩家 UUID
                                gs = new GeneratedStructure(
                                        player.getUuid(),
                                        origin,
                                        gs.getDescription(),
                                        gs.getBlocks()
                                );

                                // 存储结构用于预览
                                PreviewStorage.storeStructure(player, gs);

                                // 发送预览
                                List<OutlineBlock> outline = OutlineGenerator.fromPlannedBlocks(gs.getBlocks());
                                FormaCraftNetworking.sendPreviewOutline(player, outline);
                                PreviewStorage.setPreview(player, true);

                                player.sendMessage(Text.translatable(
                                        "formacraft.preview.ready.updated_city"),
                                        false);
                            }, finalServerWorld.getServer());

                    ctx.getSource().sendFeedback(
                            () -> Text.literal("Editing city..."),
                            false
                    );
                    return 1;
                })));

        // 编辑建筑命令
        dispatcher.register(literal("forma_edit_building")
                .requires(source -> source.hasPermissionLevel(2))
                .then(argument("command", StringArgumentType.greedyString())
                .executes(ctx -> {
                    ServerPlayerEntity player = ctx.getSource().getPlayer();
                    if (player == null) {
                        ctx.getSource().sendError(Text.literal("Only players can use this command."));
                        return 0;
                    }

                    String editCommand = StringArgumentType.getString(ctx, "command");
                    String buildingId = PlayerSpecRepository.getBuildingId(player);
                    String currentJson = PlayerSpecRepository.getBuildingJson(player);

                    if (buildingId == null || currentJson == null) {
                        ctx.getSource().sendError(Text.literal("No current building spec. Generate a building first."));
                        return 0;
                    }

                    // 调用 Orchestrator 编辑建筑
                    if (!(player.getEntityWorld() instanceof ServerWorld serverWorld)) {
                        ctx.getSource().sendError(Text.literal("Cannot edit building in this world."));
                        return 0;
                    }

                    final ServerWorld finalServerWorld = serverWorld;
                    OrchestratorClient orchestrator = new OrchestratorClient("http://localhost:8000");
                    orchestrator.editBuilding(buildingId, currentJson, editCommand)
                            .thenAcceptAsync(updatedJson -> {
                                if (updatedJson == null) {
                                    player.sendMessage(Text.literal("Failed to edit building. Please try again."), false);
                                    return;
                                }

                                // 更新 PlayerSpecRepository
                                PlayerSpecRepository.setBuildingSpec(player, buildingId, updatedJson);

                                // 反序列化为 BuildingSpec
                                BuildingSpec updated = JsonUtil.fromJson(updatedJson, BuildingSpec.class);

                                // 生成新的结构
                                BlockPos origin = player.getBlockPos();
                                StructureGenerator generator = GenerationHub.routeStructure(updated);
                                GeneratedStructure gs = generator.generate(updated, origin, finalServerWorld);

                                // 设置玩家 UUID
                                gs = new GeneratedStructure(
                                        player.getUuid(),
                                        origin,
                                        gs.getDescription(),
                                        gs.getBlocks()
                                );

                                // 存储结构用于预览
                                PreviewStorage.storeStructure(player, gs);

                                // 发送预览
                                List<OutlineBlock> outline = OutlineGenerator.fromPlannedBlocks(gs.getBlocks());
                                FormaCraftNetworking.sendPreviewOutline(player, outline);
                                PreviewStorage.setPreview(player, true);

                                player.sendMessage(Text.translatable(
                                        "formacraft.preview.ready.updated_building"),
                                        false);
                            }, finalServerWorld.getServer());

                    ctx.getSource().sendFeedback(
                            () -> Text.literal("Editing building..."),
                            false
                    );
                    return 1;
                })));

        // 保存蓝图命令
        dispatcher.register(literal("forma_save_blueprint")
                .requires(source -> source.hasPermissionLevel(2))
                .then(argument("name", StringArgumentType.word())
                .executes(ctx -> {
                    ServerPlayerEntity player = ctx.getSource().getPlayer();
                    if (player == null) {
                        ctx.getSource().sendError(Text.literal("Only players can use this command."));
                        return 0;
                    }

                    String name = StringArgumentType.getString(ctx, "name");
                    
                    if (!(player.getEntityWorld() instanceof ServerWorld sw)) {
                        ctx.getSource().sendError(Text.literal("Cannot save blueprint in this world."));
                        return 0;
                    }
                    
                    // 尝试获取当前 CitySpec
                    String cityJson = PlayerSpecRepository.getCityJson(player);
                    String type = "CitySpec";
                    String description = "Saved by " + player.getName().getString();
                    
                    // 如果没有 CitySpec，尝试获取 BuildingSpec
                    if (cityJson == null) {
                        String buildingJson = PlayerSpecRepository.getBuildingJson(player);
                        if (buildingJson == null) {
                            ctx.getSource().sendError(Text.literal("No CitySpec/BuildingSpec to save. Generate or load a structure first."));
                            return 0;
                        }
                        type = "BuildingSpec";
                        cityJson = buildingJson;
                    }

                    try {
                        Blueprint bp = new Blueprint();
                        bp.setName(name);
                        bp.setType(type);
                        bp.setDescription(description);
                        bp.setFormatVersion(1);
                        bp.setData(JsonParser.parseString(cityJson));

                        BlueprintStorage.saveBlueprint(sw.getServer(), bp);
                        ctx.getSource().sendFeedback(
                                () -> Text.literal("Blueprint saved: " + name),
                                false
                        );
                        return 1;
                    } catch (IOException e) {
                        ctx.getSource().sendError(Text.literal("Failed to save blueprint: " + e.getMessage()));
                        return 0;
                    }
                })));

        // 加载蓝图命令
        dispatcher.register(literal("forma_load_blueprint")
                .requires(source -> source.hasPermissionLevel(2))
                .then(argument("name", StringArgumentType.word())
                .executes(ctx -> {
                    ServerPlayerEntity player = ctx.getSource().getPlayer();
                    if (player == null) {
                        ctx.getSource().sendError(Text.literal("Only players can use this command."));
                        return 0;
                    }

                    String name = StringArgumentType.getString(ctx, "name");

                    try {
                        if (!(player.getEntityWorld() instanceof ServerWorld sw2)) {
                            ctx.getSource().sendError(Text.literal("Cannot load blueprint in this world."));
                            return 0;
                        }
                        Blueprint bp = BlueprintStorage.loadBlueprint(sw2.getServer(), name);
                        if (bp == null) {
                            ctx.getSource().sendError(Text.literal("Blueprint not found: " + name));
                            return 0;
                        }

                        if (!(player.getEntityWorld() instanceof ServerWorld serverWorld)) {
                            ctx.getSource().sendError(Text.literal("Cannot load blueprint in this world."));
                            return 0;
                        }

                        // 根据蓝图类型反序列化
                        String dataJson = bp.getData().toString();
                        BlockPos origin = player.getBlockPos();
                        GeneratedStructure gs;

                        switch (bp.getType()) {
                            case "CitySpec" -> {
                                CitySpec city = JsonUtil.fromJson(dataJson, CitySpec.class);

                                // 缓存到玩家当前 spec
                                PlayerSpecRepository.setCitySpec(player, name, dataJson);

                                // 生成结构
                                CityBuilder cityBuilder = new CityBuilder();
                                gs = cityBuilder.generate(city, origin, serverWorld);
                            }
                            case "CompositeSpec" -> {
                                CompositeSpec composite = JsonUtil.fromJson(dataJson, CompositeSpec.class);

                                // 生成结构
                                CompositeStructureGenerator generator = new CompositeStructureGenerator();
                                gs = generator.generate(composite, origin, serverWorld);
                            }
                            case "BuildingSpec" -> {
                                BuildingSpec spec = JsonUtil.fromJson(dataJson, BuildingSpec.class);

                                // 缓存到玩家当前 spec
                                PlayerSpecRepository.setBuildingSpec(player, name, dataJson);

                                // 生成结构
                                StructureGenerator generator = GenerationHub.routeStructure(spec);
                                gs = generator.generate(spec, origin, serverWorld);
                            }
                            case null, default -> {
                                ctx.getSource().sendError(Text.literal("Unknown blueprint type: " + bp.getType()));
                                return 0;
                            }
                        }

                        if (gs == null) {
                            ctx.getSource().sendError(Text.literal("Failed to generate structure from blueprint."));
                            return 0;
                        }

                        // 设置玩家 UUID
                        gs = new GeneratedStructure(
                                player.getUuid(),
                                origin,
                                "Blueprint: " + name,
                                gs.getBlocks()
                        );

                        // 存储结构用于预览
                        PreviewStorage.storeStructure(player, gs);

                        // 发送预览
                        List<OutlineBlock> outline = OutlineGenerator.fromPlannedBlocks(gs.getBlocks());
                        FormaCraftNetworking.sendPreviewOutline(player, outline);
                        PreviewStorage.setPreview(player, true);

                        ctx.getSource().sendFeedback(
                                () -> Text.literal("Loaded blueprint: " + name + " (Preview Mode). Use /forma_confirm to build or /forma_cancel to cancel."),
                                false
                        );
                        return 1;
                    } catch (IOException e) {
                        ctx.getSource().sendError(Text.literal("Failed to load blueprint: " + e.getMessage()));
                        return 0;
                    } catch (Exception e) {
                        ctx.getSource().sendError(Text.literal("Error loading blueprint: " + e.getMessage()));
                        com.formacraft.FormacraftMod.LOGGER.error("Error loading blueprint", e);
                        return 0;
                    }
                })));

        // 列出蓝图命令
        dispatcher.register(literal("forma_list_blueprints")
                .requires(source -> source.hasPermissionLevel(2))
                .executes(ctx -> {
                    ServerPlayerEntity player = ctx.getSource().getPlayer();
                    if (player == null) {
                        ctx.getSource().sendError(Text.literal("Only players can use this command."));
                        return 0;
                    }

                    try {
                        if (!(player.getEntityWorld() instanceof ServerWorld sw3)) {
                            ctx.getSource().sendError(Text.literal("Cannot list blueprints in this world."));
                            return 0;
                        }
                        List<String> list = BlueprintStorage.listBlueprints(sw3.getServer());
                        
                        if (list.isEmpty()) {
                            ctx.getSource().sendFeedback(
                                    () -> Text.literal("No blueprints found."),
                                    false
                            );
                        } else {
                            ctx.getSource().sendFeedback(
                                    () -> Text.literal("Available Blueprints (" + list.size() + "):"),
                                    false
                            );
                            for (String name : list) {
                                player.sendMessage(Text.literal("  - " + name), false);
                            }
                        }
                        return 1;
                    } catch (IOException e) {
                        ctx.getSource().sendError(Text.literal("Failed to list blueprints: " + e.getMessage()));
                        return 0;
                    }
                }));

        // BuildingMass 系统测试命令
        dispatcher.register(literal("formacraft_test_mass")
                .requires(source -> source.hasPermissionLevel(2))
                .executes(ctx -> {
                    ServerPlayerEntity player = ctx.getSource().getPlayer();
                    if (player == null) {
                        ctx.getSource().sendError(Text.literal("Only players can use this command."));
                        return 0;
                    }

                    // 运行 BuildingMass 系统测试
                    try {
                        com.formacraft.common.mass.integration.BuildingMassSystemTest.runBasicTest();
                        ctx.getSource().sendFeedback(
                                () -> Text.literal("BuildingMass System Test completed. Check server logs for details."),
                                false
                        );
                        return 1;
                    } catch (Exception e) {
                        ctx.getSource().sendError(Text.literal("BuildingMass System Test failed: " + e.getMessage()));
                        com.formacraft.FormacraftMod.LOGGER.error("BuildingMass System Test failed", e);
                        return 0;
                    }
                }));
    }
}
