package com.formacraft.common.component.socket.place;

import com.formacraft.common.component.anchor.ComponentAnchor;
import com.formacraft.common.component.placement.ComponentPlacementSpec;
import com.formacraft.common.component.placement.FacingPolicy;
import com.formacraft.common.component.socket.Socket;

import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

/**
 * SocketAnchorResolver（Socket 锚点解析器）：H3 的"心脏"。
 * <p>
 * 核心功能：
 * - 给定一个 Socket + ComponentPlacementSpec + Component 本地锚点定义
 * - 计算出：Patch Origin（世界坐标）、Rotation（朝向）、Mirror（是否镜像）、Local → World 的 Transform
 * <p>
 * 这一步解决了哪些"之前很难的问题"：
 * - ✅ 门窗自动对齐墙（不再需要硬编码方向，内外由 FacingPolicy 控制）
 * - ✅ 栏杆/女儿墙沿边缘走（socket.center + normal 决定朝向，Patch 直接连续生成）
 * - ✅ 装饰构件智能贴墙（不需要玩家指定"北/南/东/西"，socket.normal 就是答案）
 * - ✅ 后续支持这些都很自然（对称、Path 连续布置、Outline 裁剪）
 */
public final class SocketAnchorResolver {
    private SocketAnchorResolver() {}

    /**
     * 解析 Socket 到 Component 实例变换
     * 
     * @param socket Socket
     * @param anchor 构件锚点定义
     * @param spec 构件放置规格
     * @return ComponentInstanceTransform
     */
    public static ComponentInstanceTransform resolve(
            Socket socket,
            ComponentAnchor anchor,
            ComponentPlacementSpec spec
    ) {
        if (socket == null || anchor == null || spec == null) {
            return new ComponentInstanceTransform(
                    BlockPos.ORIGIN,
                    Direction.SOUTH,
                    false,
                    false
            );
        }

        // 1️⃣ Socket 世界中心
        Vec3d socketCenter = socket.center();

        // 2️⃣ 计算旋转（把 component.anchor.facing 对齐 socket.normal）
        Direction targetFacing = resolveFacing(socket, anchor, spec);

        // 3️⃣ 计算本地锚点旋转后的偏移
        BlockPos localOffset = rotateLocalOffset(anchor, targetFacing);

        // 4️⃣ Patch 原点 = socketCenter - localOffset
        BlockPos origin = new BlockPos(
                (int) Math.floor(socketCenter.x) - localOffset.getX(),
                (int) Math.floor(socketCenter.y) - localOffset.getY(),
                (int) Math.floor(socketCenter.z) - localOffset.getZ()
        );

        // 5️⃣ 镜像（v1：默认不开，交给 SymmetryTool）
        boolean mirrorX = false;
        boolean mirrorZ = false;

        return new ComponentInstanceTransform(
                origin,
                targetFacing,
                mirrorX,
                mirrorZ
        );
    }

    // --------------------------------------------------------

    /**
     * 解析朝向
     */
    private static Direction resolveFacing(Socket socket,
                                           ComponentAnchor anchor,
                                           ComponentPlacementSpec spec) {
        if (socket.normal == null) {
            return anchor.facing; // fallback
        }

        FacingPolicy policy = spec.facingPolicy;
        if (policy == null) {
            policy = FacingPolicy.NONE;
        }

        return switch (policy) {
            case NONE -> anchor.facing; // 使用构件默认朝向
            case DERIVED_FROM_HOST, OUTWARD_NORMAL -> socket.normal; // 朝向外法线
            case ALONG_EDGE -> {
                // 沿边缘：使用 socket.tangent（如果有），否则使用 normal
                if (socket.tangent != null && socket.tangent.getAxis().isHorizontal()) {
                    yield socket.tangent;
                }
                yield socket.normal;
            }
            case USER_DEFINED -> anchor.facing; // 用户定义，使用构件默认朝向
        };
    }

    /**
     * 把 component 的本地 anchor 坐标，旋转到世界朝向
     */
    private static BlockPos rotateLocalOffset(ComponentAnchor anchor,
                                              Direction targetFacing) {
        int x = anchor.localX;
        int y = anchor.localY;
        int z = anchor.localZ;

        // 计算从 anchor.facing 到 targetFacing 的旋转
        Direction fromFacing = anchor.facing;
        if (fromFacing == null || !fromFacing.getAxis().isHorizontal()) {
            fromFacing = Direction.SOUTH;
        }

        if (targetFacing == null || !targetFacing.getAxis().isHorizontal()) {
            targetFacing = Direction.SOUTH;
        }

        // 计算旋转步数（顺时针）
        int steps = rotationSteps(fromFacing, targetFacing);

        // 应用旋转
        for (int i = 0; i < steps; i++) {
            // 90° clockwise: (x,z) -> (-z, x)
            int nx = -z;
            int nz = x;
            x = nx;
            z = nz;
        }

        return new BlockPos(x, y, z);
    }

    /**
     * 返回从 from 旋转到 to 的顺时针步数（0..3）。
     * 顺时针定义：N->E->S->W。
     */
    private static int rotationSteps(Direction from, Direction to) {
        if (from == null || to == null || from == to) return 0;
        if (!from.getAxis().isHorizontal() || !to.getAxis().isHorizontal()) return 0;

        // 使用 Direction 数组顺序计算旋转步数
        Direction[] order = new Direction[]{Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST};
        int a = -1, b = -1;
        for (int i = 0; i < order.length; i++) {
            if (order[i] == from) a = i;
            if (order[i] == to) b = i;
        }
        if (a < 0 || b < 0) return 0;
        int d = b - a;
        if (d < 0) d += 4;
        return d;
    }
}
