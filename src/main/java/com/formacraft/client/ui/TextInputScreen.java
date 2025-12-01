package com.formacraft.client.ui;

import com.formacraft.ai.AIResult;
import com.formacraft.ai.AIService;
import com.formacraft.ai.AIServiceManager;
import com.formacraft.ai.BuildingRequest;
import com.formacraft.common.builder.AutoBuilder;
import com.formacraft.common.builder.BuildingBlueprint;
import com.formacraft.common.builder.BuildingPlanner;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

public class TextInputScreen extends Screen {
    private TextFieldWidget input;

    private final AIService aiService = new AIServiceManager();
    private final BuildingPlanner planner = new BuildingPlanner();
    private final AutoBuilder autoBuilder = new AutoBuilder();

    public TextInputScreen() {
        super(Text.translatable("formacraft.title"));
    }

    @Override
    protected void init() {
        int cx = this.width / 2;
        int cy = this.height / 2;
        input = new TextFieldWidget(this.textRenderer, cx - 100, cy - 10, 200, 20, Text.empty());
        addDrawableChild(input);

        addDrawableChild(ButtonWidget.builder(Text.translatable("formacraft.button.generate"), b -> {
            String text = input.getText();
            MinecraftClient client = MinecraftClient.getInstance();
            if (client.player != null && client.world != null) {
                World world = client.world;
                BlockPos start = client.player.getBlockPos().add(2, 0, 2);
                String dimensionId = world.getRegistryKey().getValue().toString();

                BuildingRequest request = new BuildingRequest(text, start, dimensionId);
                AIResult result = aiService.generateBuildingPlan(request);
                BuildingBlueprint blueprint = planner.plan(request, result);

                autoBuilder.build(world, blueprint);
            }
        }).dimensions(cx - 50, cy + 20, 100, 20).build());
    }
}

