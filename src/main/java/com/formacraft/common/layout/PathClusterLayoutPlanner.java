package com.formacraft.common.layout;

import com.formacraft.common.terrain.TerrainStrategySampler;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.List;

/**
 * PathClusterLayoutPlanner（路径集群布局规划器）
 * 
 * 核心功能：从 PathTool 路径生成建筑站点序列
 * 
 * ✅ 功能点（v1）：
 * - PathTool 的 world points → 2D polyline
 * - 按 spacing 重采样（每隔 N 格一个站点）
 * - 站点朝向 = 路径切线方向（N/E/S/W）
 * - 地形：用 TerrainStrategySampler.sampleGroundY() 把站点落到地面
 * - 简易碰撞：站点之间按 footprint+clearance 做 AABB 近似避免重叠
 * - 可选：左右偏移（比如沿路两侧布置办公楼）
 */
public class PathClusterLayoutPlanner {

    /**
     * 配置选项
     */
    public static class Options {
        public int spacing = 10;              // 站点间距（格）
        public int footprintW = 9;             // 默认占地宽
        public int footprintD = 11;            // 默认占地深
        public int clearance = 2;               // 安全边距（防止挤在一起）
        public int lateralOffset = 0;          // 沿法线方向偏移（>0右侧，<0左侧）
        public int maxSites = 64;              // 最多生成多少个站点
        public boolean snapToCardinal = true;  // 朝向是否只用 NESW
        public String defaultTag = "cluster_site";
    }

    private final TerrainStrategySampler terrain;

    public PathClusterLayoutPlanner(TerrainStrategySampler terrain) {
        this.terrain = terrain;
    }

    /**
     * 从路径点生成站点序列
     *
     * @param world 世界
     * @param pathWorldPoints PathTool 收集的世界点（BlockPos）
     * @param origin 用于参考（可空）
     * @param opt 配置
     * @param constraints 禁区/轮廓/选区约束（v1 可传 ALLOW_ALL）
     * @return 站点列表
     */
    public List<LayoutSite> plan(
            World world,
            List<BlockPos> pathWorldPoints,
            BlockPos origin,
            Options opt,
            LayoutConstraints constraints
    ) {
        if (pathWorldPoints == null || pathWorldPoints.size() < 2) {
            return List.of();
        }
        if (constraints == null) {
            constraints = LayoutConstraints.ALLOW_ALL;
        }

        // 1) polyline -> samples (double)
        List<PathSample> poly = toPolyline(pathWorldPoints);

        // 2) resample by spacing
        List<PathSample> samples = resample(poly, opt.spacing);
        if (samples.isEmpty()) {
            return List.of();
        }

        // 3) generate sites (with facing + ground y)
        List<LayoutSite> sites = new ArrayList<>();
        List<AABB2> occupied = new ArrayList<>();

        for (int i = 0; i < samples.size() && sites.size() < opt.maxSites; i++) {
            PathSample s = samples.get(i);

            // 切线方向：用前后点差分
            Direction facing = estimateFacing(samples, i, opt.snapToCardinal);

            // 法线偏移（沿 facing 的右侧方向）
            int ox = 0, oz = 0;
            if (opt.lateralOffset != 0) {
                Direction right = rightOf(facing);
                ox = right.getOffsetX() * opt.lateralOffset;
                oz = right.getOffsetZ() * opt.lateralOffset;
            }

            int x = (int) Math.round(s.x) + ox;
            int z = (int) Math.round(s.z) + oz;

            int y = terrain != null ? terrain.sampleGroundY(world, x, z) : (origin != null ? origin.getY() : 64);
            BlockPos anchor = new BlockPos(x, y, z);

            // 约束检查（禁区/轮廓/选区）
            if (!constraints.allowFootprint(anchor, opt.footprintW, opt.footprintD, opt.clearance)) {
                continue;
            }

            // 碰撞避免：用 2D AABB 近似
            AABB2 box = footprintAabb(anchor, facing, opt.footprintW, opt.footprintD, opt.clearance);
            boolean collide = false;
            for (AABB2 o : occupied) {
                if (o.intersects(box)) {
                    collide = true;
                    break;
                }
            }
            if (collide) {
                continue;
            }

            occupied.add(box);
            sites.add(new LayoutSite(anchor, facing, opt.footprintW, opt.footprintD, opt.clearance, opt.defaultTag));
        }

        return sites;
    }

