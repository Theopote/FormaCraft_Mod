package com.formacraft.common.skeleton;

import com.formacraft.common.skeleton.compound.CompoundPlan;
import com.formacraft.common.skeleton.compound.GeneratorBackedPlan;
import com.formacraft.common.skeleton.grid.GridPlan;
import com.formacraft.common.skeleton.linear.LinearPathPlan;
import com.formacraft.common.skeleton.path.PolylinePathPlan;
import com.formacraft.common.skeleton.radial.RadialPlan;
import com.formacraft.common.skeleton.radial.RadialPrimitive;
import com.formacraft.common.skeleton.rect.RectEnclosurePlan;
import com.formacraft.common.skeleton.span.SpanSuspensionPlan;
import com.formacraft.common.skeleton.stack.VerticalStackPlan;
import com.formacraft.common.skeleton.vertical.VerticalTaperPlan;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * {@link SkeletonPlan} → {@link ExecutableSkeletonPlan} 桥接器。
 * <p>
 * Phase 1：仅提供转换能力，尚未接入 Blueprint 路径（仍走 Interpreter / StructureGenerator）。
 * {@link GeneratorBackedPlan} 必须保持空结果，由现有整栋生成器处理。
 */
public final class SkeletonPlanConverter {

    private SkeletonPlanConverter() {}

    /**
     * @return 可执行的骨架计划；无法转换时（如 GeneratorBackedPlan）返回 empty
     */
    public static Optional<ExecutableSkeletonPlan> toExecutable(SkeletonPlan plan) {
        if (plan == null) {
            return Optional.empty();
        }
        if (plan instanceof GeneratorBackedPlan) {
            return Optional.empty();
        }

        ExecutableSkeletonPlan result = switch (plan) {
            case LinearPathPlan p -> fromLinearPath(p);
            case PolylinePathPlan p -> fromPolylinePath(p);
            case CompoundPlan p -> fromCompound(p);
            case GridPlan p -> fromGrid(p);
            case RadialPlan p -> fromRadial(p);
            case RectEnclosurePlan p -> fromRectEnclosure(p);
            case SpanSuspensionPlan p -> fromSpanSuspension(p);
            case VerticalStackPlan p -> fromVerticalStack(p);
            case VerticalTaperPlan p -> fromVerticalTaper(p);
            default -> fromGeneric(plan);
        };

        if (result == null) {
            return Optional.empty();
        }
        mergeBaseFields(plan, result);
        return Optional.of(result.applyParams());
    }

    private static ExecutableSkeletonPlan fromLinearPath(LinearPathPlan plan) {
        if (plan.pathPoints != null && plan.pathPoints.size() >= 2) {
            ExecutableSkeletonPlan esp = new ExecutableSkeletonPlan(SkeletonType.PATH_POLYLINE);
            esp.width = Math.max(1, plan.thickness);
            esp.height = Math.max(1, plan.height);
            esp.put("points", toPointMaps(plan.pathPoints, BlockPos.ORIGIN));
            esp.put("towerSpacing", plan.towerSpacing);
            esp.put("crenels", plan.crenels);
            return esp;
        }
        ExecutableSkeletonPlan esp = new ExecutableSkeletonPlan(SkeletonType.LINEAR_PATH);
        esp.width = Math.max(1, plan.thickness);
        esp.height = Math.max(1, plan.height);
        esp.length = 10;
        esp.put("towerSpacing", plan.towerSpacing);
        esp.put("crenels", plan.crenels);
        return esp;
    }

    private static ExecutableSkeletonPlan fromPolylinePath(PolylinePathPlan plan) {
        ExecutableSkeletonPlan esp = new ExecutableSkeletonPlan(SkeletonType.PATH_POLYLINE);
        esp.width = plan.width;
        esp.conformTerrain = plan.followTerrain;
        esp.put("points", toPointMaps(plan.points, BlockPos.ORIGIN));
        esp.put("lamps", plan.lamps);
        esp.put("lampInterval", plan.lampInterval);
        return esp;
    }

    private static ExecutableSkeletonPlan fromCompound(CompoundPlan plan) {
        ExecutableSkeletonPlan esp = new ExecutableSkeletonPlan(SkeletonType.COMPOUND);
        for (CompoundPlan.Component c : plan.components) {
            toExecutable(c.plan).ifPresent(esp::addChild);
        }
        return esp.children.isEmpty() ? null : esp;
    }

    private static ExecutableSkeletonPlan fromGrid(GridPlan plan) {
        ExecutableSkeletonPlan esp = new ExecutableSkeletonPlan(SkeletonType.GRID);
        esp.put("width", plan.intParam("width", 24));
        esp.put("depth", plan.intParam("depth", 24));
        esp.put("step", plan.intParam("step", 4));
        toExecutable(plan.module).ifPresent(esp::addChild);
        return esp;
    }

    private static ExecutableSkeletonPlan fromRadial(RadialPlan plan) {
        ExecutableSkeletonPlan esp = new ExecutableSkeletonPlan(SkeletonType.RADIAL_RING);
        RadialPrimitive first = plan.primitives.isEmpty() ? null : plan.primitives.get(0);
        int radius = first != null ? first.outerRadius : plan.intParam("radius", 6);
        esp.put("radius", radius);
        if (first != null) {
            esp.put("innerRadius", first.innerRadius);
            esp.put("y0", first.y0);
            esp.put("y1", first.y1);
        }
        return esp;
    }

