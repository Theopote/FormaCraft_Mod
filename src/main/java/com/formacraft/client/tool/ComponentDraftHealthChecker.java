package com.formacraft.client.tool;

import com.formacraft.common.component.ComponentCategory;
import com.formacraft.common.component.health.HealthCheckResult;
import com.formacraft.common.component.placement.AttachmentType;
import net.minecraft.util.math.BlockPos;

/**
 * Draft 级健康检查：不依赖 ComponentDefinition/JSON。
 */
public final class ComponentDraftHealthChecker {
    private ComponentDraftHealthChecker() {}

    public static HealthCheckResult check(ComponentCaptureDraft draft,
                                          ComponentToolState state,
                                          BlockPos fallbackMin,
                                          BlockPos fallbackMax) {
        HealthCheckResult result = new HealthCheckResult();
        if (draft == null || state == null) {
            result.add(HealthCheckResult.CheckItem.error(
                    "H0-0", "草案为空",
                    "无法检查空草案", "无法保存",
                    HealthCheckResult.FixAction.NONE, ""));
            return result;
        }

        BlockPos min = null;
        BlockPos max = null;
        if (draft.selection.explicit && draft.selection.blocks != null && !draft.selection.blocks.isEmpty()) {
            min = draft.selection.aabbMin;
            max = draft.selection.aabbMax;
            if (min == null || max == null) {
                draft.selection.updateAabbFromBlocks();
                min = draft.selection.aabbMin;
                max = draft.selection.aabbMax;
            }
        } else {
            min = draft.selection.aabbMin != null ? draft.selection.aabbMin : fallbackMin;
            max = draft.selection.aabbMax != null ? draft.selection.aabbMax : fallbackMax;
        }

        boolean hasSelection = (draft.selection.explicit && draft.selection.blocks != null && !draft.selection.blocks.isEmpty())
                || (min != null && max != null);
        if (!hasSelection) {
            result.add(HealthCheckResult.CheckItem.error(
                    "H1-1", "未选择有效方块",
                    "请先选择构件方块", "无法保存",
                    HealthCheckResult.FixAction.NONE, ""));
        } else {
            result.add(HealthCheckResult.CheckItem.ok("H1-1", "选区结构合理"));
        }

        if (draft.anchor.worldPos == null) {
            result.add(HealthCheckResult.CheckItem.error(
                    "H2-1", "未设置构件锚点",
                    "请右键点击方块设置锚点", "构件无法稳定放置",
                    HealthCheckResult.FixAction.AUTO, "自动推荐锚点（底部中心）"));
        } else {
            result.add(HealthCheckResult.CheckItem.ok("H2-1", "锚点已设置"));
        }

        // 宿主面建议（墙面类）
        AttachmentType attachment = draft.host.attachment != null ? draft.host.attachment : AttachmentType.NONE;
        boolean needsHost = attachment == AttachmentType.WALL_OPENING || attachment == AttachmentType.WALL_SURFACE;
        if (needsHost && (draft.host.referenceBlock == null || draft.host.normal == null)) {
            result.add(HealthCheckResult.CheckItem.warn(
                    "H2-4", "建议设置宿主面",
                    "墙面类构件建议选择外墙表面作为参考面",
                    "可能导致内外方向不稳定",
                    HealthCheckResult.FixAction.SUGGEST,
                    "点击「选择宿主面」并在世界中选一个外墙面"));
        } else if (needsHost) {
            result.add(HealthCheckResult.CheckItem.ok("H2-4", "宿主面已设置"));
        }

        // 方向标记
        if (draft.orientation.hasInteriorExterior) {
            boolean hasInOut = draft.orientation.insideMarkWorld != null && draft.orientation.outsideMarkWorld != null;
            if (!hasInOut) {
                result.add(HealthCheckResult.CheckItem.warn(
                        "H2-3", "需要设置内外方向标记",
                        "需要标记内侧和外侧位置",
                        "AI 可能反向放置",
                        HealthCheckResult.FixAction.SUGGEST,
                        "点击「标记内侧/外侧」并在世界中标记"));
            } else {
                result.add(HealthCheckResult.CheckItem.ok("H2-3", "方向性已设置"));
            }
        }
        if (draft.orientation.hasBottomTop) {
            boolean hasBottomTop = draft.orientation.bottomMarkWorld != null && draft.orientation.topMarkWorld != null;
            if (!hasBottomTop) {
                result.add(HealthCheckResult.CheckItem.warn(
                        "H2-3", "需要设置上下方向标记",
                        "需要标记底端和顶端位置",
                        "AI 可能反向放置",
                        HealthCheckResult.FixAction.SUGGEST,
                        "点击「标记底端/顶端」并在世界中标记"));
            }
        }

        // 偶数宽度锚点提示
        if (min != null && max != null && draft.anchor.worldPos != null) {
            int w = max.getX() - min.getX() + 1;
            int d = max.getZ() - min.getZ() + 1;
            int ax = draft.anchor.worldPos.getX() - min.getX();
            int az = draft.anchor.worldPos.getZ() - min.getZ();
            if (w % 2 == 0 && Math.abs((ax + 0.5) - (w / 2.0)) > 0.25) {
                result.add(HealthCheckResult.CheckItem.warn(
                        "H2-5", "锚点不在对称中心",
                        "构件宽度为偶数时建议锚点在中心线",
                        "可能导致对齐偏移",
                        HealthCheckResult.FixAction.NONE,
                        "建议将锚点移动到中心线"));
            }
            if (d % 2 == 0 && Math.abs((az + 0.5) - (d / 2.0)) > 0.25) {
                result.add(HealthCheckResult.CheckItem.warn(
                        "H2-5", "锚点不在对称中心",
                        "构件厚度为偶数时建议锚点在中心线",
                        "可能导致对齐偏移",
                        HealthCheckResult.FixAction.NONE,
                        "建议将锚点移动到中心线"));
            }
        }

        // 语义提示（轻量版）
        ComponentCategory cat = state.category != null ? state.category : ComponentCategory.GENERIC;
        if (cat == ComponentCategory.GENERIC) {
            result.add(HealthCheckResult.CheckItem.warn(
                    "H3-1", "构件语义不明确",
                    "建议选择构件类型（门/窗/装饰）",
                    "AI 组合能力下降",
                    HealthCheckResult.FixAction.NONE,
                    "在“语义标注”中选择合适的类型"));
        } else {
            result.add(HealthCheckResult.CheckItem.ok("H3-1", "构件语义明确"));
        }

        return result;
    }
}
