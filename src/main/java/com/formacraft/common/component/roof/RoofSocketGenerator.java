package com.formacraft.common.component.roof;

import com.formacraft.common.component.socket.Socket;
import com.formacraft.common.component.socket.SocketType;
import com.formacraft.common.geometry.Polygon2D;
import com.formacraft.common.geometry.Vec2;
import com.formacraft.common.llm.dto.StructuralSkeleton;
import com.formacraft.common.llm.dto.structural.RidgeLine;
import com.formacraft.common.llm.dto.structural.RoofSlope;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;

import java.util.ArrayList;
import java.util.List;

/**
 * RoofSocketGenerator（屋顶 Socket 生成器）
 * <p>
 * 核心职责：从 RoofPlate 生成屋顶相关的 Socket
 * <p>
 * 三大生态位：
 * - RIDGE（脊）→ RIDGE_LINE Socket
 * - EAVE（檐）→ EAVE_LINE Socket
 * - SLOPE_SURFACE（坡面）→ ROOF_SURFACE Socket
 */
public final class RoofSocketGenerator {

    private RoofSocketGenerator() {}

    /**
     * 从 RoofPlate 生成所有屋顶 Socket
     * <p>
     * 流程：
     * 1. 从 ridges 生成 RIDGE_LINE Socket
     * 2. 从 slopes + 外墙边界生成 EAVE_LINE Socket
     * 3. 从 slopes 生成 ROOF_SURFACE Socket
     *
     * @param roofPlate RoofPlate
     * @param structural StructuralSkeleton（用于获取外墙信息，用于生成 EAVE_LINE）
     * @return 生成的 Socket 列表
     */
    public static List<Socket> generateRoofSockets(
            StructuralSkeleton.RoofPlate roofPlate,
            StructuralSkeleton structural
    ) {
        List<Socket> sockets = new ArrayList<>();

        if (roofPlate == null) {
            return sockets;
        }

        // 1. 生成 RIDGE_LINE Socket（从 ridges）
        if (roofPlate.ridges != null && !roofPlate.ridges.isEmpty()) {
            List<Socket> ridgeSockets = generateRidgeSockets(roofPlate.ridges);
            sockets.addAll(ridgeSockets);
        }

        // 2. 生成 EAVE_LINE Socket（从 slopes + 外墙边界）
        if (roofPlate.slopes != null && !roofPlate.slopes.isEmpty()) {
            List<Socket> eaveSockets = generateEaveSockets(roofPlate.slopes, structural);
            sockets.addAll(eaveSockets);
        }

        // 3. 生成 ROOF_SURFACE Socket（从 slopes）
        if (roofPlate.slopes != null && !roofPlate.slopes.isEmpty()) {
            List<Socket> surfaceSockets = generateSurfaceSockets(roofPlate.slopes);
            sockets.addAll(surfaceSockets);
        }

        return sockets;
    }

    /**
     * 生成 RIDGE_LINE Socket
     * <p>
     * 语义来源：RoofPlate.ridges
     * <p>
     * Socket 定义：
     * - role = RIDGE_LINE
     * - context = ROOF
     * - shape = LINE
     * - direction = ALONG_LINE
     */
    private static List<Socket> generateRidgeSockets(List<RidgeLine> ridges) {
        List<Socket> sockets = new ArrayList<>();

        for (RidgeLine ridge : ridges) {
            if (ridge == null || ridge.lineXZ == null) {
                continue;
            }

            Vec2 start = ridge.lineXZ.start();
            Vec2 end = ridge.lineXZ.end();
            if (start == null || end == null) {
                continue;
            }

            // 计算脊线长度和方向
            double length = ridge.lineXZ.length();
            Vec2 midPoint = ridge.lineXZ.midpoint();

            // 创建 Socket bounds（从脊线创建一个细长的 Box）
            // v1 简化：创建一个沿脊线的 Box
            Box bounds = new Box(
                    start.x(), ridge.heightY, start.z(),
                    end.x(), ridge.heightY + 0.5, end.z()
            );

            // TODO: 创建 Socket 对象（需要确定 Direction normal 和 tangent）
            // Socket socket = new Socket(
            //     SocketType.RIDGE_LINE,
            //     bounds,
            //     Direction.UP, // normal（向上）
            //     null, // tangent（沿脊线方向，需要从 lineXZ 计算）
            //     new Socket.SemanticContext(false, "roof_ridge")
            // );
            // sockets.add(socket);
        }

        return sockets;
    }

