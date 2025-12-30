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
    public record AreaLabel(String name, int range, List<BlockPos> outline, int minY, int maxY) {}

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

    public void clearLabels() {
        labels.clear();
        drafting = false;
        draft.clear();
    }

    @Override
    public boolean onMouseClick(double mx, double my, int button) {
        if (button != 0 && button != 1) return false;
        BlockHitResult hit = CursorRaycastHelper.getLastBlockHit();
        if (hit == null) return true;
        BlockPos pos = hit.getBlockPos();

        if (button == 1) {
            // 右键：结束（draft >= 3）或取消
            if (draft.size() >= 3) {
                finishLabel(pos.getY());
            } else {
                cancelDraft();
            }
            return true;
        }

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
            renderPolygonFill(ctx, l.outline(), l.minY(), 255, 230, 80, 70);
            renderLabelText(ctx, l);
        }

        // draft：更亮一点（开放折线）
        if (drafting && !draft.isEmpty()) {
            renderPolygon(ctx, draft, 255, 255, 120, 200, true);
        }
    }

    private void cancelDraft() {
        drafting = false;
        draft.clear();
    }

    private void finishLabel(int baseY) {
        if (draft.size() < 3) {
            cancelDraft();
            return;
        }
        int[] yr = defaultYRange(baseY);
        labels.add(new AreaLabel(pendingName, pendingRange, List.copyOf(draft), yr[0], yr[1]));
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
            BlockPos p2 = pts.get(0);
            ToolRenderUtil.line(ctx,
                    p1.getX() + 0.5, p1.getY() + 0.05, p1.getZ() + 0.5,
                    p2.getX() + 0.5, p2.getY() + 0.05, p2.getZ() + 0.5,
                    r, g, b, a);
        }
    }

    private void renderPolygonFill(ToolWorldRenderContext ctx, List<BlockPos> poly, int y,
                                   int r, int g, int b, int a) {
        if (poly == null || poly.size() < 3) return;
        int minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
        for (BlockPos p : poly) {
            minZ = Math.min(minZ, p.getZ());
            maxZ = Math.max(maxZ, p.getZ());
        }
        if (minZ > maxZ) return;

        double yy = y + 0.02;
        for (int zz = minZ; zz <= maxZ; zz++) {
            List<Double> xs = intersectScanline(poly, zz + 0.5);
            if (xs.size() < 2) continue;
            xs.sort(Double::compareTo);
            for (int i = 0; i + 1 < xs.size(); i += 2) {
                double x1 = xs.get(i);
                double x2 = xs.get(i + 1);
                ToolRenderUtil.line(ctx, x1, yy, zz + 0.5, x2, yy, zz + 0.5, r, g, b, a);
            }
        }
    }

    private List<Double> intersectScanline(List<BlockPos> poly, double z) {
        List<Double> xs = new ArrayList<>();
        int n = poly.size();
        for (int i = 0; i < n; i++) {
            BlockPos a = poly.get(i);
            BlockPos b = poly.get((i + 1) % n);
            double z1 = a.getZ() + 0.5;
            double z2 = b.getZ() + 0.5;
            double x1 = a.getX() + 0.5;
            double x2 = b.getX() + 0.5;
            if (z1 == z2) continue;
            boolean cond = (z >= Math.min(z1, z2)) && (z < Math.max(z1, z2));
            if (!cond) continue;
            double t = (z - z1) / (z2 - z1);
            xs.add(x1 + t * (x2 - x1));
        }
        return xs;
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


