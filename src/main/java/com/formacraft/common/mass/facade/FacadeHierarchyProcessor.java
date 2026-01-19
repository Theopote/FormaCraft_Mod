package com.formacraft.common.mass.facade;

import com.formacraft.common.component.socket.Socket;
import com.formacraft.common.component.socket.SocketType;
import com.formacraft.common.mass.derived.FacadeRhythmProfile;
import com.formacraft.common.mass.derived.FloorLayer;
import com.formacraft.FormacraftMod;
import net.minecraft.util.math.Direction;

import java.util.*;

/**
 * FacadeHierarchyProcessor（立面层次处理器）
 * <p>
 * 🎯 核心职责：
 * 协调立面构件分级系统的完整流程
 * <p>
 * 执行顺序（不可打乱）：
 * 1️⃣ 计算 FacadeBay（柱距）← FacadeBayGenerator
 * 2️⃣ 在 Bay 内放 Window Socket（已经完成）
 * 3️⃣ 给部分 Window 加 WindowSurround（这里）
 * 4️⃣ 在 FloorLayer 边界加 HorizontalBand（这里）
 * 5️⃣ 最后（可选）加 Decoration（未来）
 * <p>
 * 为什么顺序这么重要？
 * 因为：
 * - Bay 是骨架
 * - Window 是内容
 * - Surround / Band 是强调
 * - Decoration 是气氛
 */
public final class FacadeHierarchyProcessor {

    private FacadeHierarchyProcessor() {}

    /**
     * 立面层次处理结果
     */
    public record FacadeHierarchyResult(
            /**
             * 生成的 Bay 列表
             */
            List<FacadeBay> bays,

            /**
             * 窗套规则映射（Window Socket ID -> WindowSurroundRule）
             */
            Map<String, WindowSurroundRule> windowSurrounds,

            /**
             * 水平线脚列表
             */
            List<HorizontalBandRule> horizontalBands
    ) {
        public static FacadeHierarchyResult empty() {
            return new FacadeHierarchyResult(List.of(), Map.of(), List.of());
        }
    }

    /**
     * 处理立面层次
     *
     * @param sockets Socket 列表（某个朝向的，已经经过 Rhythm 处理）
     * @param facing 朝向
     * @param layers 楼层列表
     * @param rhythmProfile 节奏配置
     * @param detailLevel 细节级别
     * @return 立面层次处理结果
     */
    public static FacadeHierarchyResult processHierarchy(
            List<Socket> sockets,
            Direction facing,
            List<FloorLayer> layers,
            FacadeRhythmProfile rhythmProfile,
            FacadeDetailLevel detailLevel
    ) {
        if (sockets == null || sockets.isEmpty()) {
            return FacadeHierarchyResult.empty();
        }

        try {
            // Step 1: 生成 Bay（结构级构件）
            List<FacadeBay> bays = FacadeBayGenerator.generateBays(
                    sockets,
                    facing,
                    layers,
                    rhythmProfile
            );

            // Step 2: 派生窗套规则（强调级构件）
            Map<String, WindowSurroundRule> windowSurrounds = deriveWindowSurrounds(
                    sockets,
                    bays,
                    layers,
                    detailLevel
            );

            // Step 3: 派生水平线脚（强调级构件）
            List<HorizontalBandRule> horizontalBands = deriveHorizontalBands(
                    sockets,
                    layers,
                    facing,
                    detailLevel
            );

            FormacraftMod.LOGGER.debug(
                    "FacadeHierarchyProcessor: Generated {} bays, {} window surrounds, {} horizontal bands",
                    bays.size(),
                    windowSurrounds.size(),
                    horizontalBands.size()
            );

            return new FacadeHierarchyResult(bays, windowSurrounds, horizontalBands);

        } catch (Exception e) {
            FormacraftMod.LOGGER.error("FacadeHierarchyProcessor: processing failed", e);
            return FacadeHierarchyResult.empty();
        }
    }