    // -----------------------------
    // polyline helpers
    // -----------------------------

    /**
     * 将 BlockPos 列表转换为 PathSample 列表
     */
    private List<PathSample> toPolyline(List<BlockPos> pts) {
        List<PathSample> out = new ArrayList<>(pts.size());
        for (BlockPos p : pts) {
            out.add(new PathSample(p.getX() + 0.5, p.getZ() + 0.5));
        }
        return out;
    }

    /**
     * 按固定间隔重采样 polyline
     */
    private List<PathSample> resample(List<PathSample> poly, int spacing) {
        if (poly.size() < 2) {
            return List.of();
        }
        double step = Math.max(1.0, spacing);

        List<PathSample> out = new ArrayList<>();
        PathSample prev = poly.get(0);
        out.add(prev);

        double carry = 0.0;

        for (int i = 1; i < poly.size(); i++) {
            PathSample cur = poly.get(i);
            double dx = cur.x - prev.x;
            double dz = cur.z - prev.z;
            double segLen = Math.hypot(dx, dz);
            if (segLen < 1e-6) {
                continue;
            }

            double ux = dx / segLen;
            double uz = dz / segLen;

            double dist = carry;
            while (dist + step <= segLen) {
                dist += step;
                double nx = prev.x + ux * dist;
                double nz = prev.z + uz * dist;
                out.add(new PathSample(nx, nz));
            }
            carry = segLen - dist;
            prev = cur;
        }

        return out;
    }

    /**
     * 估计朝向（使用前后点差分）
     */
    private Direction estimateFacing(List<PathSample> samples, int i, boolean snapCardinal) {
        PathSample a = samples.get(Math.max(0, i - 1));
        PathSample b = samples.get(Math.min(samples.size() - 1, i + 1));
        double dx = b.x - a.x;
        double dz = b.z - a.z;

        if (!snapCardinal) {
            // v1 仍然返回 NESW（否则你要支持任意 yaw）
            return cardinalFromVector(dx, dz);
        }
        return cardinalFromVector(dx, dz);
    }

    /**
     * 从向量计算主要方向（NESW）
     */
    private Direction cardinalFromVector(double dx, double dz) {
        // Minecraft 常用：Z+ = SOUTH, Z- = NORTH, X+ = EAST, X- = WEST
        if (Math.abs(dx) >= Math.abs(dz)) {
            return dx >= 0 ? Direction.EAST : Direction.WEST;
        } else {
            return dz >= 0 ? Direction.SOUTH : Direction.NORTH;
        }
    }

    /**
     * 获取右侧方向
     */
    private Direction rightOf(Direction facing) {
        return switch (facing) {
            case NORTH -> Direction.EAST;
            case SOUTH -> Direction.WEST;
            case EAST -> Direction.SOUTH;
            case WEST -> Direction.NORTH;
            default -> Direction.EAST;
        };
    }

    // -----------------------------
    // AABB collision (2D)
    // -----------------------------

    /**
     * 2D AABB（用于碰撞检测）
     */
    private static class AABB2 {
        final int minX, minZ, maxX, maxZ;

        AABB2(int minX, int minZ, int maxX, int maxZ) {
            this.minX = minX;
            this.minZ = minZ;
            this.maxX = maxX;
            this.maxZ = maxZ;
        }

        boolean intersects(AABB2 o) {
            return !(o.maxX < minX || o.minX > maxX || o.maxZ < minZ || o.minZ > maxZ);
        }
    }

    /**
     * 计算站点的 footprint AABB
     */
    private AABB2 footprintAabb(BlockPos anchor, Direction facing, int w, int d, int clearance) {
        // footprint：以 anchor 为中心，宽 w（侧向），深 d（朝向）
        int halfW = Math.max(1, w / 2);
        int halfD = Math.max(1, d / 2);

        // 简化：AABB 不旋转（保守）
        int r = Math.max(halfW, halfD) + clearance;
        return new AABB2(anchor.getX() - r, anchor.getZ() - r, anchor.getX() + r, anchor.getZ() + r);
    }
}