    private static ExecutableSkeletonPlan fromRectEnclosure(RectEnclosurePlan plan) {
        ExecutableSkeletonPlan esp = new ExecutableSkeletonPlan(SkeletonType.PERIMETER_LOOP);
        int hw = plan.width / 2;
        int hd = plan.depth / 2;
        List<Map<String, Integer>> corners = List.of(
                pointMap(-hw, 0, -hd),
                pointMap(hw, 0, -hd),
                pointMap(hw, 0, hd),
                pointMap(-hw, 0, hd),
                pointMap(-hw, 0, -hd)
        );
        esp.put("points", corners);
        esp.height = plan.wallHeight;
        esp.width = Math.max(1, plan.thickness);
        esp.facing = plan.gateSide;
        esp.put("gateWidth", plan.gateWidth);
        esp.put("battlements", plan.battlements);
        esp.put("battlementSpacing", plan.battlementSpacing);
        return esp;
    }

    private static ExecutableSkeletonPlan fromSpanSuspension(SpanSuspensionPlan plan) {
        ExecutableSkeletonPlan esp = new ExecutableSkeletonPlan(SkeletonType.SPAN_SUSPENSION);
        esp.put("deckCenters", plan.deckCenters);
        esp.put("deckHalfWidth", plan.deckHalfWidth);
        esp.put("towerIndex1", plan.towerIndex1);
        esp.put("towerIndex2", plan.towerIndex2);
        esp.put("towerHeight", plan.towerHeight);
        esp.put("cableY", plan.cableY);
        esp.put("refined", plan.refined);
        if (plan.deckCenters != null && plan.deckCenters.size() >= 2) {
            BlockPos start = plan.deckCenters.get(0);
            BlockPos end = plan.deckCenters.get(plan.deckCenters.size() - 1);
            esp.put("end", pointMap(
                    end.getX() - start.getX(),
                    end.getY() - start.getY(),
                    end.getZ() - start.getZ()
            ));
        }
        return esp;
    }

    private static ExecutableSkeletonPlan fromVerticalStack(VerticalStackPlan plan) {
        ExecutableSkeletonPlan esp = new ExecutableSkeletonPlan(SkeletonType.VERTICAL_STACK);
        esp.facing = plan.facing != null ? plan.facing : Direction.NORTH;
        esp.put("levels", plan.levels);
        esp.put("refined", plan.refined);
        if (plan.levels != null && !plan.levels.isEmpty()) {
            int totalH = 0;
            for (VerticalStackPlan.Level lv : plan.levels) {
                totalH = Math.max(totalH, lv.y0 + lv.height);
            }
            esp.height = totalH;
        }
        return esp;
    }

    private static ExecutableSkeletonPlan fromVerticalTaper(VerticalTaperPlan plan) {
        ExecutableSkeletonPlan esp = new ExecutableSkeletonPlan(SkeletonType.VERTICAL_TAPER);
        esp.height = plan.height;
        esp.put("baseHalf", plan.baseHalf);
        esp.put("topHalf", plan.topHalf);
        esp.put("halfByY", plan.halfByY);
        esp.put("platformsY", plan.platformsY);
        esp.put("refined", plan.refined);
        esp.put("spireStartY", plan.spireStartY);
        esp.put("spireEndY", plan.spireEndY);
        return esp;
    }

    private static ExecutableSkeletonPlan fromGeneric(SkeletonPlan plan) {
        SkeletonType type = plan.type();
        if (type == null) {
            return null;
        }
        ExecutableSkeletonPlan esp = new ExecutableSkeletonPlan(type);
        if (plan.points != null && !plan.points.isEmpty()) {
            BlockPos anchor = plan.anchor != null ? plan.anchor : BlockPos.ORIGIN;
            esp.put("points", toPointMaps(plan.points, anchor));
        }
        return esp;
    }

    private static void mergeBaseFields(SkeletonPlan source, ExecutableSkeletonPlan target) {
        if (source.params != null) {
            for (Map.Entry<String, Object> e : source.params.entrySet()) {
                target.params.putIfAbsent(e.getKey(), e.getValue());
            }
        }
        if (source.anchor != null) {
            target.put("anchorX", source.anchor.getX());
            target.put("anchorY", source.anchor.getY());
            target.put("anchorZ", source.anchor.getZ());
        }
    }

    private static List<Map<String, Integer>> toPointMaps(List<BlockPos> points, BlockPos origin) {
        List<Map<String, Integer>> out = new ArrayList<>();
        BlockPos o = origin != null ? origin : BlockPos.ORIGIN;
        for (BlockPos p : points) {
            if (p == null) continue;
            out.add(pointMap(p.getX() - o.getX(), p.getY() - o.getY(), p.getZ() - o.getZ()));
        }
        return out;
    }

    private static Map<String, Integer> pointMap(int dx, int dy, int dz) {
        Map<String, Integer> m = new HashMap<>();
        m.put("dx", dx);
        m.put("dy", dy);
        m.put("dz", dz);
        return m;
    }
}
