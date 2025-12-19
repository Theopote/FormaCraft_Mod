package com.formacraft.client.ui.panel;

import com.formacraft.common.model.build.BuildingSpec;
import com.formacraft.common.model.build.BuildingType;
import com.formacraft.common.model.build.BuildingStyle;
import com.formacraft.common.model.build.Materials;
import com.formacraft.common.model.build.Features;
import com.formacraft.common.model.build.Footprint;
import com.formacraft.common.network.FormaCraftNetworking;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.input.MouseInput;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;

import java.util.UUID;

/**
 * 建造确认浮层（替代 BuildPreviewScreen）
 * 不是 Screen，而是 HUD 上的一个小面板
 */
public class BuildConfirmPanel {
    
    public static final BuildConfirmPanel INSTANCE = new BuildConfirmPanel();
    
    private final MinecraftClient client = MinecraftClient.getInstance();
    
    private boolean visible = false;
    private BuildingSpec spec;
    @SuppressWarnings("unused")
    private UUID buildId; // 可选：用于区分不同建造请求（以后拓展）
    
    // 面板尺寸
    private int width = 300;
    private int height = 240;

    // 原版风格按钮（与 SettingsPanel 保持一致：ButtonWidget 渲染）
    private ButtonWidget confirmButton;
    private ButtonWidget cancelButton;
    
    private BuildConfirmPanel() {}

    private void ensureWidgets() {
        if (confirmButton != null) return;
        confirmButton = ButtonWidget.builder(Text.translatable("formacraft.preview.confirm"), b -> onConfirm())
                .dimensions(0, 0, 120, 16)
                .build();
        cancelButton = ButtonWidget.builder(Text.translatable("formacraft.preview.cancel"), b -> hide())
                .dimensions(0, 0, 120, 16)
                .build();
    }
    
    /** 请求显示确认面板 */
    public void show(BuildingSpec spec) {
        this.spec = spec;
        this.buildId = UUID.randomUUID();
        this.visible = true;
    }
    
    /** 隐藏面板 */
    public void hide() {
        this.visible = false;
        this.spec = null;
        this.buildId = null;
    }
    
    public boolean isVisible() {
        return visible;
    }
    
    /** 在 HUD 渲染时调用 */
    public void render(DrawContext context) {
        if (!visible || client == null || spec == null) return;

        ensureWidgets();
        
        int screenW = client.getWindow().getScaledWidth();
        int screenH = client.getWindow().getScaledHeight();
        
        int centerX = screenW / 2;
        int centerY = screenH / 2;
        
        int x0 = centerX - width / 2;
        int y0 = centerY - height / 2;
        int x1 = x0 + width;
        int y1 = y0 + height;
        
        // 半透明背景
        int bgColor = 0xDD000000;
        context.fill(x0, y0, x1, y1, bgColor);
        
        // 手动画边框
        int borderColor = 0xFFFFFFFF;
        context.fill(x0, y0, x1, y0 + 1, borderColor);       // 上
        context.fill(x0, y1 - 1, x1, y1, borderColor);       // 下
        context.fill(x0, y0, x0 + 1, y1, borderColor);       // 左
        context.fill(x1 - 1, y0, x1, y1, borderColor);       // 右
        
        // 标题
        int titleY = y0 + 10;
        context.drawCenteredTextWithShadow(
                client.textRenderer,
                Text.translatable("formacraft.preview.title"),
                centerX,
                titleY,
                0xFFFFFF
        );
        
        int infoY = titleY + 20;
        int lineHeight = 12;
        int textX = x0 + 12;
        
        // 建筑类型
        BuildingType type = spec.getType();
        if (type != null) {
            context.drawTextWithShadow(
                    client.textRenderer,
                    Text.translatable("formacraft.preview.type", type.name()),
                    textX, infoY, 0xCCCCCC
            );
            infoY += lineHeight;
        }
        
        // 建筑风格
        BuildingStyle style = spec.getStyle();
        if (style != null) {
            context.drawTextWithShadow(
                    client.textRenderer,
                    Text.translatable("formacraft.preview.style", style.name()),
                    textX, infoY, 0xCCCCCC
            );
            infoY += lineHeight;
        }
        
        // 尺寸信息
        Footprint footprint = spec.getFootprint();
        if (footprint != null) {
            if ("circle".equals(footprint.getShape())) {
                context.drawTextWithShadow(
                        client.textRenderer,
                        Text.translatable("formacraft.preview.radius", footprint.getRadius()),
                        textX, infoY, 0xCCCCCC
                );
            } else {
                context.drawTextWithShadow(
                        client.textRenderer,
                        Text.translatable(
                                "formacraft.preview.size",
                                footprint.getWidth(),
                                footprint.getDepth()
                        ),
                        textX, infoY, 0xCCCCCC
                );
            }
            infoY += lineHeight;
        }
        
        // 高度
        context.drawTextWithShadow(
                client.textRenderer,
                Text.translatable("formacraft.preview.height", spec.getHeight()),
                textX, infoY, 0xCCCCCC
        );
        infoY += lineHeight;
        
        // 材质信息
        Materials materials = spec.getMaterials();
        if (materials != null) {
            infoY += 4;
            context.drawTextWithShadow(
                    client.textRenderer,
                    Text.translatable("formacraft.preview.materials"),
                    textX, infoY, 0xAAAAAA
            );
            infoY += lineHeight;
            
            if (materials.getWall() != null) {
                context.drawTextWithShadow(
                        client.textRenderer,
                        Text.translatable("formacraft.preview.wall", materials.getWall()),
                        textX + 8, infoY, 0x999999
                );
                infoY += lineHeight;
            }
            
            if (materials.getRoof() != null) {
                context.drawTextWithShadow(
                        client.textRenderer,
                        Text.translatable("formacraft.preview.roof", materials.getRoof()),
                        textX + 8, infoY, 0x999999
                );
                infoY += lineHeight;
            }
        }
        
        // 特性信息
        Features features = spec.getFeatures();
        if (features != null) {
            infoY += 4;
            context.drawTextWithShadow(
                    client.textRenderer,
                    Text.translatable("formacraft.preview.features"),
                    textX, infoY, 0xAAAAAA
            );
            infoY += lineHeight;
            
            if (features.hasWindows()) {
                context.drawTextWithShadow(
                        client.textRenderer,
                        Text.translatable("formacraft.preview.has_windows"),
                        textX + 8, infoY, 0x999999
                );
                infoY += lineHeight;
            }
            
            if (features.hasStairs()) {
                context.drawTextWithShadow(
                        client.textRenderer,
                        Text.translatable("formacraft.preview.has_stairs"),
                        textX + 8, infoY, 0x999999
                );
                infoY += lineHeight;
            }
        }
        
        // AI Notes
        if (spec.getNotes() != null && !spec.getNotes().isEmpty()) {
            infoY += 4;
            context.drawTextWithShadow(
                    client.textRenderer,
                    Text.translatable("formacraft.preview.notes"),
                    textX, infoY, 0xAAAAAA
            );
            infoY += lineHeight;
            
            String notes = spec.getNotes();
            int maxWidth = width - 40;
            for (var ordered : client.textRenderer.wrapLines(Text.literal(notes), maxWidth)) {
                context.drawTextWithShadow(
                        client.textRenderer,
                        ordered,
                        textX + 8, infoY, 0x999999
                );
                infoY += lineHeight;
            }
        }
        
        // 底部按钮
        int btnY = y1 - 30;
        int btnW = 120;
        int spacing = 10;
        
        int confirmX = centerX - btnW - spacing / 2;
        int cancelX = centerX + spacing / 2;
        
        double mouseX = client.mouse.getX() / client.getWindow().getScaleFactor();
        double mouseY = client.mouse.getY() / client.getWindow().getScaleFactor();
        
        // Confirm（原版 ButtonWidget 渲染）
        confirmButton.setPosition(confirmX, btnY);
        confirmButton.setWidth(btnW);
        confirmButton.visible = true;
        confirmButton.active = true;
        confirmButton.render(context, (int) mouseX, (int) mouseY, 0.0f);
        
        // Cancel（原版 ButtonWidget 渲染）
        cancelButton.setPosition(cancelX, btnY);
        cancelButton.setWidth(btnW);
        cancelButton.visible = true;
        cancelButton.active = true;
        cancelButton.render(context, (int) mouseX, (int) mouseY, 0.0f);
    }
    
