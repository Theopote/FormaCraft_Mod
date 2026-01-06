package com.formacraft.server.skeleton.gen.geometry;

import com.formacraft.ai.context.OutlineContext;
import com.formacraft.ai.context.ProtectedZoneContext;
import com.formacraft.ai.context.SelectionContext;
import com.formacraft.ai.context.SymmetryContext;
import com.formacraft.client.buildcontext.BuildContextResolver;
import com.formacraft.client.tool.SymmetryMode;
import com.formacraft.common.buildcontext.BuildContext;
import com.formacraft.common.geometry.tool.GeometryConstraintPipeline;
import com.formacraft.common.geometry.tool.footprint.FootprintConstraint;
import com.formacraft.common.geometry.tool.footprint.FootprintRegion;
import com.formacraft.common.geometry.tool.footprint.PolygonFootprint;
import com.formacraft.common.geometry.tool.nobuild.NoBuildConstraint;
import com.formacraft.common.geometry.tool.nobuild.NoBuildZone;
import com.formacraft.common.geometry.tool.nobuild.PolygonNoBuildZone;
import com.formacraft.common.geometry.tool.symmetry.SymmetryPlane;
import com.formacraft.common.geometry.tool.symmetry.SymmetryProcessor;
import com.formacraft.common.model.constraint.ProtectedZone;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.List;

/**
 * ToolConstraintBuilder（工具约束构建器）
 * 
 * 从 BuildContext 和工具状态构建 GeometryConstraintPipeline 和 SymmetryProcessor
 * 
 * 这是连接客户端工具状态和服务端约束系统的桥梁
 */
public final class ToolConstraintBuilder {

    private ToolConstraintBuilder() {}

    /**
     * 从当前工具状态构建约束管道和对称处理器
     * 
     * @param restrictToSelection 是否限制到选区
     * @return 约束构建结果（包含约束管道和对称处理器）
     */
    public static ConstraintResult buildConstraints(boolean restrictToSelection) {
        GeometryConstraintPipeline pipeline = new GeometryConstraintPipeline();
        SymmetryProcessor symmetry = null;

        BuildContext bc = BuildContextResolver.resolve(restrictToSelection);
        
        // 1. 选区约束（Footprint）
        if (SelectionContext.hasSelection()) {
            BlockPos min = SelectionContext.min();
            BlockPos max = SelectionContext.max();
            if (min != null && max != null) {
                // 创建矩形轮廓
                List<PolygonFootprint.Point2> polygon = List.of(
                    new PolygonFootprint.Point2(min.getX(), min.getZ()),
                    new PolygonFootprint.Point2(max.getX(), min.getZ()),
                    new PolygonFootprint.Point2(max.getX(), max.getZ()),
                    new PolygonFootprint.Point2(min.getX(), max.getZ())
                );
                FootprintRegion footprint = new PolygonFootprint(polygon);
                pipeline.add(new FootprintConstraint(footprint));
            }
        }

        // 2. 轮廓约束（Outline）
        if (OutlineContext.hasOutline()) {
            var outline = OutlineContext.shape();
            if (outline != null && outline.points() != null && outline.points().size() >= 3) {
                List<PolygonFootprint.Point2> polygon = new ArrayList<>();
                for (BlockPos p : outline.points()) {
                    polygon.add(new PolygonFootprint.Point2(p.getX(), p.getZ()));
                }
                FootprintRegion footprint = new PolygonFootprint(polygon);
                pipeline.add(new FootprintConstraint(footprint));
            }
        }

        // 3. 禁区约束（No-Build Zones）
        if (ProtectedZoneContext.hasZones()) {
            for (ProtectedZone zone : ProtectedZoneContext.zones()) {
                if (zone == null || zone.min() == null || zone.max() == null) continue;
                ProtectedZone n = zone.normalized();
                
                // 创建矩形禁区
                List<PolygonNoBuildZone.Point2> polygon = List.of(
                    new PolygonNoBuildZone.Point2(n.min().getX(), n.min().getZ()),
                    new PolygonNoBuildZone.Point2(n.max().getX(), n.min().getZ()),
                    new PolygonNoBuildZone.Point2(n.max().getX(), n.max().getZ()),
                    new PolygonNoBuildZone.Point2(n.min().getX(), n.max().getZ())
                );
                NoBuildZone noBuildZone = new PolygonNoBuildZone(polygon);
                pipeline.add(new NoBuildConstraint(noBuildZone));
            }
        }

        // 4. 对称处理器
        if (SymmetryContext.enabled()) {
            var mode = SymmetryContext.mode();
            if (mode != null) {
                // 简化处理：使用选区中心作为对称轴
                int centerX = 0;
                int centerZ = 0;
                
                if (SelectionContext.hasSelection()) {
                    BlockPos min = SelectionContext.min();
                    BlockPos max = SelectionContext.max();
                    if (min != null && max != null) {
                        centerX = (min.getX() + max.getX() + 1) / 2;
                        centerZ = (min.getZ() + max.getZ() + 1) / 2;
                    }
                } else if (bc != null && bc.origin != null) {
                    centerX = bc.origin.getX();
                    centerZ = bc.origin.getZ();
                }

                SymmetryPlane plane = null;
                switch (mode) {
                    case MIRROR_X -> plane = new SymmetryPlane(SymmetryPlane.Axis.X, centerX);
                    case MIRROR_Z -> plane = new SymmetryPlane(SymmetryPlane.Axis.Z, centerZ);
                    case BOTH -> {
                        // 双向对称：先处理 X 轴，Z 轴在后续处理中处理
                        plane = new SymmetryPlane(SymmetryPlane.Axis.X, centerX);
                    }
                    case CUSTOM_AXIS, NONE -> {
                        // CUSTOM_AXIS 等需要更复杂的处理
                        // NONE 不需要处理
                    }
                }
                
                if (plane != null) {
                    symmetry = new SymmetryProcessor(plane);
                }
            }
        }

        return new ConstraintResult(pipeline, symmetry);
    }

    /**
     * 约束构建结果
     */
    public record ConstraintResult(
            GeometryConstraintPipeline constraintPipeline,
            SymmetryProcessor symmetryProcessor
    ) {}
}

