package com.formacraft.server.cluster.layout;

import com.formacraft.common.buildcontext.OutlineShape;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * C3：统一的 Site → Layout 前端（增量、不改动既有 solver）。
 *
 * <p>把"单体"与"建筑群"在布局层收敛为同一套流程：给定一个 {@link OutlineShape} 和一组
 * {@link BuildingUnit}，用 {@link ParcelSubdivision} 将场地切成地块，再"大单体配大地块"地分配，
 * 产出与既有集群管线一致的 {@link BuildingPlacement}（originRel 相对 cluster 原点、为 footprint 的
 * 最小角）。单体即 units.size()==1（整块场地一个地块），建筑群即 N 个地块——同一入口。</p>
 *
 * <p>确定性、无随机；这是 C2 分地块算法的直接消费者，也是后续把 PlanProgram(单体) 与
 * PlacementSolver(集群) 完全统一到 Site→Layout→BuildingSpec 的落脚点。既有
 * {@link PlacementSolver} 采样式排布仍可独立使用，本类是可选的确定性替代/补充。</p>
 */
public final class SiteLayoutPlanner {

    private SiteLayoutPlanner() {}

    /**
     * 规划一组建筑在场地内的地块占位。
     *
     * @param outline       场地轮廓（世界坐标）
     * @param clusterOrigin 集群原点（世界坐标；输出 originRel 相对它）
     * @param units         建筑单元（模板）
     * @param roadWidth     地块间道路/间距
     * @return 每个 unit 一个 {@link BuildingPlacement}（尽力而为；地块不足时复用最后一块）
     */
    public static List<BuildingPlacement> plan(OutlineShape outline, BlockPos clusterOrigin,
                                               List<BuildingUnit> units, int roadWidth) {
        if (outline == null || clusterOrigin == null || units == null || units.isEmpty()) {
            return List.of();
        }
        int n = units.size();
        int minParcel = 3;
        for (BuildingUnit u : units) {
            if (u != null) {
                minParcel = Math.max(minParcel, Math.min(u.width, u.depth));
            }
        }

        List<ParcelSubdivision.Parcel> parcels =
                ParcelSubdivision.subdivide(outline, n, Math.max(0, roadWidth), minParcel);
        if (parcels.isEmpty()) {
            return List.of();
        }
        // 大地块优先分给大单体：地块按面积降序，单体按 footprint 面积×重要度降序。
        parcels.sort(Comparator.comparingLong(ParcelSubdivision.Parcel::area).reversed());
        List<BuildingUnit> sorted = new ArrayList<>(units);
        sorted.sort(Comparator.comparingLong(
                (BuildingUnit u) -> (long) u.width * u.depth * Math.max(1, u.importance)).reversed());

        List<BuildingPlacement> out = new ArrayList<>(sorted.size());
        int pc = parcels.size();
        for (int i = 0; i < sorted.size(); i++) {
            BuildingUnit u = sorted.get(i);
            ParcelSubdivision.Parcel parcel = parcels.get(Math.min(i, pc - 1));

            int rot = chooseRotation(u, parcel);
            int w = (rot % 180 == 0) ? u.width : u.depth;
            int d = (rot % 180 == 0) ? u.depth : u.width;

            // 在地块内居中放置并夹紧到地块边界（单体大于地块时退化到地块最小角）。
            int minX = parcel.centerX() - w / 2;
            int minZ = parcel.centerZ() - d / 2;
            minX = Math.max(parcel.minX(), Math.min(minX, parcel.maxX() - w + 1));
            minZ = Math.max(parcel.minZ(), Math.min(minZ, parcel.maxZ() - d + 1));

            BlockPos originRel = new BlockPos(
                    minX - clusterOrigin.getX(), 0, minZ - clusterOrigin.getZ());
            out.add(new BuildingPlacement(u, originRel, rot));
        }
        return out;
    }

    /** 单体便捷入口：整块场地一个地块，居中放置。 */
    public static BuildingPlacement planSingle(OutlineShape outline, BlockPos clusterOrigin,
                                               BuildingUnit unit, int margin) {
        if (outline == null || clusterOrigin == null || unit == null) {
            return null;
        }
        List<BuildingPlacement> one = plan(outline, clusterOrigin, List.of(unit), 0);
        return one.isEmpty() ? null : one.get(0);
    }

    /** 让单体长边贴合地块长边，减少旋转造成的浪费。 */
    private static int chooseRotation(BuildingUnit u, ParcelSubdivision.Parcel p) {
        boolean parcelWide = p.width() >= p.depth();
        boolean unitWide = u.width >= u.depth;
        return (parcelWide == unitWide) ? 0 : 90;
    }
}
