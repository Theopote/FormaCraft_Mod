package com.formacraft.client.tool;

import com.formacraft.client.buildcontext.BuildContextResolver;
import com.formacraft.client.interaction.CursorRaycastHelper;
import com.formacraft.client.network.FormaCraftClientNetworking;
import com.formacraft.common.network.FormaCraftNetworking;
import net.minecraft.text.Text;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 轮廓/Footprint 工具：
 * - 支持：自由绘制、矩形、圆形、多边形
 * - 绘制结束自动封闭（polygon/free_draw/rectangle）
 * - 在世界中渲染：紫色轮廓 + 半透明“条纹填充”（用线段模拟填充）
 *
 * 交互（面板外）：
 * - RECTANGLE：左键 A → 左键 B 完成
 * - CIRCLE：左键中心 → 左键半径点 完成
 * - POLYGON：左键加点 / 右键完成
 * - FREE_DRAW：按住左键拖动采样点；松开左键自动完成
 */
public final class OutlineTool implements FormacraftTool {
    public static final OutlineTool INSTANCE = new OutlineTool();

    private OutlineTool() {}

    public record OutlineShape(
            OutlineMode mode,
            List<BlockPos> points, // polygon/rectangle/free_draw：点在世界坐标（仅用 x/z）
            BlockPos center,       // circle：中心
            int radius,            // circle：半径（XZ）
            int minY,
            int maxY
    ) {}

    private OutlineMode mode = OutlineMode.POLYGON;

    private final List<BlockPos> draft = new ArrayList<>();
    private BlockPos anchorA; // rect/circle 起点
    private boolean selecting = false;
    private boolean lastLeftDown = false; // free draw

    private OutlineShape shape;

    @Override
    public String getId() {
        return "outline";
    }

    @Override
    public Text getDisplayName() {
        return Text.literal("轮廓工具");
    }

    public OutlineMode getMode() {
        return mode;
    }

    public void cycleMode() {
        mode = switch (mode) {
            case FREE_DRAW -> OutlineMode.RECTANGLE;
            case RECTANGLE -> OutlineMode.CIRCLE;
            case CIRCLE -> OutlineMode.POLYGON;
            case POLYGON -> OutlineMode.FREE_DRAW;
        };
        cancelDraft();
    }

    private void cancelDraft() {
        draft.clear();
        anchorA = null;
        selecting = false;
        lastLeftDown = false;
    }

    @Override
    public boolean onMouseClick(double mx, double my, int button) {
        if (button != 0 && button != 1) return false;

        // 右键：结束 polygon / 取消 rect/circle 的选中态
        if (button == 1) {
            if (mode == OutlineMode.POLYGON && !draft.isEmpty()) {
                finishPolygon();
            } else {
                cancelDraft();
            }
            return true;
        }

        // 左键
        BlockHitResult hit = CursorRaycastHelper.getLastBlockHit();
        if (hit == null) return true;
        BlockPos pos = hit.getBlockPos();

        switch (mode) {
            case RECTANGLE -> {
                if (!selecting) {
                    anchorA = pos;
                    selecting = true;
                } else {
                    BlockPos a = anchorA == null ? pos : anchorA;
                    finishRectangle(a, pos);
                }
            }
            case CIRCLE -> {
                if (!selecting) {
                    anchorA = pos; // center
                    selecting = true;
                } else {
                    BlockPos c = anchorA == null ? pos : anchorA;
                    finishCircle(c, pos);
                }
            }
            case POLYGON -> {
                // 左键加点
                draft.add(pos);
            }
            case FREE_DRAW -> {
                // free draw 主要靠 tick 采样；这里用于“开始”
                selecting = true;
                if (draft.isEmpty()) draft.add(pos);
            }
        }
        return true;
    }

    @Override
    public void tick() {
        if (mode != OutlineMode.FREE_DRAW) return;

        boolean leftDown = com.formacraft.client.ui.input.InputRouter.leftDown;
        if (leftDown && !lastLeftDown) {
            // 刚按下
            selecting = true;
        }

        if (leftDown) {
            BlockHitResult hit = CursorRaycastHelper.getLastBlockHit();
            if (hit != null) {
                BlockPos p = hit.getBlockPos();
                if (draft.isEmpty()) {
                    draft.add(p);
                } else {
                    BlockPos last = draft.get(draft.size() - 1);
                    // 只在 XZ 有变化且距离阈值满足时采样，避免过密
                    int dx = p.getX() - last.getX();
                    int dz = p.getZ() - last.getZ();
                    if (dx * dx + dz * dz >= 1) {
                        draft.add(p);
                    }
                }
            }
        }

        if (!leftDown && lastLeftDown) {
            // 刚松开：完成 free draw
            if (draft.size() >= 3) {
                finishPolygon();
            } else {
                cancelDraft();
            }
        }

        lastLeftDown = leftDown;
    }

