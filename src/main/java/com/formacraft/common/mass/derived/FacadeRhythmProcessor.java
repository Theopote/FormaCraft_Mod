package com.formacraft.common.mass.derived;

import com.formacraft.common.component.socket.Socket;
import com.formacraft.common.llm.dto.PlanSkeleton;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.util.*;
import java.util.stream.Collectors;

/**
 * FacadeRhythmProcessor（立面节奏处理器）
 * <p>
 * 🎯 核心职责：
 * 它是 Socket 的"审美过滤器"。
 * <p>
 * 立面节奏系统在整体架构中的位置：
 * ```
 * BuildingMass
 *   → Skeleton
 *     → Socket（候选）
 *       → FacadeRhythm（筛选 / 对齐 / 分组）← 这里
 *         → Final Socket
 *           → Component Placement
 * ```
 * <p>
 * 执行流程：
 * 1. 收集候选 Socket
 * 2. 按立面方向排序
 * 3. 应用 RhythmMode（节奏筛选）
 * 4. 应用 AlignmentMode（对齐）
 * 5. 应用 SymmetryMode（对称）
 * 6. 应用 VariationMode（防"太工整"）
 */
public final class FacadeRhythmProcessor {

    private FacadeRhythmProcessor() {}

    /**
     * 处理立面节奏
     * <p>
     * 输入：
     * - Exterior Skeleton
     * - 某一 FloorLayer
     * - 某一朝向（N / S / E / W）
     * <p>
     * 输出：经过节奏处理后的 Socket 列表
     *
     * @param candidates 候选 Socket 列表
     * @param layer 楼层
     * @param facing 朝向
     * @param profile 节奏配置
     * @param domain Plan Domain（用于提取 Axis）
     * @return 处理后的 Socket 列表
     */
    public static List<Socket> processRhythm(
            List<Socket> candidates,
            FloorLayer layer,
            Direction facing,
            FacadeRhythmProfile profile,
            PlanSkeleton domain
    ) {
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }

        List<Socket> result = new ArrayList<>(candidates);

        // Step 2: 按立面方向排序（左 → 右 或 前 → 后）
        sortByHorizontalPosition(result, facing);

        // Step 3: 应用 RhythmMode（节奏筛选）
        applyRhythmMode(result, profile.mode, profile.spacing, facing);

        // Step 4: 应用 AlignmentMode（对齐）
        applyAlignment(result, profile.align, facing, domain);

        // Step 5: 应用 SymmetryMode（对称）
        // ⚠️ 只对 Window / Balcony 生效，不对 Door
        applySymmetry(result, profile.symmetry, facing, layer);

        // Step 6: 应用 VariationMode（防"太工整"）
        applyVariation(result, profile.variation, facing);

