package com.formacraft.client.interaction;

import com.formacraft.client.ui.FormacraftUIState;
import com.formacraft.common.logging.FcaLog;
import com.formacraft.config.SettingsConfig;
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

    private static final FcaLog LOG = FcaLog.of("CursorRaycastHelper");

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
        return SettingsConfig.effectiveInteractionReach();
    }

    /**
     * 使用“光标在屏幕中的位置”进行 RayCast。
     *
     * 说明：
     * - 使用 scaled 坐标（与 HUD/UI 命中测试一致），避免高 DPI/缩放导致的边缘偏移
     * - 不依赖 crosshairTarget；但上层可以选择把结果写回 crosshairTarget 来复用原版交互
     */
    public static HitResult raycastFromCursor(float tickDelta, double distance) {
        // 兼容旧调用：用 options 的 fov（不含动态 FOV），优先推荐调用带 fov 参数的重载
        double fov = 70.0;
        try {
            fov = client.options.getFov().getValue();
        } catch (Throwable t) {
            LOG.debug("read FOV failed", t);
        }
        return raycastFromCursor(tickDelta, distance, fov);
    }

    /**
     * 使用“光标在屏幕中的位置”进行 RayCast（使用传入的实际 FOV）。
     * @param fovDegrees 使用 GameRenderer 实际渲染用的 FOV（含动态 FOV）时可避免边缘偏移
     */
    public static HitResult raycastFromCursor(float tickDelta, double distance, double fovDegrees) {
        Entity camera = client.getCameraEntity();
        if (camera == null || client.world == null) return null;

        double scale = client.getWindow().getScaleFactor();
        double mouseX = client.mouse.getX() / scale; // scaled coords
        double mouseY = client.mouse.getY() / scale;
        double w = client.getWindow().getScaledWidth();
        double h = client.getWindow().getScaledHeight();
        if (w <= 0 || h <= 0) return null;

        // 转换为 -1 ~ 1 的 NDC 坐标
        double ndcX = (mouseX / w) * 2.0 - 1.0;
        double ndcY = 1.0 - (mouseY / h) * 2.0;

        // 摄像机方向 & FOV
        double fov = fovDegrees > 1e-3 ? fovDegrees : 70.0;
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
        // 供工具系统复用：缓存命中结果
        lastHit = hit;
        if (hit instanceof BlockHitResult bhr && bhr.getType() == HitResult.Type.BLOCK) {
            lastBlockHit = bhr;
        } else {
            lastBlockHit = null;
        }
        return hit;
    }
}