    /**
     * 生成 EAVE_LINE Socket
     * <p>
     * 语义来源：RoofSlope ∩ 外墙边界
     * <p>
     * 也就是说：坡面 + 外墙的交线 = 檐口
     * <p>
     * Socket 定义：
     * - role = EAVE_LINE
     * - context = ROOF_EDGE
     * - shape = LINE
     * - direction = ALONG_EDGE
     */
    private static List<Socket> generateEaveSockets(
            List<RoofSlope> slopes,
            StructuralSkeleton structural
    ) {
        List<Socket> sockets = new ArrayList<>();

        if (structural == null || structural.walls == null) {
            return sockets;
        }

        // v1 简化：提取所有外墙边界
        // 未来：计算 slope.area 与外墙边界的交线
        for (RoofSlope slope : slopes) {
            if (slope == null || slope.area == null) {
                continue;
            }

            // 从 slope.area 的外边界生成 EAVE_LINE
            Polygon2D area = slope.area;
            List<Vec2> vertices = area.getVertices();

            // 将 polygon 的每条边转换为一个 EAVE_LINE Socket
            for (int i = 0; i < vertices.size(); i++) {
                Vec2 start = vertices.get(i);
                Vec2 end = vertices.get((i + 1) % vertices.size());

                // 检查这条边是否与外墙相邻（v1 简化：假设都是外墙边界）
                // 创建 Socket bounds（沿边的细长 Box）
                Box bounds = new Box(
                        start.x(), slope.normal.y() - 0.5, start.z(),
                        end.x(), slope.normal.y() + 0.5, end.z()
                );

                // TODO: 实际创建 Socket 对象
                // Socket socket = new Socket(
                //     SocketType.EAVE_LINE,
                //     bounds,
                //     Direction.DOWN, // normal（向下）
                //     null, // tangent（沿边方向）
                //     new Socket.SemanticContext(true, "roof_eave")
                // );
                // sockets.add(socket);
            }
        }

        return sockets;
    }

    /**
     * 生成 ROOF_SURFACE Socket
     * <p>
     * 语义来源：RoofSlope.area
     * <p>
     * Socket 定义：
     * - role = ROOF_SURFACE
     * - context = ROOF
     * - shape = SURFACE
     * - normal = slope.normal
     */
    private static List<Socket> generateSurfaceSockets(List<RoofSlope> slopes) {
        List<Socket> sockets = new ArrayList<>();

        for (RoofSlope slope : slopes) {
            if (slope == null || slope.area == null || slope.normal == null) {
                continue;
            }

            // 计算坡面中心点和边界框
            Polygon2D area = slope.area;
            Polygon2D.Bounds2D bounds2D = area.getBounds();

            // 估算高度（使用 slope.normal.y() 和 pitch）
            double estimatedY = slope.normal.y() + Math.sin(Math.toRadians(slope.pitch)) * 2.0;

            // 创建 Socket bounds（覆盖整个坡面）
            Box bounds = new Box(
                    bounds2D.min().x(), estimatedY, bounds2D.min().z(),
                    bounds2D.max().x(), estimatedY + 0.5, bounds2D.max().z()
            );

            // TODO: 实际创建 Socket 对象
            // 需要确定 normal Direction（从 slope.normal 转换为 Minecraft Direction）
            // Socket socket = new Socket(
            //     SocketType.ROOF_SURFACE,
            //     bounds,
            //     Direction.UP, // normal（根据 slope.normal 计算）
            //     null, // tangent（无）
            //     new Socket.SemanticContext(true, "roof_surface")
            // );
            // sockets.add(socket);
        }

        return sockets;
    }
}