    /**
     * 派生窗套规则
     * <p>
     * 规则（v1）：
     * if (windowSocket.layer.role >= STANDARD
     *     && bay.role == PRIMARY
     *     && detailLevel >= MEDIUM) {
     *     allow window surround;
     * }
     */
    private static Map<String, WindowSurroundRule> deriveWindowSurrounds(
            List<Socket> sockets,
            List<FacadeBay> bays,
            List<FloorLayer> layers,
            FacadeDetailLevel detailLevel
    ) {
        Map<String, WindowSurroundRule> surrounds = new HashMap<>();

        if (detailLevel == FacadeDetailLevel.LOW) {
            return surrounds; // 低细节级别：不加窗套
        }

        // 筛选 Window Socket
        List<Socket> windowSockets = sockets.stream()
                .filter(s -> s.type == SocketType.WALL_OPENING &&
                        s.context != null &&
                        "window".equals(s.context.semanticTag))
                .toList();

        for (Socket windowSocket : windowSockets) {
            // 检查是否在 PRIMARY Bay 中
            FacadeBay containingBay = findContainingBay(windowSocket, bays);
            if (containingBay == null || containingBay.role() != FacadeBay.BayRole.PRIMARY) {
                continue;
            }

            // 检查楼层角色
            FloorLayer layer = findContainingLayer(windowSocket, layers);
            if (layer == null || layer.role == FloorLayer.FloorRole.GROUND) {
                continue; // 地面层通常不加窗套（v1 简化）
            }

            // 创建窗套规则（v1 简化：使用完整窗套）
            WindowSurroundRule rule = detailLevel == FacadeDetailLevel.HIGH
                    ? WindowSurroundRule.full()
                    : WindowSurroundRule.topOnly(); // MEDIUM 级别只用顶部

            surrounds.put(getSocketId(windowSocket), rule);
        }

        return surrounds;
    }

    /**
     * 派生水平线脚
     * <p>
     * 规则：
     * if (layer.role == STANDARD && detailLevel >= MEDIUM) {
     *     create FLOOR_DIVIDER band;
     * }
     */
    private static List<HorizontalBandRule> deriveHorizontalBands(
            List<Socket> sockets,
            List<FloorLayer> layers,
            Direction facing,
            FacadeDetailLevel detailLevel
    ) {
        List<HorizontalBandRule> bands = new ArrayList<>();

        if (detailLevel == FacadeDetailLevel.LOW) {
            return bands; // 低细节级别：不加线脚
        }

        // 计算立面宽度（用于设置线脚宽度）
        int facadeWidth = calculateFacadeWidth(sockets, facing);

        for (FloorLayer layer : layers) {
            // 只在标准层添加楼层分隔线
            if (layer.role == FloorLayer.FloorRole.STANDARD) {
                // 在楼层顶部添加分隔线
                bands.add(new HorizontalBandRule(
                        FacadeComponentLevel.ARTICULATION,
                        layer.topY,
                        HorizontalBandRule.BandRole.FLOOR_DIVIDER,
                        facadeWidth
                ));
            }

            // 如果是顶层，添加檐口线
            if (layer.role == FloorLayer.FloorRole.TOP) {
                bands.add(new HorizontalBandRule(
                        FacadeComponentLevel.ARTICULATION,
                        layer.topY,
                        HorizontalBandRule.BandRole.CROWN,
                        facadeWidth
                ));
            }
        }

        return bands;
    }

    /**
     * 查找包含 Socket 的 Bay
     */
    private static FacadeBay findContainingBay(Socket socket, List<FacadeBay> bays) {
        double centerX = (socket.bounds.minX + socket.bounds.maxX) / 2.0;
        double centerZ = (socket.bounds.minZ + socket.bounds.maxZ) / 2.0;

        for (FacadeBay bay : bays) {
            if (bay.containsX(centerX) && bay.containsZ(centerZ)) {
                return bay;
            }
        }
        return null;
    }

    /**
     * 查找包含 Socket 的 FloorLayer
     */
    private static FloorLayer findContainingLayer(Socket socket, List<FloorLayer> layers) {
        int y = (int) socket.bounds.minY;
        for (FloorLayer layer : layers) {
            if (layer.contains(y)) {
                return layer;
            }
        }
        return null;
    }

    /**
     * 获取 Socket ID（用于映射）
     */
    private static String getSocketId(Socket socket) {
        // v1 简化：使用 bounds 的字符串表示作为 ID
        return String.format("%.1f_%.1f_%.1f", socket.bounds.minX, socket.bounds.minY, socket.bounds.minZ);
    }

    /**
     * 计算立面宽度
     */
    private static int calculateFacadeWidth(List<Socket> sockets, Direction facing) {
        if (sockets.isEmpty()) {
            return 0;
        }

        double minPos, maxPos;

        if (facing == Direction.NORTH || facing == Direction.SOUTH) {
            // 东西朝向：计算 X 范围
            minPos = sockets.stream().mapToDouble(s -> s.bounds.minX).min().orElse(0);
            maxPos = sockets.stream().mapToDouble(s -> s.bounds.maxX).max().orElse(0);
        } else {
            // 南北朝向：计算 Z 范围
            minPos = sockets.stream().mapToDouble(s -> s.bounds.minZ).min().orElse(0);
            maxPos = sockets.stream().mapToDouble(s -> s.bounds.maxZ).max().orElse(0);
        }

        return (int) Math.round(maxPos - minPos);
    }
}
