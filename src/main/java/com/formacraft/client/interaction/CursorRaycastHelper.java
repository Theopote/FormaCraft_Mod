package com.formacraft.client.interaction;

import com.formacraft.client.ui.FormacraftUIState;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;

/**
 * 将“系统光标位置”转换为世界 RayCast（不依赖十字准星）。
 */
@Environment(EnvType.CLIENT)
public final class CursorRaycastHelper {
    private CursorRaycastHelper() {}

    private static final MinecraftClient client = MinecraftClient.getInstance();

    private static BlockHitResult lastBlockHit = null;
    private static HitResult lastHit = null;

    public static void clear() {
        lastBlockHit = null;
        lastHit = null;
    }

    public static void tick(float tickDelta) {
        if (!FormacraftUIState.isOpen) {
            clear();
            return;
        }
        if (client.player == null || client.world == null) {
            clear();
            return;
        }
        lastHit = raycastFromCursor(tickDelta, getReachDistance());
        if (lastHit instanceof BlockHitResult bhr && bhr.getType() == HitResult.Type.BLOCK) {
            lastBlockHit = bhr;
        } else {
            lastBlockHit = null;
        }
    }

    public static BlockHitResult getLastBlockHit() {
        return lastBlockHit;
    }

    public static HitResult getLastHit() {
        return lastHit;
    }

    private static double getReachDistance() {
        try {
            if (client.player != null) {
                return client.player.getBlockInteractionRange();
            }
        } catch (Throwable ignored) {}
        return 4.5;
    }

    /**
     * 使用“光标在屏幕中的位置”进行 RayCast。
     *
     * 说明：
     * - 使用 window 坐标（像素）而不是 scaled 坐标，避免缩放误差
     * - 不依赖 crosshairTarget；但上层可以选择把结果写回 crosshairTarget 来复用原版交互
     */
    public static HitResult raycastFromCursor(float tickDelta, double distance) {
        Entity camera = client.getCameraEntity();
        if (camera == null || client.world == null) return null;

        double mouseX = client.mouse.getX(); // window coords
        double mouseY = client.mouse.getY();
        double w = client.getWindow().getWidth();
        double h = client.getWindow().getHeight();
        if (w <= 0 || h <= 0) return null;

        // 转换为 -1 ~ 1 的 NDC 坐标
        double ndcX = (mouseX / w) * 2.0 - 1.0;
        double ndcY = 1.0 - (mouseY / h) * 2.0;

        // 摄像机方向 & FOV
        double fov = 70.0;
        try {
            fov = client.options.getFov().getValue();
        } catch (Throwable ignored) {}
        double aspect = w / h;
        double tan = Math.tan(Math.toRadians(fov / 2.0));

        Vec3d look = camera.getRotationVec(tickDelta);
        Vec3d upWorld = new Vec3d(0, 1, 0);

        // right = look x up
        Vec3d right = look.crossProduct(upWorld);
        if (right.lengthSquared() < 1e-6) {
            // 极端情况：look 几乎与 up 平行，给一个兜底 right
            right = new Vec3d(1, 0, 0);
        } else {
            right = right.normalize();
        }

        // 重新正交化 up，避免 look 俯仰时出现畸变
        Vec3d up = right.crossProduct(look).normalize();

        Vec3d rayDir = look
                .add(right.multiply(ndcX * tan * aspect))
                .add(up.multiply(ndcY * tan))
                .normalize();

        Vec3d start = camera.getCameraPosVec(tickDelta);
        Vec3d end = start.add(rayDir.multiply(distance));

        HitResult hit = client.world.raycast(
                new RaycastContext(
                        start,
                        end,
                        RaycastContext.ShapeType.OUTLINE,
                        RaycastContext.FluidHandling.NONE,
                        camera
                )
        );
        return hit;
    }
}

