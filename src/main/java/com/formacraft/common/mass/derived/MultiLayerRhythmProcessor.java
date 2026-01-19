package com.formacraft.common.mass.derived;

import com.formacraft.common.component.socket.Socket;
import com.formacraft.common.llm.dto.PlanSkeleton;
import net.minecraft.util.math.Direction;

import java.util.*;
import java.util.stream.Collectors;

/**
 * MultiLayerRhythmProcessor（多层节奏处理器）
 * <p>
 * 🎯 核心职责：
 * 处理多层之间的节奏"垂直关联"
 * <p>
 * 多层之间的节奏规则：
 * 1. 垂直对齐（v1 默认）
 * 2. 顶层变化
 * 3. Ground 层破节奏（非常重要）
 */
public final class MultiLayerRhythmProcessor {

    private MultiLayerRhythmProcessor() {}

    /**
     * 处理多层立面节奏
     * <p>
     * 为每个楼层应用节奏，并处理层间关联
     *
     * @param layeredSockets 按层组织的 Socket（FloorLayer -> List<Socket>）
     * @param facing 朝向
     * @param profile 节奏配置
     * @param domain Plan Domain
     * @return 处理后的按层组织的 Socket
     */
    public static Map<FloorLayer, List<Socket>> processMultiLayerRhythm(
            Map<FloorLayer, List<Socket>> layeredSockets,
            Direction facing,
            FacadeRhythmProfile profile,
            PlanSkeleton domain
    ) {
        if (layeredSockets == null || layeredSockets.isEmpty()) {
            return Map.of();
        }

        Map<FloorLayer, List<Socket>> result = new HashMap<>();

        // 按楼层索引排序
        List<FloorLayer> sortedLayers = layeredSockets.keySet().stream()
                .sorted(Comparator.comparingInt(l -> l.index))
                .toList();

        List<Socket> previousLayerSockets = null;

        for (FloorLayer layer : sortedLayers) {
            List<Socket> layerSockets = new ArrayList<>(layeredSockets.get(layer));

            // 应用单层节奏
            List<Socket> processedSockets = FacadeRhythmProcessor.processRhythm(
                    layerSockets, layer, facing, profile, domain
            );

            // 处理层间关联
            if (layer.role == FloorLayer.FloorRole.GROUND) {
                // Ground 层破节奏：不强制对齐上层，允许更自由
                // v1 简化：不额外处理，保持节奏结果
            } else if (layer.role == FloorLayer.FloorRole.TOP) {
                // 顶层变化：减少窗口数量
                processedSockets = reduceTopLayerSockets(processedSockets);
            } else if (previousLayerSockets != null) {
                // 垂直对齐：继承上一层的 X 位置
                processedSockets = alignVertically(processedSockets, previousLayerSockets, facing);
            }

            result.put(layer, processedSockets);
            previousLayerSockets = processedSockets;
        }

        return result;
    }

    /**
     * 垂直对齐
     * <p>
     * windows on layer N → inherit x positions from layer N-1
     */
    private static List<Socket> alignVertically(
            List<Socket> currentLayerSockets,
            List<Socket> previousLayerSockets,
            Direction facing
    ) {
        if (previousLayerSockets.isEmpty() || currentLayerSockets.isEmpty()) {
            return currentLayerSockets;
        }

        // 提取上一层的 X 坐标（根据朝向）
        Set<Integer> previousCoords = previousLayerSockets.stream()
                .map(s -> getCoordinate(s.centerBlockPos(), facing))
                .collect(Collectors.toSet());

        // 保留与上一层对齐的 Socket（±1 block 容差）
        List<Socket> aligned = currentLayerSockets.stream()
                .filter(socket -> {
                    int coord = getCoordinate(socket.centerBlockPos(), facing);
                    return previousCoords.stream().anyMatch(prev -> Math.abs(coord - prev) <= 1);
                })
                .toList();

        // v1 简化：如果对齐后的 Socket 太少，保留部分原 Socket
        if (aligned.size() < currentLayerSockets.size() / 2) {
            return currentLayerSockets; // 如果对齐后损失太多，保持原样
        }

        return aligned;
    }

    /**
     * 顶层变化：减少窗口数量
     */
    private static List<Socket> reduceTopLayerSockets(List<Socket> sockets) {
        // v1 简化：减少 30% 的 Socket（保留 70%）
        int targetCount = (int) (sockets.size() * 0.7);
        if (targetCount >= sockets.size()) {
            return sockets;
        }

        // 随机选择要保留的 Socket
        List<Socket> result = new ArrayList<>(sockets);
        Collections.shuffle(result, new Random());
        return result.subList(0, targetCount);
    }

    /**
     * 获取坐标（根据朝向）
     */
    private static int getCoordinate(net.minecraft.util.math.BlockPos pos, Direction facing) {
        return switch (facing) {
            case NORTH, SOUTH -> pos.getX();
            case EAST, WEST -> pos.getZ();
            default -> 0;
        };
    }
}