    /** 鼠标点击处理。返回 true 表示事件已被消费。 */
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (!visible || spec == null || client == null) return false;
        if (button != 0) return false; // 只处理左键

        ensureWidgets();
        
        int screenW = client.getWindow().getScaledWidth();
        int screenH = client.getWindow().getScaledHeight();
        
        int centerX = screenW / 2;
        int centerY = screenH / 2;
        
        int x0 = centerX - width / 2;
        int y0 = centerY - height / 2;
        int x1 = x0 + width;
        int y1 = y0 + height;
        
        // 如果点击不在面板范围内，也消费掉，避免点到世界
        if (mouseX < x0 || mouseX > x1 || mouseY < y0 || mouseY > y1) {
            return true;
        }
        
        int btnY = y1 - 30;
        int btnW = 120;
        int spacing = 10;
        
        int confirmX = centerX - btnW - spacing / 2;
        int cancelX = centerX + spacing / 2;
        
        Click click = new Click(mouseX, mouseY, new MouseInput(button, 0));

        confirmButton.setPosition(confirmX, btnY);
        confirmButton.setWidth(btnW);
        confirmButton.visible = true;
        confirmButton.active = true;
        if (confirmButton.mouseClicked(click, false)) return true;

        cancelButton.setPosition(cancelX, btnY);
        cancelButton.setWidth(btnW);
        cancelButton.visible = true;
        cancelButton.active = true;
        cancelButton.mouseClicked(click, false);

        return true; // 点击面板内部其他位置，也阻止传递到世界
    }
    
    /** 键盘支持：Enter 确认，Esc 取消 */
    public boolean keyPressed(int keyCode) {
        if (!visible) return false;
        
        // Esc 取消
        if (keyCode == 256) {
            hide();
            return true;
        }
        // Enter 确认（GLFW_KEY_ENTER = 257）
        if (keyCode == 257) {
            onConfirm();
            return true;
        }
        
        return false;
    }
    
    private void onConfirm() {
        if (client.player != null && spec != null) {
            BlockPos pos = client.player.getBlockPos();
            int[] origin = new int[]{pos.getX(), pos.getY(), pos.getZ()};
            FormaCraftNetworking.sendConfirmBuild(spec, origin);
        }
        hide();
    }
}
