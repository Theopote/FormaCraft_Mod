package com.formacraft.client.preview;

import com.formacraft.common.layout.LayoutSite;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.util.math.Direction;

import java.util.List;

/**
 * LayoutSiteHudOverlay（站点 HUD 预览）
 * 
 * HUD 预览：把 sites 画在屏幕左上角小地图式列表
 * 
 * 这会让你"立刻看到差异化效果"：
 * - 站点是否被轮廓裁切
 * - 是否避开禁区
 * - 是否落在选区内
 * - 朝向是否正确
 * 
 * 如果你想画"世界中的线框"，下一步我会给你 WorldRenderEvents 的版本。
 */
@Environment(EnvType.CLIENT)
@SuppressWarnings("deprecation")
public class LayoutSiteHudOverlay implements HudRenderCallback {

    /**
     * 注册 HUD Overlay
     */
    public static void register() {
        HudRenderCallback.EVENT.register(new LayoutSiteHudOverlay());
    }

    @Override
    public void onHudRender(DrawContext ctx, net.minecraft.client.render.RenderTickCounter tickCounter) {
        if (!LayoutSitePreviewState.isEnabled()) {
            return;
        }

        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.player == null) {
            return;
        }
        if (client.currentScreen != null) {
            return; // 避免遮挡 Screen
        }

        List<LayoutSite> sites = LayoutSitePreviewState.getSites();
        if (sites.isEmpty()) {
            return;
        }

        int x = 8;
        int y = 8;

        // 背景
        int w = 220;
        int h = Math.min(120, 14 + sites.size() * 10);
        ctx.fill(x - 2, y - 2, x + w + 2, y + h + 2, 0x88000000);

        ctx.drawTextWithShadow(client.textRenderer, "Cluster Sites: " + sites.size(), x, y, 0xFFFFFF);
        y += 12;

        int shown = Math.min(10, sites.size());
        for (int i = 0; i < shown; i++) {
            LayoutSite s = sites.get(i);
            String line = String.format(
                    "#%d  (%d,%d,%d)  %s  tag=%s",
                    i,
                    s.anchor.getX(), s.anchor.getY(), s.anchor.getZ(),
                    dirArrow(s.facing),
                    s.tag
            );
            ctx.drawTextWithShadow(client.textRenderer, line, x, y, 0xDDDDDD);
            y += 10;
        }

        if (sites.size() > shown) {
            ctx.drawTextWithShadow(client.textRenderer, "...", x, y, 0xAAAAAA);
        }
    }

    /**
     * 方向箭头
     */
    private static String dirArrow(Direction d) {
        return switch (d) {
            case NORTH -> "↑";
            case SOUTH -> "↓";
            case EAST -> "→";
            case WEST -> "←";
            default -> "?";
        };
    }
}

