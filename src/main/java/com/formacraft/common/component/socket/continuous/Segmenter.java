package com.formacraft.common.component.socket.continuous;

import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.List;

/**
 * Segmenter（段切分器）：将连续路径切分为多个段。
 */
public final class Segmenter {
    private Segmenter() {}

    /**
     * 将采样点列表切分为段
     * 
     * @param points 采样点列表
     * @param segmentLength 每段的长度（方块单位）
     * @return 段列表
     */
    public static List<Segment> split(List<BlockPos> points, int segmentLength) {
        List<Segment> segments = new ArrayList<>();
        if (points == null || points.size() < 2 || segmentLength <= 0) {
            return segments;
        }

        int startIndex = 0;
        double accumulatedLength = 0.0;

        for (int i = 1; i < points.size(); i++) {
            BlockPos prev = points.get(i - 1);
            BlockPos cur = points.get(i);

            // 计算两点间距离
            double segmentDistance = Math.sqrt(
                    Math.pow(cur.getX() - prev.getX(), 2) +
                    Math.pow(cur.getY() - prev.getY(), 2) +
                    Math.pow(cur.getZ() - prev.getZ(), 2)
            );

            accumulatedLength += segmentDistance;

            // 如果累计长度达到或超过 segmentLength，创建一个段
            if (accumulatedLength >= segmentLength) {
                int endIndex = i;
                List<BlockPos> segmentPoints = points.subList(startIndex, endIndex + 1);
                Segment segment = createSegment(segmentPoints, startIndex, endIndex);
                segments.add(segment);

                // 重置：下一段从当前点开始
                startIndex = i;
                accumulatedLength = 0.0;
            }
        }

        // 处理剩余部分（如果不足一段，根据策略决定是否创建）
        if (startIndex < points.size() - 1) {
            int endIndex = points.size();
            List<BlockPos> segmentPoints = points.subList(startIndex, endIndex);
            if (segmentPoints.size() >= 2) {
                Segment segment = createSegment(segmentPoints, startIndex, endIndex);
                segments.add(segment);
            }
        }

        return segments;
    }

    /**
     * 创建段对象
     */
    private static Segment createSegment(List<BlockPos> points, int startIndex, int endIndex) {
        if (points.isEmpty()) {
            return new Segment(startIndex, endIndex, List.of(), null, new Vec3d(0, 0, 1), 0.0);
        }

        // 计算中心点
        int mid = points.size() / 2;
        BlockPos center = points.get(mid);

        // 计算方向（从第一个点到最后一个点的方向）
        BlockPos first = points.get(0);
        BlockPos last = points.get(points.size() - 1);
        Vec3d direction = new Vec3d(
                last.getX() - first.getX(),
                last.getY() - first.getY(),
                last.getZ() - first.getZ()
        );
        double len = direction.length();
        if (len > 1e-6) {
            direction = direction.multiply(1.0 / len);
        } else {
            direction = new Vec3d(0, 0, 1);
        }

        // 计算长度
        double totalLength = 0.0;
        for (int i = 1; i < points.size(); i++) {
            BlockPos prev = points.get(i - 1);
            BlockPos cur = points.get(i);
            totalLength += Math.sqrt(
                    Math.pow(cur.getX() - prev.getX(), 2) +
                    Math.pow(cur.getY() - prev.getY(), 2) +
                    Math.pow(cur.getZ() - prev.getZ(), 2)
            );
        }

        return new Segment(startIndex, endIndex, new ArrayList<>(points), center, direction, totalLength);
    }
}
