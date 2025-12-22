package com.formacraft.client.world;

import com.formacraft.client.ui.FormacraftUIState;
import com.formacraft.client.ui.input.InputRouter;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.Camera;
import net.minecraft.entity.Entity;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.Objects;

/**
 * UI 打开时：用“系统光标”进行世界 RayCast，并绘制方块 hover 高亮边框。
 *
 * 目标交互规则：
 * - 光标在面板内：UI 接管输入，不做世界 hover
 * - 光标在面板外：光标驱动世界交互（hover 边框 + 交互目标）
 *
 * 注意：此处不依赖十字准星渲染；但会同步更新 client.crosshairTarget，
 * 以便复用原版的交互逻辑（左键破坏/右键使用）。
 */
@Environment(EnvType.CLIENT)
public final class CursorWorldInteraction {
    private CursorWorldInteraction() {}

    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(CursorWorldInteraction::tick);
    }

    private static void tick(MinecraftClient client) {
        if (client == null) return;
        if (!FormacraftUIState.isOpen) return;
        if (client.currentScreen != null) return;
        if (client.world == null) return;

        // 鼠标在 UI 面板内：不做世界 hover（避免“隔着 UI 高亮世界”）
        double mx = InputRouter.getMouseX();
        double my = InputRouter.getMouseY();
        if (InputRouter.isMouseInsideUI(mx, my)) {
            return;
        }

        Camera cam = client.gameRenderer.getCamera();
        Entity cameraEntity = cam.getFocusedEntity();
        if (cameraEntity == null) return;

        double reach = getReachDistance(client);

        // 同步交互目标：让原版“破坏/放置/使用”能跟随光标命中结果
        client.crosshairTarget = raycastFromMouse(client, cam, cameraEntity, reach);
    }

    private static double getReachDistance(MinecraftClient client) {
        // 优先使用玩家交互范围（会随模式/属性变化）
        try {
            if (client.player != null) {
                return client.player.getBlockInteractionRange();
            }
        } catch (Throwable ignored) {}
        return 4.5;
    }

    /**
     * 使用鼠标屏幕位置反算射线方向，并进行方块 RayCast。
     */
    private static HitResult raycastFromMouse(MinecraftClient client, Camera cam, Entity cameraEntity, double reachDistance) {
        int sw = client.getWindow().getScaledWidth();
        int sh = client.getWindow().getScaledHeight();
        if (sw <= 0 || sh <= 0) {
            return BlockHitResult.createMissed(Vec3d.ZERO, net.minecraft.util.math.Direction.UP, net.minecraft.util.math.BlockPos.ORIGIN);
        }

        double mouseX = InputRouter.getMouseX();
        double mouseY = InputRouter.getMouseY();

        // NDC: [-1,1]
        double nx = (mouseX / (double) sw) * 2.0 - 1.0;
        double ny = 1.0 - (mouseY / (double) sh) * 2.0;

        // FOV & aspect
        double fov = 70.0;
        try {
            fov = client.options.getFov().getValue();
        } catch (Throwable ignored) {
        }
        double tan = Math.tan(Math.toRadians(fov) / 2.0);
        double aspect = sw / (double) sh;

        // 视空间：forward = -Z（OpenGL 习惯）；中心点应与相机视线一致
        Vector3f dir = new Vector3f(
                (float) (nx * aspect * tan),
                (float) (ny * tan),
                -1.0f
        ).normalize();

        Quaternionf rot = cam.getRotation();
        rot.transform(dir);

        Vec3d origin = cam.getPos();
        Vec3d direction = new Vec3d(dir.x, dir.y, dir.z);
        Vec3d end = origin.add(direction.multiply(reachDistance));

        RaycastContext ctx = new RaycastContext(
                origin,
                end,
                RaycastContext.ShapeType.OUTLINE,
                RaycastContext.FluidHandling.NONE,
                cameraEntity
        );
        return Objects.requireNonNull(client.world).raycast(ctx);
    }
}