    private void finishRectangle(BlockPos a, BlockPos b) {
        cancelDraft();
        selecting = false;

        int minX = Math.min(a.getX(), b.getX());
        int maxX = Math.max(a.getX(), b.getX());
        int minZ = Math.min(a.getZ(), b.getZ());
        int maxZ = Math.max(a.getZ(), b.getZ());

        List<BlockPos> pts = new ArrayList<>(4);
        pts.add(new BlockPos(minX, a.getY(), minZ));
        pts.add(new BlockPos(maxX, a.getY(), minZ));
        pts.add(new BlockPos(maxX, a.getY(), maxZ));
        pts.add(new BlockPos(minX, a.getY(), maxZ));
        setPolygonShape(OutlineMode.RECTANGLE, pts);
    }

    private void finishCircle(BlockPos center, BlockPos edge) {
        cancelDraft();
        selecting = false;

        int dx = edge.getX() - center.getX();
        int dz = edge.getZ() - center.getZ();
        int r = (int) Math.max(1, Math.round(Math.sqrt(dx * dx + dz * dz)));

        int[] yr = defaultYRange(center.getY());
        this.shape = new OutlineShape(OutlineMode.CIRCLE, List.of(), center, r, yr[0], yr[1]);
        syncToServer();
    }

    private void finishPolygon() {
        if (draft.size() < 3) {
            cancelDraft();
            return;
        }
        List<BlockPos> pts = new ArrayList<>(draft);
        cancelDraft();
        selecting = false;
        setPolygonShape(mode == OutlineMode.FREE_DRAW ? OutlineMode.FREE_DRAW : OutlineMode.POLYGON, pts);
    }

    private void setPolygonShape(OutlineMode m, List<BlockPos> pts) {
        int baseY = pts.get(0).getY();
        int[] yr = defaultYRange(baseY);
        this.shape = new OutlineShape(m, List.copyOf(pts), null, 0, yr[0], yr[1]);
        syncToServer();
    }

    private int[] defaultYRange(int baseY) {
        // 如果用户已经选区，则直接复用选区的 Y 范围（更符合预期）
        if (SelectionTool.INSTANCE.hasSelection()) {
            BlockPos min = SelectionTool.INSTANCE.getMin();
            BlockPos max = SelectionTool.INSTANCE.getMax();
            if (min != null && max != null) {
                return new int[]{min.getY(), max.getY()};
            }
        }
        return new int[]{baseY, baseY + 20};
    }

    public boolean hasShape() {
        return shape != null;
    }

    public OutlineShape getShape() {
        return shape;
    }

    public void clearShape() {
        shape = null;
        cancelDraft();
        syncToServer();
    }

    private void syncToServer() {
        FormaCraftClientNetworking.sendOutlineSync(BuildContextResolver.currentOutlineShape());
    }

    public boolean isDrafting() {
        return selecting;
    }

    public List<BlockPos> getDraftPoints() {
        return Collections.unmodifiableList(draft);
    }

