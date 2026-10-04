package com.formacraft.server.command;

import com.formacraft.common.llm.dto.LlmPlan;
import com.formacraft.common.llm.parser.LlmPlanParser;
import com.formacraft.common.model.request.FormaRequest;
import com.formacraft.server.network.LlmPlanPreviewBuilder;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import static net.minecraft.server.command.CommandManager.*;

/** Fixed inputs for manual visual review through the production preview pipeline. */
public final class StyleVisualCases {
    public static final List<String> IDS = List.of("wood_stone", "modern_flat", "hui_white_black", "two_materials");
    private StyleVisualCases() {}

    public static LlmPlan load(String id) throws Exception {
        return load(id, net.minecraft.util.math.BlockPos.ORIGIN);
    }

    public static LlmPlan load(String id, net.minecraft.util.math.BlockPos origin) throws Exception {
        if (!IDS.contains(id)) throw new IllegalArgumentException("Unknown style case: " + id);
        try (var stream = StyleVisualCases.class.getResourceAsStream("/assets/formacraft/style_visual_cases/" + id + ".json")) {
            if (stream == null) throw new IllegalStateException("Missing style case: " + id);
            var json = com.google.gson.JsonParser.parseString(new String(stream.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
            var anchor = json.getAsJsonObject("anchor");
            anchor.addProperty("x", origin.getX());
            anchor.addProperty("y", origin.getY());
            anchor.addProperty("z", origin.getZ());
            return LlmPlanParser.parseAndValidate(json.toString());
        }
    }

    public static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
        dispatcher.register(literal("forma_style_case").requires(source -> source.hasPermissionLevel(2))
                .executes(ctx -> {
                    ctx.getSource().sendFeedback(() -> Text.literal("Style cases: " + String.join(", ", IDS)), false);
                    return 1;
                })
                .then(argument("case", StringArgumentType.word())
                        .suggests((ctx, builder) -> {
                            IDS.stream().filter(id -> id.startsWith(builder.getRemaining())).forEach(builder::suggest);
                            return builder.buildFuture();
                        })
                        .executes(ctx -> {
                            var player = ctx.getSource().getPlayer();
                            if (player == null || !(player.getEntityWorld() instanceof ServerWorld world)) return 0;
                            try {
                                String id = StringArgumentType.getString(ctx, "case");
                                var origin = player.getBlockPos();
                                var req = new FormaRequest("Style visual case " + id, origin, "south",
                                        world.getRegistryKey().getValue().toString(), "plains", null, null);
                                req.setPromptMode("BUILD");
                                req.setOutputFormat("llmplan");
                                boolean handled = LlmPlanPreviewBuilder.tryBuildPreview(player, req, load(id, origin), origin, world, new AtomicBoolean(false));
                                if (!handled) ctx.getSource().sendError(Text.literal("Style case could not produce a preview."));
                                return handled ? 1 : 0;
                            } catch (Exception e) {
                                ctx.getSource().sendError(Text.literal("Style case failed: " + e.getMessage()));
                                return 0;
                            }
                        })));
    }
}
