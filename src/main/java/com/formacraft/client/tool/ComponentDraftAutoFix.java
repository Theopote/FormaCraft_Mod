package com.formacraft.client.tool;

import com.formacraft.common.component.placement.AttachmentType;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

/**
 * Draft 级自动修复：仅处理锚点/宿主面/方向标记。
 */
public final class ComponentDraftAutoFix {
    private ComponentDraftAutoFix() {}

    public static FixReport apply(ComponentCaptureDraft draft,
                                  ComponentToolState state,
                                  BlockPos fallbackMin,
                                  BlockPos fallbackMax) {
        FixReport report = new FixReport();
        if (draft == null || state == null) return report;

        Bounds bounds = resolveBounds(draft, fallbackMin, fallbackMax);
        boolean hasBounds = bounds.min != null && bounds.max != null;

        // Fix: anchor missing -> bottom center
        if (draft.anchor.worldPos == null && hasBounds) {
            int cx = (bounds.min.getX() + bounds.max.getX()) / 2;
            int cz = (bounds.min.getZ() + bounds.max.getZ()) / 2;
            draft.anchor.worldPos = new BlockPos(cx, bounds.min.getY(), cz);
            report.add("设置锚点为底部中心");
        }

        // Fix: inside/outside marks missing -> derive from host or anchor
        if (draft.orientation.hasInteriorExterior) {
            if (draft.orientation.insideMarkWorld == null && draft.anchor.worldPos != null) {
                draft.orientation.insideMarkWorld = draft.anchor.worldPos;
                report.add("补全内侧标记（使用锚点）");
            }
            if (draft.orientation.outsideMarkWorld == null) {
                if (draft.host.normal != null && draft.anchor.worldPos != null) {
                    draft.orientation.outsideMarkWorld = draft.anchor.worldPos.offset(draft.host.normal);
                    report.add("补全外侧标记（沿宿主法向）");
                } else if (draft.orientation.insideMarkWorld != null) {
                    draft.orientation.outsideMarkWorld = draft.orientation.insideMarkWorld.offset(Direction.SOUTH);
                    report.add("补全外侧标记（默认向 SOUTH）");
                }
            }
            // 如果内外齐全，更新朝向
            if (draft.orientation.insideMarkWorld != null && draft.orientation.outsideMarkWorld != null) {
                Direction derived = deriveHorizontalFacing(draft.orientation.insideMarkWorld, draft.orientation.outsideMarkWorld);
                if (derived != null) {
                    draft.orientation.facing = derived;
                    report.add("更新朝向（由内外标记推断）");
                }
            }
        }

        // Fix: bottom/top marks missing -> derive from anchor + height
        if (draft.orientation.hasBottomTop && hasBounds) {
            if (draft.orientation.bottomMarkWorld == null && draft.anchor.worldPos != null) {
                draft.orientation.bottomMarkWorld = new BlockPos(
                        draft.anchor.worldPos.getX(),
                        bounds.min.getY(),
                        draft.anchor.worldPos.getZ()
                );
                report.add("补全底端标记（使用锚点+选区底部）");
            }
            if (draft.orientation.topMarkWorld == null && draft.anchor.worldPos != null) {
                draft.orientation.topMarkWorld = new BlockPos(
                        draft.anchor.worldPos.getX(),
                        bounds.max.getY(),
                        draft.anchor.worldPos.getZ()
                );
                report.add("补全顶端标记（使用锚点+选区顶部）");
            }
        }

        // Fix: host face missing for wall attachments -> derive from outside mark
        AttachmentType attachment = draft.host.attachment != null ? draft.host.attachment : AttachmentType.NONE;
        if ((attachment == AttachmentType.WALL_OPENING || attachment == AttachmentType.WALL_SURFACE)
                && draft.host.referenceBlock == null && draft.host.normal == null) {
            if (draft.orientation.outsideMarkWorld != null && draft.orientation.insideMarkWorld != null) {
                Direction n = deriveHorizontalFacing(draft.orientation.insideMarkWorld, draft.orientation.outsideMarkWorld);
                if (n != null) {
                    draft.host.referenceBlock = draft.orientation.outsideMarkWorld;
                    draft.host.normal = n;
                    report.add("推断宿主面（使用外侧标记+内外方向）");
                }
            }
        }

        if (!report.isEmpty()) {
            draft.dirty = true;
            draft.updatePhase();
        }

        return report;
    }

    private static Bounds resolveBounds(ComponentCaptureDraft draft, BlockPos fallbackMin, BlockPos fallbackMax) {
        Bounds b = new Bounds();
        if (draft.selection.explicit) {
            if (draft.selection.blocks != null && !draft.selection.blocks.isEmpty()) {
                if (draft.selection.aabbMin == null || draft.selection.aabbMax == null) {
                    draft.selection.updateAabbFromBlocks();
                }
                b.min = draft.selection.aabbMin;
                b.max = draft.selection.aabbMax;
            }
        } else {
            b.min = draft.selection.aabbMin != null ? draft.selection.aabbMin : fallbackMin;
            b.max = draft.selection.aabbMax != null ? draft.selection.aabbMax : fallbackMax;
        }
        return b;
    }

    private static Direction deriveHorizontalFacing(BlockPos inside, BlockPos outside) {
        if (inside == null || outside == null) return null;
        int dx = outside.getX() - inside.getX();
        int dz = outside.getZ() - inside.getZ();
        if (dx == 0 && dz == 0) return null;
        if (Math.abs(dx) >= Math.abs(dz)) {
            return dx >= 0 ? Direction.EAST : Direction.WEST;
        }
        return dz >= 0 ? Direction.SOUTH : Direction.NORTH;
    }

    private static final class Bounds {
        BlockPos min;
        BlockPos max;
    }

    public static final class FixReport {
        private final java.util.List<String> fixes = new java.util.ArrayList<>();

        public void add(String fix) {
            if (fix != null && !fix.isBlank()) fixes.add(fix);
        }

        public boolean isEmpty() {
            return fixes.isEmpty();
        }

        public int size() {
            return fixes.size();
        }

        public java.util.List<String> getFixes() {
            return java.util.Collections.unmodifiableList(fixes);
        }
    }
}