    @Override
    public void renderWorld(ToolWorldRenderContext ctx) {
        // draft 预览：浅紫色
        if (!draft.isEmpty()) {
            renderPolygon(ctx, draft, 180, 80, 220, 160, true);
        }

        // rect/circle 在 selecting 时的预览
        if (selecting && anchorA != null && (mode == OutlineMode.RECTANGLE || mode == OutlineMode.CIRCLE)) {
            BlockHitResult hit = CursorRaycastHelper.getLastBlockHit();
            if (hit != null) {
                BlockPos p = hit.getBlockPos();
                if (mode == OutlineMode.RECTANGLE) {
                    List<BlockPos> pts = new ArrayList<>(4);
                    int minX = Math.min(anchorA.getX(), p.getX());
                    int maxX = Math.max(anchorA.getX(), p.getX());
                    int minZ = Math.min(anchorA.getZ(), p.getZ());
                    int maxZ = Math.max(anchorA.getZ(), p.getZ());
                    pts.add(new BlockPos(minX, anchorA.getY(), minZ));
                    pts.add(new BlockPos(maxX, anchorA.getY(), minZ));
                    pts.add(new BlockPos(maxX, anchorA.getY(), maxZ));
                    pts.add(new BlockPos(minX, anchorA.getY(), maxZ));
                    renderPolygon(ctx, pts, 200, 100, 255, 180, true);
                } else {
                    int dx = p.getX() - anchorA.getX();
                    int dz = p.getZ() - anchorA.getZ();
                    int r = (int) Math.max(1, Math.round(Math.sqrt(dx * dx + dz * dz)));
                    renderCircle(ctx, anchorA, r, 200, 100, 255, 180, true);
                }
            }
        }

        // 已完成的 shape：亮紫 + 半透明条纹填充
        if (shape == null) return;
        if (shape.mode == OutlineMode.CIRCLE) {
            renderCircle(ctx, shape.center, shape.radius, 170, 80, 255, 220, false);
            renderCircleFillStripes(ctx, shape.center, shape.radius, shape.minY, 160, 70, 220, 70);
            return;
        }
        if (shape.points != null && shape.points.size() >= 3) {
            renderPolygon(ctx, shape.points, 170, 80, 255, 220, false);
            renderPolygonFillStripes(ctx, shape.points, shape.minY, 160, 70, 220, 70);
        }
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
        if (!open) {
            BlockPos p1 = pts.get(n - 1);
            BlockPos p2 = pts.get(0);
            ToolRenderUtil.line(ctx,
                    p1.getX() + 0.5, p1.getY() + 0.05, p1.getZ() + 0.5,
                    p2.getX() + 0.5, p2.getY() + 0.05, p2.getZ() + 0.5,
                    r, g, b, a);
        }
    }

    private void renderCircle(ToolWorldRenderContext ctx, BlockPos center, int radius,
                              int r, int g, int b, int a, boolean preview) {
        if (center == null || radius <= 0) return;
        int segments = 48;
        double cy = center.getY() + 0.05;
        double cx = center.getX() + 0.5;
        double cz = center.getZ() + 0.5;
        double lastX = cx + radius;
        double lastZ = cz;
        for (int i = 1; i <= segments; i++) {
            double t = (Math.PI * 2.0) * i / segments;
            double x = cx + Math.cos(t) * radius;
            double z = cz + Math.sin(t) * radius;
            ToolRenderUtil.line(ctx, lastX, cy, lastZ, x, cy, z, r, g, b, a);
            lastX = x;
            lastZ = z;
        }
        if (preview) {
            // 半径指示线
            ToolRenderUtil.line(ctx, cx, cy, cz, cx + radius, cy, cz, r, g, b, 120);
        }
    }

    private void renderPolygonFillStripes(ToolWorldRenderContext ctx, List<BlockPos> poly, int y,
                                          int r, int g, int b, int a) {
        if (poly == null || poly.size() < 3) return;
        // 只用 XZ，扫描 Z 画水平线段
        int minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE;
        for (BlockPos p : poly) {
            minZ = Math.min(minZ, p.getZ());
            maxZ = Math.max(maxZ, p.getZ());
            minX = Math.min(minX, p.getX());
            maxX = Math.max(maxX, p.getX());
        }
        if (minZ > maxZ || minX > maxX) return;

        double yy = y + 0.02;
        int step = 1;
        for (int zz = minZ; zz <= maxZ; zz += step) {
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

            // 典型扫描线交点：忽略水平边，使用半开区间避免重复计数
            if (z1 == z2) continue;
            boolean cond = (z >= Math.min(z1, z2)) && (z < Math.max(z1, z2));
            if (!cond) continue;
            double t = (z - z1) / (z2 - z1);
            double x = x1 + t * (x2 - x1);
            xs.add(x);
        }
        return xs;
    }

    private void renderCircleFillStripes(ToolWorldRenderContext ctx, BlockPos center, int radius, int y,
                                         int r, int g, int b, int a) {
        if (center == null || radius <= 0) return;
        double cx = center.getX() + 0.5;
        double cz = center.getZ() + 0.5;
        double yy = y + 0.02;
        for (int dz = -radius; dz <= radius; dz++) {
            double zz = cz + dz;
            double dx = Math.sqrt(Math.max(0.0, radius * radius - dz * dz));
            ToolRenderUtil.line(ctx, cx - dx, yy, zz, cx + dx, yy, zz, r, g, b, a);
        }
    }
}


