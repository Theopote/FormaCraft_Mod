package com.formacraft.client.tool;

import com.formacraft.client.interaction.CursorRaycastHelper;
import net.minecraft.text.Text;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 区域语义标注工具：
 * - 多边形区域：左键加点 / 右键结束
 * - 使用 ToolPanel 提供的“当前标签名”写入
 * - 渲染：黄色轮廓 + 半透明填充 + 世界悬浮文字
 */
public final class SemanticLabelTool implements FormacraftTool {
    public static final SemanticLabelTool INSTANCE = new SemanticLabelTool();

    private SemanticLabelTool() {}

    /** range：标签“作用范围”（方块），用于告诉 AI 该标签大致影响的区域范围（即便轮廓较小/较大也可单独约束）。 */
    public record AreaLabel(
            String name,
            int range,
            List<BlockPos> outline,
            int minY,
            int maxY,
            List<ToolGeometry.FillStripe> fill
    ) {
        public static AreaLabel of(String name, int range, List<BlockPos> outline, int minY, int maxY) {
            List<BlockPos> pts = List.copyOf(outline);
            return new AreaLabel(name, range, pts, minY, maxY, ToolGeometry.polygonFillStripes(pts));
        }
    }

    private final List<BlockPos> draft = new ArrayList<>();
    private boolean drafting = false;

    private String pendingName = "入口";
    /** 标签作用范围（方块），用于“标签影响周边区域”的大致半径 */
    private int pendingRange = 16;

    private final List<AreaLabel> labels = new ArrayList<>();

    @Override
    public String getId() {
        return "semantic_label";
    }

    @Override
    public Text getDisplayName() {
        return Text.literal("区域语义标注");
    }

    public void setPendingName(String name) {
        String n = name == null ? "" : name.trim();
        if (n.isEmpty()) n = "未命名";
        this.pendingName = n;
    }

    public String getPendingName() {
        return pendingName;
    }

    public void setPendingRange(int range) {
        int r = range;
        // UI 侧使用滑动条（1~40），这里也做同样钳制，避免服务端/后端收到离谱值
        if (r < 1) r = 1;
        if (r > 40) r = 40;
        this.pendingRange = r;
    }

    public int getPendingRange() {
        return pendingRange;
    }

    public List<AreaLabel> getLabels() {
        return Collections.unmodifiableList(labels);
    }

    public boolean hasLabels() {
        return !labels.isEmpty();
    }

    @Override
    public boolean hasPersistentOverlay() { return hasLabels(); }

    public void clearLabels() {
        labels.clear();
        drafting = false;
        draft.clear();
    }

    @Override
    public void resetWorldState() {
        clearLabels();
    }

    @Override
    public boolean onMouseClick(double mx, double my, int button) {
        if (button != 0 && button != 1) return false;
        if (button == 1) {
            // 右键：结束（draft >= 3）或取消
            if (draft.size() >= 3) {
                finishDraft();
            } else {
                cancelDraft();
            }
            return true;
        }

        BlockHitResult hit = CursorRaycastHelper.getLastBlockHit();
        if (hit == null) return true;
        BlockPos pos = hit.getBlockPos();
        // 左键：加点
        drafting = true;
        draft.add(pos);
        return true;
    }

    @Override
    public void tick() {
        // v1：不做“跟随预览点”，避免线段抖动；只显示已点击的点
    }

    @Override
    public void renderWorld(ToolWorldRenderContext ctx) {
        // 已提交标签区域：黄色轮廓 + 半透明填充 + 悬浮文字
        for (AreaLabel l : labels) {
            if (l == null || l.outline() == null || l.outline().size() < 3) continue;
            renderPolygon(ctx, l.outline(), 255, 230, 80, 220, false);
            renderCachedFill(ctx, l.fill(), l.minY(), 255, 230, 80, 70);
            renderLabelText(ctx, l);
        }

        // draft：更亮一点（开放折线）
        if (drafting && !draft.isEmpty()) {
            renderPolygon(ctx, draft, 255, 255, 120, 200, true);
        }
    }

