package com.formacraft.server.network.compat;

import com.formacraft.common.json.JsonUtil;
import com.formacraft.common.model.build.BuildingSpec;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * ACTIVE_COMPAT 事务边界：旧 BuildingSpec 编辑在迁移退出期间不得损坏玩家存档。
 * <p>
 * 契约（见 {@code docs/refactoring/ARCHITECTURE_AUDIT.md}）：
 * <ol>
 *   <li>后端返回结果 ≠ 事务提交；只有预览真正交付后才写回规格仓库。</li>
 *   <li>解析失败 / 无生成器 / 生成失败时，保留玩家原有 BuildingSpec。</li>
 *   <li>请求门禁在预览流程结束（含失败）之后才释放，不在编排请求返回时释放。</li>
 * </ol>
 * 本类无 Minecraft 运行时依赖，便于回归。
 */
public final class LegacyEditPreviewScope {

    private final AtomicBoolean released = new AtomicBoolean(false);
    private final Runnable releaseAction;
    private final AtomicBoolean previewDelivered = new AtomicBoolean(false);

    public LegacyEditPreviewScope(Runnable releaseAction) {
        this.releaseAction = releaseAction;
    }

    /** True once the preview was delivered to the player. */
    public boolean isPreviewDelivered() {
        return previewDelivered.get();
    }

    /** Mark the preview as delivered (only after a successful delivery). */
    public void markPreviewDelivered() {
        previewDelivered.set(true);
    }

    /** True when the request gate has been released. */
    public boolean isReleased() {
        return released.get();
    }

    /** Idempotent release; safe to call from any terminal path. */
    public void release() {
        if (!released.compareAndSet(false, true)) {
            return;
        }
        if (releaseAction != null) {
            releaseAction.run();
        }
    }

    /**
     * Parse the edited spec. Never throws; returns {@code null} when the payload is
     * not a valid BuildingSpec so callers can keep the previous spec untouched.
     */
    public static BuildingSpec parseSpec(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            BuildingSpec spec = JsonUtil.fromJson(json, BuildingSpec.class);
            return (spec == null) ? null : spec;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * Commit the edited spec to the player's repository.
     *
     * @return true when the spec was written; false when the preview was not delivered
     *         and the previous spec must be kept.
     */
    public static boolean commitSpec(UUID playerId, String buildingId, String json, boolean delivered) {
        if (!delivered) {
            return false;
        }
        if (playerId == null || buildingId == null || json == null) {
            return false;
        }
        com.formacraft.server.state.PlayerSpecRepository.setBuildingSpec(playerId, buildingId, json);
        return true;
    }
}
