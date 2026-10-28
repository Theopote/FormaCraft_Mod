package com.formacraft.client.tool;

import com.formacraft.client.interaction.CursorRaycastHelper;
import com.formacraft.common.skeleton.PathSkeleton;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 路径工具（空间曲线 / 规划参考线）：
 * - 左键：添加路径点（命中方块中心）
 * - 右键：完成当前路径（至少两个点），并保留为参考线
 *
 * 曲线实现：将用户点击的“路径点”作为样条节点，使用 Catmull-Rom -> Cubic Bezier 转换得到分段三次贝塞尔曲线，
 * 然后采样成折线进行渲染（视觉上为平滑的贝塞尔空间曲线）。
 */
public final class PathTool implements FormacraftTool {
    public static final PathTool INSTANCE = new PathTool();

    PathTool() {}

    public record Path(List<Vec3d> waypoints, List<Vec3d> polyline) {}

    private final List<Vec3d> draft = new ArrayList<>();
    private Vec3d hover = null;
    private boolean drafting = false;

    private final List<Path> paths = new ArrayList<>();
    private int selectedPath = -1;
    private int corridorRadius = 3;
    private boolean smooth = false;
    private PathSkeleton.PathIntent intent = PathSkeleton.PathIntent.GENERIC;

    public boolean isSmooth() { return smooth; }
    public void cycleDrawingMode() { smooth = !smooth; }
    public void changeCorridorRadius(int delta) { corridorRadius = Math.clamp(corridorRadius + delta, 1, 32); }
    public PathSkeleton.PathIntent getIntent() { return intent; }
    public void cycleIntent() {
        var values = PathSkeleton.PathIntent.values();
        intent = values[(intent.ordinal() + 1) % values.length];
    }
    public int getSelectedPathIndex() { return selectedPath; }
    public void selectNextPath() {
        if (!paths.isEmpty()) selectedPath = (selectedPath + 1) % paths.size();
    }
    public List<Path> getGenerationPaths() {
        return selectedPath >= 0 && selectedPath < paths.size() ? List.of(paths.get(selectedPath)) : List.of();
    }
    public void removeSelectedPath() {
        if (selectedPath < 0 || selectedPath >= paths.size()) return;
        paths.remove(selectedPath);
        selectedPath = paths.isEmpty() ? -1 : Math.min(selectedPath, paths.size() - 1);
    }


    @Override
    public String getId() {
        return "path";
    }

    @Override
    public Text getDisplayName() {
        return Text.literal("路径工具");
    }

    public boolean isDrafting() {
        return drafting && !draft.isEmpty();
    }

    public int getDraftPointCount() {
        return draft.size();
    }

    public int getPathCount() {
        return paths.size();
    }

    public List<Path> getPaths() {
        return Collections.unmodifiableList(paths);
    }

    /** Export only the explicitly selected path; disconnected paths must never be joined. */
    public List<BlockPos> getNodes() {
        List<BlockPos> nodes = new ArrayList<>();
        for (Path path : getGenerationPaths()) {
            for (Vec3d v : path.polyline()) {
                BlockPos pos = BlockPos.ofFloored(v);
                if (nodes.isEmpty() || !nodes.getLast().equals(pos)) nodes.add(pos);
            }
        }
        return nodes;
    }

    public int getCorridorRadius() { return corridorRadius; }

    /**
     * 导出为 PathSkeleton（用于生成器）
     * 
     * 这是不依赖 AI 的结构事实
     * AI 只能"补充风格"，不能破坏骨架
     */
    public PathSkeleton toSkeleton() {
        List<BlockPos> nodes = getNodes();
        if (nodes.size() < 2) {
            // 如果没有有效路径，返回空骨架
            return new PathSkeleton(List.of(), getCorridorRadius(), true, PathSkeleton.PathIntent.GENERIC);
        }
        
        return new PathSkeleton(
            nodes,
            getCorridorRadius(),
            !WorkPlaneTool.INSTANCE.state().fixed(), // 锁定基准平面时保留绘制标高
            resolveIntent()
        );
    }

    /**
     * 解析路径意图（v1：简单规则，后续可被 AI 覆盖）
     */
    private PathSkeleton.PathIntent resolveIntent() { return intent; }

    public void setIntent(PathSkeleton.PathIntent intent) {
        this.intent = intent != null ? intent : PathSkeleton.PathIntent.GENERIC;
    }

    public void clearAll() {
        paths.clear();
        selectedPath = -1;
        cancelDraft();
    }

    public void cancelDraft() {
        draft.clear();
        hover = null;
        drafting = false;
    }

