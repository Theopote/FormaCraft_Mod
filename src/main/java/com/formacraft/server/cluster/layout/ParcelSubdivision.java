package com.formacraft.server.cluster.layout;

import com.formacraft.common.buildcontext.OutlineShape;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.List;

/**
 * C2：地块划分（polygon subdivision + parcel allocation）。
 *
 * <p>把一个任意 {@link OutlineShape}（多边形或圆）按目标栋数切成若干矩形地块，地块之间留出
 * 道路/院落间距。算法为确定性递归二分：每次挑当前最大的矩形，沿其较长边切两半并扣除路宽，
 * 直到达到目标数量或无法再切；最后只保留"中心落在轮廓内且尺寸达标"的地块。</p>
 *
 * <p>纯函数、无随机、无副作用，产出世界坐标 XZ 的 {@link Parcel}，供集群排布/单体编译按需消费
 * （单体 → 单地块；建筑群 → 多地块）。地形贴合与最终约束仍由既有 TerrainFit/BuildConstraint 链负责。</p>
 */
public final class ParcelSubdivision {

    private ParcelSubdivision() {}

    /** 一个矩形地块（世界坐标，包含边界）。 */
    public record Parcel(int minX, int minZ, int maxX, int maxZ) {
        public int width() { return maxX - minX + 1; }
        public int depth() { return maxZ - minZ + 1; }
        public int centerX() { return (minX + maxX) / 2; }
        public int centerZ() { return (minZ + maxZ) / 2; }
        public long area() { return (long) width() * (long) depth(); }
    }

    /**
     * 按目标数量划分地块。
     *
     * @param outline       轮廓（世界坐标）
     * @param targetCount   目标地块数（&lt;=1 表示单体，直接返回包围盒地块）
     * @param roadWidth     地块间道路/间距宽度（block，&gt;=0）
     * @param minParcelSize 地块最小边长（低于此不再细分，也会被过滤）
     * @return 世界坐标地块列表（可能少于 targetCount：受轮廓形状/最小尺寸限制）
     */
    public static List<Parcel> subdivide(OutlineShape outline, int targetCount, int roadWidth, int minParcelSize) {
        if (outline == null) {
            return List.of();
        }
        int[] bbox = boundingBox(outline);
        if (bbox == null) {
            return List.of();
        }
        int minX = bbox[0], minZ = bbox[1], maxX = bbox[2], maxZ = bbox[3];
        if (maxX <= minX || maxZ <= minZ) {
            return List.of();
        }
        int road = Math.max(0, roadWidth);
        int minSize = Math.max(1, minParcelSize);

        List<Parcel> parcels = new ArrayList<>();
        parcels.add(new Parcel(minX, minZ, maxX, maxZ));

        int target = Math.max(1, targetCount);
        // 递归二分：每轮切当前最大且可切的地块。
        int guard = 0;
        while (parcels.size() < target && guard < 4096) {
            guard++;
            int idx = largestSplittable(parcels, minSize, road);
            if (idx < 0) {
                break; // 没有可再切的地块
            }
            Parcel p = parcels.remove(idx);
            Parcel[] halves = split(p, road, minSize);
            if (halves == null) {
                parcels.add(p); // 切不动，放回（理论上 largestSplittable 已保证可切）
                break;
            }
            parcels.add(halves[0]);
            parcels.add(halves[1]);
        }

        // 过滤：中心必须在轮廓内，且尺寸达标。
        List<Parcel> out = new ArrayList<>();
        for (Parcel p : parcels) {
            if (p.width() < minSize || p.depth() < minSize) {
                continue;
            }
            if (containsXZ(outline, p.centerX(), p.centerZ())) {
                out.add(p);
            }
        }
        return out;
    }

