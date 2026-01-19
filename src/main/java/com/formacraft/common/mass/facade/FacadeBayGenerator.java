package com.formacraft.common.mass.facade;

import com.formacraft.common.component.socket.Socket;
import com.formacraft.common.component.socket.SocketType;
import com.formacraft.common.mass.derived.FacadeRhythmProfile;
import com.formacraft.common.mass.derived.FloorLayer;
import com.formacraft.FormacraftMod;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;

import java.util.*;

/**
 * FacadeBayGenerator（立面柱距生成器）
 * <p>
 * 🎯 核心职责：
 * 从 Socket 和 Rhythm Profile 生成 FacadeBay（柱距）
 * <p>
 * 执行顺序（不可打乱）：
 * 1️⃣ 计算 FacadeBay（柱距）← 这里
 * 2️⃣ 在 Bay 内放 Window Socket
 * 3️⃣ 给部分 Window 加 WindowSurround
 * 4️⃣ 在 FloorLayer 边界加 HorizontalBand
 * 5️⃣ 最后（可选）加 Decoration
 * <p>
 * Bay 决定：
 * - 窗的对齐位置
 * - 窗是否成组
 * - 线脚的断点
 */
public final class FacadeBayGenerator {

    private FacadeBayGenerator() {}

    /**
     * 从 Socket 列表和 Rhythm Profile 生成 Bay
     * <p>
     * 算法（v1）：
     * 1. 收集所有 Window Socket 的 X/Z 位置
     * 2. 根据 Rhythm Profile 的 spacing 和 alignment 计算 Bay 边界
     * 3. 识别主次 Bay（基于宽度和窗的密度）
     *
     * @param sockets Socket 列表（某个朝向的）
     * @param facing 朝向
     * @param layers 楼层列表
     * @param rhythmProfile 节奏配置
     * @return 生成的 Bay 列表
     */
    public static List<FacadeBay> generateBays(
            List<Socket> sockets,
            Direction facing,
            List<FloorLayer> layers,
            FacadeRhythmProfile rhythmProfile
    ) {
        if (sockets == null || sockets.isEmpty() || layers == null || layers.isEmpty()) {
            return List.of();
        }

        // 筛选出 Window Socket
        List<Socket> windowSockets = sockets.stream()
                .filter(s -> s.type == SocketType.WALL_OPENING &&
                        s.context != null &&
                        "window".equals(s.context.semanticTag))
                .toList();

        if (windowSockets.isEmpty()) {
            return List.of();
        }

        // 计算总体 Y 范围
        int minY = layers.stream().mapToInt(l -> l.baseY).min().orElse(0);
        int maxY = layers.stream().mapToInt(l -> l.topY).max().orElse(0);

        // 根据朝向提取 X 或 Z 坐标
        List<Double> positions = extractPositions(windowSockets, facing);

        if (positions.isEmpty()) {
            return List.of();
        }

        // 排序
        positions.sort(Double::compareTo);

        // 根据 Rhythm Profile 计算 Bay
        List<FacadeBay> bays = computeBaysFromPositions(
                positions,
                facing,
                minY,
                maxY,
                rhythmProfile
        );

        FormacraftMod.LOGGER.debug(
                "FacadeBayGenerator: Generated {} bays for facing {}",
                bays.size(),
                facing
        );

        return bays;
    }

    /**
     * 从 Socket 提取位置（根据朝向）
     */
    private static List<Double> extractPositions(List<Socket> sockets, Direction facing) {
        List<Double> positions = new ArrayList<>();

        for (Socket socket : sockets) {
            Box bounds = socket.bounds;
            double pos;

            if (facing == Direction.NORTH || facing == Direction.SOUTH) {
                // 东西朝向的立面，提取 X 坐标
                pos = (bounds.minX + bounds.maxX) / 2.0;
            } else {
                // 南北朝向的立面，提取 Z 坐标
                pos = (bounds.minZ + bounds.maxZ) / 2.0;
            }

            positions.add(pos);
        }

        return positions;
    }

    /**
     * 从位置列表计算 Bay
     */
    private static List<FacadeBay> computeBaysFromPositions(
            List<Double> positions,
            Direction facing,
            int baseY,
            int topY,
            FacadeRhythmProfile rhythmProfile
    ) {
        if (positions.isEmpty()) {
            return List.of();
        }

        List<FacadeBay> bays = new ArrayList<>();

        // v1 简化算法：根据 spacing 和位置分组
        int spacing = rhythmProfile.spacing;

        // 识别 Bay 中心（通过位置聚类）
        List<Double> bayCenters = clusterPositions(positions, spacing);

        // 为每个 Bay 中心创建 Bay
        for (int i = 0; i < bayCenters.size(); i++) {
            double center = bayCenters.get(i);
            double minPos, maxPos;

            if (i == 0) {
                // 第一个 Bay：从中心向左延伸一半间距
                minPos = center - spacing / 2.0;
            } else {
                // 与前一个 Bay 的中间点
                minPos = (center + bayCenters.get(i - 1)) / 2.0;
            }

            if (i == bayCenters.size() - 1) {
                // 最后一个 Bay：从中心向右延伸一半间距
                maxPos = center + spacing / 2.0;
            } else {
                // 与下一个 Bay 的中间点
                maxPos = (center + bayCenters.get(i + 1)) / 2.0;
            }

            // 创建 Box（根据朝向）
            Box bounds;
            if (facing == Direction.NORTH || facing == Direction.SOUTH) {
                // 东西朝向：X 范围，Z 固定
                bounds = new Box(minPos, baseY, -0.5, maxPos, topY, 0.5);
            } else {
                // 南北朝向：Z 范围，X 固定
                bounds = new Box(-0.5, baseY, minPos, 0.5, topY, maxPos);
            }

            int width = (int) Math.round(maxPos - minPos);

            // 判断 Bay 角色（v1 简化：基于宽度）
            FacadeBay.BayRole role = width >= spacing * 1.5 ? FacadeBay.BayRole.PRIMARY : FacadeBay.BayRole.SECONDARY;

            bays.add(new FacadeBay(
                    "bay_" + i,
                    bounds,
                    width,
                    baseY,
                    topY,
                    role
            ));
        }

        return bays;
    }

    /**
     * 位置聚类（简单算法）
     * <p>
     * 将相近的位置聚成一类，每个类代表一个 Bay 的中心
     */
    private static List<Double> clusterPositions(List<Double> positions, int spacing) {
        if (positions.isEmpty()) {
            return List.of();
        }

        List<Double> clusters = new ArrayList<>();
        double currentCluster = positions.get(0);

        for (double pos : positions) {
            if (Math.abs(pos - currentCluster) > spacing / 2.0) {
                // 开始新的聚类
                clusters.add(currentCluster);
                currentCluster = pos;
            } else {
                // 合并到当前聚类（取平均）
                currentCluster = (currentCluster + pos) / 2.0;
            }
        }

        // 添加最后一个聚类
        clusters.add(currentCluster);

        return clusters;
    }
}