    @Override
    public boolean finishDraft() {
        if (draft.size() < 2) return false;
        finalizePath();
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
    public void onDeactivate() {
        // 切走工具时不保留“未完成草稿”，避免用户误触导致半条线卡住
        cancelDraft();
    }

    @Override
    public boolean onMouseClick(double mx, double my, int button) {
        if (button != 0 && button != 1) return false;

        // 右键：完成 / 取消
        if (button == 1) {
            if (draft.size() >= 2) {
                finalizePath();
            } else {
                cancelDraft();
            }
            return true;
        }

        // 左键：添加点
        BlockPos target = CursorRaycastHelper.getDrawingBlockPos();
        if (target == null) return true;

        BlockPos pos = target;
        Vec3d p = new Vec3d(pos.getX() + 0.5, pos.getY() + 0.05, pos.getZ() + 0.5);

        addDraftPoint(p);
        return true;
    }

    @Override
    public void tick() {
        if (!drafting || draft.isEmpty()) {
            hover = null;
            return;
        }
        BlockPos target = CursorRaycastHelper.getDrawingBlockPos();
        if (target == null) {
            hover = null;
            return;
        }
        BlockPos pos = target;
        hover = new Vec3d(pos.getX() + 0.5, pos.getY() + 0.05, pos.getZ() + 0.5);
    }

    void addDraftPoint(Vec3d p) {
        if (draft.isEmpty() || !draft.getLast().equals(p)) draft.add(p);
        drafting = !draft.isEmpty();
    }

    private List<Vec3d> sampleDraft(List<Vec3d> points) {
        return smooth ? sampleBezierSpline(points) : List.copyOf(points);
    }

    private void finalizePath() {
        List<Vec3d> waypoints = List.copyOf(draft);
        List<Vec3d> poly = sampleDraft(waypoints);
        paths.add(new Path(waypoints, poly));
        selectedPath = paths.size() - 1;
        cancelDraft();
    }

    /**
     * 全局渲染入口：即便当前 activeTool 不是 PathTool，也会把已完成的路径绘制出来作为参考线。
     * 草稿仅在该工具激活时绘制（避免“路上突然多一条线”干扰）。
     */
    public static void renderGlobal(ToolWorldRenderContext ctx) {
        if (ctx == null) return;

        // 已完成路径：橙色
        for (Path p : INSTANCE.paths) {
            if (p == null || p.polyline == null || p.polyline.size() < 2) continue;
            boolean selected = INSTANCE.getGenerationPaths().contains(p);
            renderPolyline(ctx, p.polyline, 255, selected ? 210 : 130, 40, selected ? 255 : 110);
        }

        // 草稿：仅当当前工具就是 PathTool 才显示（亮青色）
        if (!ToolManager.isActive(INSTANCE.getId())) return;

        List<Vec3d> pts = new ArrayList<>(INSTANCE.draft);
        if (INSTANCE.hover != null) pts.add(INSTANCE.hover);
        if (pts.size() >= 2) {
            List<Vec3d> poly = INSTANCE.sampleDraft(pts);
            renderPolyline(ctx, poly, 80, 220, 255, 180);
        }

        // 草稿节点：更亮（小短线模拟点）
        for (Vec3d v : INSTANCE.draft) {
            if (v == null) continue;
            double s = 0.18;
            ToolRenderUtil.line(ctx, v.x - s, v.y, v.z, v.x + s, v.y, v.z, 120, 245, 255, 220);
            ToolRenderUtil.line(ctx, v.x, v.y, v.z - s, v.x, v.y, v.z + s, 120, 245, 255, 220);
        }
    }

    private static void renderPolyline(ToolWorldRenderContext ctx, List<Vec3d> poly, int r, int g, int b, int a) {
        if (poly == null || poly.size() < 2) return;
        Vec3d last = poly.getFirst();
        for (int i = 1; i < poly.size(); i++) {
            Vec3d cur = poly.get(i);
            if (last != null && cur != null) {
                ToolRenderUtil.line(ctx, last.x, last.y, last.z, cur.x, cur.y, cur.z, r, g, b, a);
            }
            last = cur;
        }
    }

    /**
     * 将节点点列转换为分段三次 Bezier 样条并采样成折线。
     * 采用标准 Catmull-Rom -> Bezier 转换（c1/c2 = +/- (p(i+1)-p(i-1))/6）。
     */
    private static List<Vec3d> sampleBezierSpline(List<Vec3d> waypoints) {
        if (waypoints == null || waypoints.size() < 2) return Collections.emptyList();

        int n = waypoints.size();
        List<Vec3d> out = new ArrayList<>();

        for (int i = 0; i < n - 1; i++) {
            Vec3d p0 = (i == 0) ? waypoints.getFirst() : waypoints.get(i - 1);
            Vec3d p1 = waypoints.get(i);
            Vec3d p2 = waypoints.get(i + 1);
            Vec3d p3 = (i + 2 < n) ? waypoints.get(i + 2) : waypoints.get(n - 1);

            if (p0 == null || p1 == null || p2 == null || p3 == null) continue;

            Vec3d c1 = p1.add(p2.subtract(p0).multiply(1.0 / 6.0));
            Vec3d c2 = p2.subtract(p3.subtract(p1).multiply(1.0 / 6.0));

            double segLen = p1.distanceTo(p2);
            int steps = (int) Math.max(10, Math.ceil(segLen * 10.0)); // 越长越密

            int start = (i == 0) ? 0 : 1; // 避免段与段之间重复点
            for (int s = start; s <= steps; s++) {
                double t = s / (double) steps;
                out.add(bezier(p1, c1, c2, p2, t));
            }
        }

        return out;
    }

    private static Vec3d bezier(Vec3d p0, Vec3d c1, Vec3d c2, Vec3d p3, double t) {
        double u = 1.0 - t;
        double tt = t * t;
        double uu = u * u;
        double uuu = uu * u;
        double ttt = tt * t;

        // (1-t)^3 p0 + 3(1-t)^2 t c1 + 3(1-t) t^2 c2 + t^3 p3
        double x = uuu * p0.x
                + 3.0 * uu * t * c1.x
                + 3.0 * u * tt * c2.x
                + ttt * p3.x;
        double y = uuu * p0.y
                + 3.0 * uu * t * c1.y
                + 3.0 * u * tt * c2.y
                + ttt * p3.y;
        double z = uuu * p0.z
                + 3.0 * uu * t * c1.z
                + 3.0 * u * tt * c2.z
                + ttt * p3.z;
        return new Vec3d(x, y, z);
    }
}