    /** 单体便捷入口：返回轮廓内最大的单一地块（去掉边缘 margin）。 */
    public static Parcel singleParcel(OutlineShape outline, int margin) {
        List<Parcel> one = subdivide(outline, 1, 0, 1);
        if (one.isEmpty()) {
            return null;
        }
        Parcel p = one.get(0);
        int m = Math.max(0, margin);
        Parcel shrunk = new Parcel(p.minX() + m, p.minZ() + m, p.maxX() - m, p.maxZ() - m);
        return (shrunk.width() >= 1 && shrunk.depth() >= 1) ? shrunk : p;
    }

    private static int largestSplittable(List<Parcel> parcels, int minSize, int road) {
        int best = -1;
        long bestArea = -1;
        for (int i = 0; i < parcels.size(); i++) {
            Parcel p = parcels.get(i);
            // 可切：较长边切两半后，每半仍 >= minSize（含扣路宽）。
            int longEdge = Math.max(p.width(), p.depth());
            int half = (longEdge - road) / 2;
            if (half < minSize) {
                continue;
            }
            if (p.area() > bestArea) {
                bestArea = p.area();
                best = i;
            }
        }
        return best;
    }

    private static Parcel[] split(Parcel p, int road, int minSize) {
        boolean splitX = p.width() >= p.depth();
        if (splitX) {
            int span = p.width() - road;
            int leftW = span / 2;
            int rightW = span - leftW;
            if (leftW < minSize || rightW < minSize) {
                return null;
            }
            int leftMaxX = p.minX() + leftW - 1;
            int rightMinX = leftMaxX + 1 + road;
            return new Parcel[] {
                    new Parcel(p.minX(), p.minZ(), leftMaxX, p.maxZ()),
                    new Parcel(rightMinX, p.minZ(), p.maxX(), p.maxZ())
            };
        } else {
            int span = p.depth() - road;
            int topD = span / 2;
            int botD = span - topD;
            if (topD < minSize || botD < minSize) {
                return null;
            }
            int topMaxZ = p.minZ() + topD - 1;
            int botMinZ = topMaxZ + 1 + road;
            return new Parcel[] {
                    new Parcel(p.minX(), p.minZ(), p.maxX(), topMaxZ),
                    new Parcel(p.minX(), botMinZ, p.maxX(), p.maxZ())
            };
        }
    }

    private static int[] boundingBox(OutlineShape outline) {
        if ("circle".equalsIgnoreCase(outline.shapeType()) && outline.center() != null && outline.radius() > 0) {
            int cx = outline.center().getX();
            int cz = outline.center().getZ();
            int r = outline.radius();
            return new int[] {cx - r, cz - r, cx + r, cz + r};
        }
        List<BlockPos> verts = outline.vertices();
        if (verts == null || verts.size() < 3) {
            return null;
        }
        int minX = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (BlockPos p : verts) {
            minX = Math.min(minX, p.getX());
            maxX = Math.max(maxX, p.getX());
            minZ = Math.min(minZ, p.getZ());
            maxZ = Math.max(maxZ, p.getZ());
        }
        return new int[] {minX, minZ, maxX, maxZ};
    }

    /** 点是否在轮廓内（圆：距离；多边形：射线法）。 */
    public static boolean containsXZ(OutlineShape outline, int x, int z) {
        if (outline == null) {
            return false;
        }
        if ("circle".equalsIgnoreCase(outline.shapeType()) && outline.center() != null && outline.radius() > 0) {
            double dx = x - outline.center().getX();
            double dz = z - outline.center().getZ();
            double r = outline.radius();
            return (dx * dx + dz * dz) <= (r * r);
        }
        List<BlockPos> verts = outline.vertices();
        if (verts == null || verts.size() < 3) {
            return false;
        }
        boolean inside = false;
        int n = verts.size();
        for (int i = 0, j = n - 1; i < n; j = i++) {
            double xi = verts.get(i).getX() + 0.5, zi = verts.get(i).getZ() + 0.5;
            double xj = verts.get(j).getX() + 0.5, zj = verts.get(j).getZ() + 0.5;
            boolean intersect = ((zi > z) != (zj > z))
                    && (x < (xj - xi) * (z - zi) / (zj - zi) + xi);
            if (intersect) {
                inside = !inside;
            }
        }
        return inside;
    }
}
