package com.formacraft.client.tools;

import com.formacraft.client.tool.OutlineTool;
import com.formacraft.client.tool.ProtectedZoneTool;
import com.formacraft.client.tool.SelectionTool;
import com.formacraft.client.tool.SemanticLabelTool;
import com.formacraft.common.geom.Geom2D;
import com.formacraft.common.model.constraint.ProtectedZone;
import net.minecraft.util.math.BlockPos;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/**
 * ToolSnapshot（工具快照）
 * 
 * K2：把工具状态"拍一张快照"，用于布局/Prompt/Filter。
 * 
 * 注意：这里提供的是"通用字段"，你只要把你的工具数据塞进来就行。
 * 
 * 核心设计：
 * - 不要让 Planner 直接依赖 Tool 类，而是依赖一个稳定结构 ToolSnapshot
 * - 使用适配器模式从工具类提取数据
 */
public class ToolSnapshot {

    /** 选区（AABB） */
    public boolean hasSelection;
    public BlockPos selMin; // inclusive
    public BlockPos selMax; // inclusive

    /** 轮廓（2D Polygon，世界坐标 X/Z） */
    public boolean hasOutline;
    public List<Geom2D.Vec2> outlinePolygon = new ArrayList<>();

    /** 禁区/保护区：一组 polygon（也可以塞矩形的四点） */
    public List<List<Geom2D.Vec2>> forbiddenPolygons = new ArrayList<>();

    /** 语义标注区域（polygon + label） */
    public List<SemanticRegion> semanticRegions = new ArrayList<>();

    public record SemanticRegion(String label, List<Geom2D.Vec2> polygon) {}

    // -----------------------------
    // adapters: from your tools
    // -----------------------------

    /**
     * 从工具实例创建快照（强类型版本）
     */
    public static ToolSnapshot fromTools(
            SelectionTool selectionTool,
            OutlineTool outlineTool,
            ProtectedZoneTool forbiddenTool,
            SemanticLabelTool semanticLabelTool
    ) {
        ToolSnapshot s = new ToolSnapshot();

        // -------- SelectionTool adapter (AABB) --------
        if (selectionTool != null && selectionTool.hasSelection()) {
            s.hasSelection = true;
            s.selMin = selectionTool.getMin();
            s.selMax = selectionTool.getMax();
        }

        // -------- OutlineTool adapter (polygon) --------
        if (outlineTool != null && outlineTool.hasShape()) {
            OutlineTool.OutlineShape shape = outlineTool.getShape();
            if (shape != null) {
                List<Geom2D.Vec2> poly = getOutlinePolygon(shape);
                if (poly != null && poly.size() >= 3) {
                    s.hasOutline = true;
                    s.outlinePolygon = poly;
                }
            }
        }

        // -------- ProtectedZoneTool adapter (polygons) --------
        if (forbiddenTool != null && forbiddenTool.hasZones()) {
            List<ProtectedZone> zones = forbiddenTool.getZones();
            for (ProtectedZone zone : zones) {
                if (zone == null) continue;
                List<Geom2D.Vec2> poly = zoneToPolygon(zone);
                if (poly != null && poly.size() >= 3) {
                    s.forbiddenPolygons.add(poly);
                }
            }
        }

        // -------- SemanticLabelTool adapter --------
        if (semanticLabelTool != null) {
            List<SemanticRegion> regions = getSemanticRegions(semanticLabelTool);
            s.semanticRegions.addAll(regions);
        }

        return s;
    }

    /**
     * 从 OutlineShape 提取多边形
     */
    private static List<Geom2D.Vec2> getOutlinePolygon(OutlineTool.OutlineShape shape) {
        if (shape == null) return null;

        // 圆形：转换为多边形（近似）
        com.formacraft.client.tool.OutlineMode mode = shape.mode();
        if (mode == com.formacraft.client.tool.OutlineMode.CIRCLE && shape.center() != null) {
            return getVec2s(shape);
        }

        // 多边形：直接使用 points
        List<BlockPos> points = shape.points();
        if (points == null || points.size() < 3) return null;

        List<Geom2D.Vec2> poly = new ArrayList<>();
        for (BlockPos p : points) {
            poly.add(new Geom2D.Vec2(p.getX() + 0.5, p.getZ() + 0.5));
        }
        return poly;
    }

    private static @NotNull List<Geom2D.Vec2> getVec2s(OutlineTool.OutlineShape shape) {
        List<Geom2D.Vec2> poly = new ArrayList<>();
        int segments = 32;
        double cx = shape.center().getX() + 0.5;
        double cz = shape.center().getZ() + 0.5;
        int radius = shape.radius();
        for (int i = 0; i < segments; i++) {
            double angle = (Math.PI * 2.0) * i / segments;
            double x = cx + Math.cos(angle) * radius;
            double z = cz + Math.sin(angle) * radius;
            poly.add(new Geom2D.Vec2(x, z));
        }
        return poly;
    }

    /**
     * 从 ProtectedZone 转换为多边形
     */
    private static List<Geom2D.Vec2> zoneToPolygon(ProtectedZone zone) {
        if (zone == null || zone.min() == null || zone.max() == null) {
            return null;
        }

        BlockPos min = zone.min();
        BlockPos max = zone.max();

        // 矩形：转换为四点多边形
        List<Geom2D.Vec2> poly = new ArrayList<>();
        poly.add(new Geom2D.Vec2(min.getX() + 0.5, min.getZ() + 0.5));
        poly.add(new Geom2D.Vec2(max.getX() + 0.5, min.getZ() + 0.5));
        poly.add(new Geom2D.Vec2(max.getX() + 0.5, max.getZ() + 0.5));
        poly.add(new Geom2D.Vec2(min.getX() + 0.5, max.getZ() + 0.5));
        return poly;
    }

    /**
     * 从 SemanticLabelTool 提取语义区域
     */
    private static List<SemanticRegion> getSemanticRegions(SemanticLabelTool tool) {
        List<SemanticRegion> regions = new ArrayList<>();
        if (tool == null || !tool.hasLabels()) {
            return regions;
        }

        List<SemanticLabelTool.AreaLabel> labels = tool.getLabels();
        for (SemanticLabelTool.AreaLabel label : labels) {
            if (label == null || label.outline() == null || label.outline().size() < 3) {
                continue;
            }

            // 将 BlockPos 列表转换为 Vec2 列表
            List<Geom2D.Vec2> poly = new ArrayList<>();
            for (BlockPos p : label.outline()) {
                poly.add(new Geom2D.Vec2(p.getX() + 0.5, p.getZ() + 0.5));
            }

            regions.add(new SemanticRegion(label.name(), poly));
        }

        return regions;
    }
}

