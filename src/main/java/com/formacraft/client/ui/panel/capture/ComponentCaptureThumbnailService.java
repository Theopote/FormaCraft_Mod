package com.formacraft.client.ui.panel.capture;

import com.formacraft.FormacraftMod;
import com.formacraft.client.component.ComponentThumbnailGenerator;
import com.formacraft.client.tool.ComponentTool;
import com.formacraft.client.tool.SelectionTool;
import com.formacraft.common.component.ComponentDefinition;
import com.formacraft.common.json.JsonUtil;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.command.argument.BlockArgumentParser;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;

/** Selection snapshots and model reads stay on the client thread; rasterization runs asynchronously. */
public final class ComponentCaptureThumbnailService {
    private BufferedImage cachedThumbnail;
    private boolean isGenerating;
    private long generationToken;
    private long lastScan;
    private String lastContent;
    private ComponentDefinition adaptedSnapshot;
    private int renderedEpoch;
    public void invalidate() {
        generationToken++; cachedThumbnail = null; lastContent = null; lastScan = 0; isGenerating = false; adaptedSnapshot = null;
    }
    public boolean isGenerating() { return isGenerating; }
    public void drawPreview(DrawContext ctx, MinecraftClient client, int x, int y, int displaySize) {
        ctx.fill(x - 1, y - 1, x + displaySize + 1, y + displaySize + 1, 0xFF444444);
        ctx.fill(x, y, x + displaySize, y + displaySize, com.formacraft.client.ui.UiTheme.CHAT_MESSAGE_BG);
        long now = System.currentTimeMillis();
        if (!isGenerating && now - lastScan >= 750) {
            lastScan = now;
            ComponentDefinition snapshot = captureSnapshot(client);
            String content = snapshot == null ? null : ComponentThumbnailGenerator.resourceEpoch() + JsonUtil.get().toJson(snapshot.blocks);
            if (!java.util.Objects.equals(content, lastContent)) {
                lastContent = content;
                generationToken++;
                if (snapshot == null) cachedThumbnail = null;
                else generate(client, snapshot);
            }
        }
        if (cachedThumbnail != null) com.formacraft.client.ui.render.ImageRenderer.renderCentered(ctx, cachedThumbnail, x, y, displaySize, displaySize);
        else ctx.drawTextWithShadow(client.textRenderer, Text.literal(isGenerating ? "生成中…" : "等待选区"), x + 4, y + displaySize / 2, 0xFFAAAAAA);
    }
    public void generateSnapshot(MinecraftClient client, ComponentDefinition snapshot) { invalidate(); adaptedSnapshot = snapshot; generate(client, snapshot); }
    private void generate(MinecraftClient client, ComponentDefinition snapshot) {
        isGenerating = true;
        renderedEpoch = ComponentThumbnailGenerator.resourceEpoch();
        long token = generationToken;
        ComponentThumbnailGenerator.generateAsync(snapshot).whenComplete((image, error) -> client.execute(() -> {
            if (token != generationToken) return;
            isGenerating = false;
            if (error != null) FormacraftMod.LOGGER.warn("Component thumbnail failed", error);
            else cachedThumbnail = image;
        }));
    }
    public void drawSnapshot(DrawContext ctx, MinecraftClient client, int x, int y, int size) {
        if (!isGenerating && adaptedSnapshot != null && renderedEpoch != ComponentThumbnailGenerator.resourceEpoch()) {
            generationToken++; generate(client, adaptedSnapshot);
        }
        ctx.fill(x, y, x + size, y + size, com.formacraft.client.ui.UiTheme.CHAT_MESSAGE_BG);
        if (cachedThumbnail != null) com.formacraft.client.ui.render.ImageRenderer.renderCentered(ctx, cachedThumbnail, x, y, size, size);
        else if (isGenerating) ctx.drawTextWithShadow(client.textRenderer, Text.literal("预览生成中…"), x + 2, y + 2, 0xFFAAAAAA);
    }
    public byte[] encodePng(ComponentDefinition def) {
        try {
            BufferedImage image = ComponentThumbnailGenerator.generateThumbnail(def);
            if (image == null) return null;
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            ImageIO.write(image, "PNG", bytes); return bytes.toByteArray();
        } catch (Exception e) { FormacraftMod.LOGGER.warn("Failed to encode component thumbnail", e); return null; }
    }
    private ComponentDefinition captureSnapshot(MinecraftClient client) {
        if (client.world == null) return null;
        var draft = ComponentTool.INSTANCE.getState().captureDraft;
        BlockPos min = draft.hasExplicitSelection() ? draft.selection.aabbMin : SelectionTool.INSTANCE.getMin();
        BlockPos max = draft.hasExplicitSelection() ? draft.selection.aabbMax : SelectionTool.INSTANCE.getMax();
        if (min == null || max == null) return null;
        if ((long)(max.getX() - min.getX() + 1) * (max.getY() - min.getY() + 1) * (max.getZ() - min.getZ() + 1) > 262144
                && !draft.hasExplicitSelection()) return null;
        Iterable<BlockPos> positions = draft.hasExplicitSelection() ? draft.selection.blocks : BlockPos.iterate(min, max);
        ComponentDefinition snapshot = new ComponentDefinition(); snapshot.blocks = new ArrayList<>();
        for (BlockPos pos : positions) {
            var state = client.world.getBlockState(pos);
            if (state.isAir()) continue;
            var block = new ComponentDefinition.BlockEntry();
            block.dx = pos.getX() - min.getX(); block.dy = pos.getY() - min.getY(); block.dz = pos.getZ() - min.getZ();
            block.block = BlockArgumentParser.stringifyBlockState(state); snapshot.blocks.add(block);
        }
        return snapshot.blocks.isEmpty() ? null : snapshot;
    }
}