        return result;
    }

    /**
     * Step 2: 按立面方向排序
     * <p>
     * 左 → 右 或 前 → 后
     */
    private static void sortByHorizontalPosition(List<Socket> sockets, Direction facing) {
        sockets.sort((a, b) -> {
            BlockPos posA = a.centerBlockPos();
            BlockPos posB = b.centerBlockPos();

            // 根据朝向确定排序方向
            int compare;
            switch (facing) {
                case NORTH, SOUTH -> compare = Integer.compare(posA.getX(), posB.getX()); // X 方向
                case EAST, WEST -> compare = Integer.compare(posA.getZ(), posB.getZ()); // Z 方向
                default -> compare = 0;
            }

            return compare;
        });
    }

    /**
     * Step 3: 应用 RhythmMode（节奏筛选）
     */
    private static void applyRhythmMode(
            List<Socket> sockets,
            FacadeRhythmProfile.RhythmMode mode,
            int spacing,
            Direction facing
    ) {
        switch (mode) {
            case REGULAR -> applyRegularRhythm(sockets, spacing);
            case GROUPED -> applyGroupedRhythm(sockets, spacing);
            case HIERARCHICAL -> applyHierarchicalRhythm(sockets, spacing);
            case FREE -> {
                // FREE 模式不筛选，保持所有 Socket
            }
        }
    }

    /**
     * REGULAR 节奏：等距
     * <p>
     * keep every N-th socket
     */
    private static void applyRegularRhythm(List<Socket> sockets, int spacing) {
        List<Socket> filtered = new ArrayList<>();
        for (int i = 0; i < sockets.size(); i++) {
            if (i % spacing == 0) {
                filtered.add(sockets.get(i));
            }
        }
        sockets.clear();
        sockets.addAll(filtered);
    }

    /**
     * GROUPED 节奏：成组（2-1-2）
     * <p>
     * [窗][窗] 空 [窗][窗]
     * <p>
     * v1 简化：使用固定的 2-1-2 模式
     */
    private static void applyGroupedRhythm(List<Socket> sockets, int spacing) {
        // v1 简化：按 2-1-2 模式筛选
        List<Socket> filtered = new ArrayList<>();
        int groupSize = 2;
        int gapSize = 1;

        for (int i = 0; i < sockets.size(); i++) {
            int posInCycle = i % (groupSize * 2 + gapSize);
            if (posInCycle < groupSize * 2) {
                filtered.add(sockets.get(i));
            }
        }
        sockets.clear();
        sockets.addAll(filtered);
    }

    /**
     * HIERARCHICAL 节奏：主次节奏
     * <p>
     * 中央密，边缘疏
     * <p>
     * v1 简化：中央区域保留更多 Socket
     */
    private static void applyHierarchicalRhythm(List<Socket> sockets, int spacing) {
        if (sockets.isEmpty()) {
            return;
        }

        int centerIndex = sockets.size() / 2;
        int centerRegionSize = sockets.size() / 3; // 中央区域占 1/3

        List<Socket> filtered = new ArrayList<>();
        for (int i = 0; i < sockets.size(); i++) {
            int distanceFromCenter = Math.abs(i - centerIndex);
            if (distanceFromCenter <= centerRegionSize) {
                // 中央区域：保留更多（每 2 个保留 1 个）
                if (i % 2 == 0) {
                    filtered.add(sockets.get(i));
                }
            } else {
                // 边缘区域：保留更少（每 spacing 个保留 1 个）
                if (i % spacing == 0) {
                    filtered.add(sockets.get(i));
                }
            }
        }
        sockets.clear();
        sockets.addAll(filtered);
    }

    /**
     * Step 4: 应用 AlignmentMode（对齐）
     */
    private static void applyAlignment(
            List<Socket> sockets,
            FacadeRhythmProfile.AlignmentMode align,
            Direction facing,
            PlanSkeleton domain
    ) {
        if (align == FacadeRhythmProfile.AlignmentMode.NONE || sockets.isEmpty()) {
            return;
        }

        switch (align) {
            case AXIS_ALIGNED -> alignToAxis(sockets, facing, domain);
            case CENTERED -> alignToCenter(sockets, facing);
            case EDGE_ALIGNED -> {
                // v1 简化：暂不实现
            }
            default -> {}
        }
    }

    /**
     * 对齐到主轴
     * <p>
     * 找 Primary Axis 在该立面上的投影
     * 最近的 Socket 对齐到轴线
     */
    private static void alignToAxis(
            List<Socket> sockets,
            Direction facing,
            PlanSkeleton domain
    ) {
        // v1 简化：假设轴在立面中心
        // 未来：需要从 domain.axes() 提取 Primary Axis 并投影
        alignToCenter(sockets, facing);
    }

    /**
     * 对齐到中心
     */
    private static void alignToCenter(List<Socket> sockets, Direction facing) {
        if (sockets.isEmpty()) {
            return;
        }

        // 找到中心位置
        int centerCoord = getCenterCoordinate(sockets, facing);

        // 找到最接近中心的 Socket
        Socket closestToCenter = sockets.stream()
                .min(Comparator.comparingInt(s -> {
                    BlockPos pos = s.centerBlockPos();
                    int coord = getCoordinate(pos, facing);
                    return Math.abs(coord - centerCoord);
                }))
                .orElse(null);

        if (closestToCenter != null) {
            // v1 简化：暂不实际移动 Socket（需要修改 Socket 的位置）
            // 未来：可以实现实际的坐标对齐
        }
    }

    /**
     * Step 5: 应用 SymmetryMode（对称）
     * <p>
     * ⚠️ 只对 Window / Balcony 生效，不对 Door
     */
    private static void applySymmetry(
            List<Socket> sockets,
            FacadeRhythmProfile.SymmetryMode symmetry,
            Direction facing,
            FloorLayer layer
    ) {
        if (symmetry == FacadeRhythmProfile.SymmetryMode.NONE || sockets.isEmpty()) {
            return;
        }

        // 过滤出 Door Socket（不参与对称）
        List<Socket> doorSockets = sockets.stream()
                .filter(s -> s.context != null && s.context.semanticTag != null && s.context.semanticTag.contains("door"))
                .collect(Collectors.toList());

        List<Socket> nonDoorSockets = sockets.stream()
                .filter(s -> !doorSockets.contains(s))
                .collect(Collectors.toList());

        if (symmetry == FacadeRhythmProfile.SymmetryMode.BILATERAL) {
            mirrorSockets(nonDoorSockets, facing);
        }

        // 将 Door Socket 重新添加回去
        sockets.clear();
        sockets.addAll(doorSockets);
        sockets.addAll(nonDoorSockets);
    }

    /**
     * 左右对称镜像
     * <p>
     * 找立面中线，镜像左右 Socket
     */
    private static void mirrorSockets(List<Socket> sockets, Direction facing) {
        if (sockets.isEmpty()) {
            return;
        }

        // v1 简化：暂不实际镜像 Socket 位置（需要创建镜像 Socket）
        // 计算中心坐标用于未来实现
        @SuppressWarnings("unused")
        int centerCoord = getCenterCoordinate(sockets, facing);
        // 未来：可以实现实际的镜像逻辑（基于 centerCoord）
    }

    /**
     * Step 6: 应用 VariationMode（防"太工整"）
     */
    private static void applyVariation(
            List<Socket> sockets,
            FacadeRhythmProfile.VariationMode variation,
            Direction facing
    ) {
        if (variation == FacadeRhythmProfile.VariationMode.NONE) {
            return;
        }

        Random random = new Random();

        switch (variation) {
            case SMALL_SHIFT -> {
                // v1 简化：暂不实际移动 Socket（需要修改 Socket 的位置）
                // 未来：可以实现 socket.x += random(-1, +1)
            }
            case SKIP_RANDOM -> {
                // 随机跳过 10% 的 Socket
                sockets.removeIf(s -> random.nextDouble() < 0.1);
            }
            default -> {}
        }
    }

    /**
     * 获取 Socket 的中心坐标（根据朝向）
     */
    private static int getCenterCoordinate(List<Socket> sockets, Direction facing) {
        int min = Integer.MAX_VALUE;
        int max = Integer.MIN_VALUE;

        for (Socket socket : sockets) {
            BlockPos pos = socket.centerBlockPos();
            int coord = getCoordinate(pos, facing);
            min = Math.min(min, coord);
            max = Math.max(max, coord);
        }

        return (min + max) / 2;
    }

    /**
     * 获取坐标（根据朝向）
     */
    private static int getCoordinate(BlockPos pos, Direction facing) {
        return switch (facing) {
            case NORTH, SOUTH -> pos.getX();
            case EAST, WEST -> pos.getZ();
            default -> 0;
        };
    }
}