    @Override
    public void cancelDraft() {
        drafting = false;
        draft.clear();
    }

    @Override
    public boolean finishDraft() {
        if (draft.size() < 3) return false;
        var points = ToolGeometry.normalizePolygon(draft);
        if (!ToolGeometry.validPolygon(points)) {
            com.formacraft.client.ui.toast.HudToast.show("标签区域无效：请避免重叠、交叉或零面积", true);
            return true;
        }
        draft.clear();
        draft.addAll(points);
        finishLabel(draft.getFirst().getY());
        return true;
    }

    @Override
    public boolean removeLastDraftPoint() {
        if (draft.isEmpty()) return false;
        draft.removeLast();
        drafting = !draft.isEmpty();
        return true;
    }

    @Override
    public void onDeactivate() { cancelDraft(); }

    private void finishLabel(int baseY) {
        if (draft.size() < 3) {
            cancelDraft();
            return;
        }
        int[] yr = defaultYRange(baseY);
        labels.add(AreaLabel.of(pendingName, pendingRange, draft, yr[0], yr[1]));
        cancelDraft();
    }

    private int[] defaultYRange(int baseY) {
        // 复用选区 Y 范围（如果存在）
        if (SelectionTool.INSTANCE.hasSelection()) {
            BlockPos min = SelectionTool.INSTANCE.getMin();
            BlockPos max = SelectionTool.INSTANCE.getMax();
            if (min != null && max != null) {
                return new int[]{min.getY(), max.getY()};
            }
        }
        return new int[]{baseY, baseY + 20};
    }

    private void renderPolygon(ToolWorldRenderContext ctx, List<BlockPos> pts,
                               int r, int g, int b, int a, boolean open) {
        if (pts == null || pts.size() < 2) return;
        int n = pts.size();
        for (int i = 0; i < n - 1; i++) {
            BlockPos p1 = pts.get(i);
            BlockPos p2 = pts.get(i + 1);
            ToolRenderUtil.line(ctx,
                    p1.getX() + 0.5, p1.getY() + 0.05, p1.getZ() + 0.5,
                    p2.getX() + 0.5, p2.getY() + 0.05, p2.getZ() + 0.5,
                    r, g, b, a);
        }
        if (!open && n >= 3) {
            BlockPos p1 = pts.get(n - 1);
            BlockPos p2 = pts.getFirst();
            ToolRenderUtil.line(ctx,
                    p1.getX() + 0.5, p1.getY() + 0.05, p1.getZ() + 0.5,
                    p2.getX() + 0.5, p2.getY() + 0.05, p2.getZ() + 0.5,
                    r, g, b, a);
        }
    }

    private void renderCachedFill(
            ToolWorldRenderContext ctx,
            List<ToolGeometry.FillStripe> fill,
            int y,
            int r, int g, int b, int a
    ) {
        if (fill == null || fill.isEmpty()) return;
        double yy = y + 0.02;
        for (ToolGeometry.FillStripe s : fill) {
            ToolRenderUtil.line(ctx, s.x1(), yy, s.z(), s.x2(), yy, s.z(), r, g, b, a);
        }
    }

    private void renderLabelText(ToolWorldRenderContext ctx, AreaLabel l) {
        if (l == null || l.outline() == null || l.outline().isEmpty()) return;
        // centroid (XZ)
        double sx = 0, sz = 0;
        for (BlockPos p : l.outline()) {
            sx += p.getX() + 0.5;
            sz += p.getZ() + 0.5;
        }
        double cx = sx / l.outline().size();
        double cz = sz / l.outline().size();
        double y = l.minY() + 1.8;
        String suffix = l.range() > 0 ? ("  r=" + l.range()) : "";
        ToolTextRenderUtil.drawBillboardText(ctx, new net.minecraft.util.math.Vec3d(cx, y, cz),
                Text.literal(l.name() + suffix), 0xFFFFE650, 0.02f);
    }
}


