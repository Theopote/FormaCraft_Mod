package com.formacraft.common.component.socket;

/**
 * LegacySocketHelper（旧版 Socket 适配器）v1：兼容旧代码对 record 风格 API 的调用。
 * <p>
 * 职责：
 * - 将旧版 ComponentSocket（record，包含 x/y/z/width/height/depth）适配到新版 ComponentSocket（语义接口）
 * - 为旧代码提供过渡期兼容层
 * <p>
 * 注意：
 * - 新代码应直接使用 ComponentSocket（v1 完整版本）
 * - 旧代码可通过此 Helper 适配（逐步迁移）
 * - v2 移除此 Helper
 */
public final class LegacySocketHelper {
    private LegacySocketHelper() {}

    /**
     * 从旧版 socket 数据创建新版 ComponentSocket（过渡期适配）。
     * <p>
     * 策略：
     * - 假设旧版 socket 都是 CONSUMER（门/窗需要墙体洞口）
     * - 假设 context=WALL, shape=RECT
     * - 从 width/height 推断 size 约束
     */
    public static ComponentSocket fromLegacy(String id, int x, int y, int z, int width, int height, int depth) {
        return ComponentSocket.builder(id != null ? id : "legacy_socket")
                .role(SocketRole.CONSUMER)
                .shape(SocketShape.RECT)
                .context(SocketContext.WALL)
                .facingPolicy(SocketFacingPolicy.IN_OUT)
                .size(SizeConstraint.rect(width, height, width, height))
                .tag("opening")
                .build();
    }

    /**
     * 兼容旧代码的"取 id"方法（v1 ComponentSocket.id 是 public final，直接访问即可）。
     */
    public static String id(ComponentSocket socket) {
        return socket != null ? socket.id : null;
    }

    /**
     * 兼容旧代码的"取坐标"方法（新版 Socket 不包含坐标，返回 0）。
     * <p>
     * 注意：
     * - 新版 Socket 是"语义接口"，不包含具体坐标
     * - 坐标由 SocketPlacement 提供
     * - 旧代码应逐步迁移到 SocketPlacement
     */
    public static int x(ComponentSocket socket) {
        return 0; // 新版 Socket 不包含坐标
    }

    public static int y(ComponentSocket socket) {
        return 0;
    }

    public static int z(ComponentSocket socket) {
        return 0;
    }

    /**
     * 兼容旧代码的"取尺寸"方法（从 size 约束中提取）。
     */
    public static int width(ComponentSocket socket) {
        if (socket == null || socket.size == null || socket.size.min.length == 0) return 0;
        return socket.size.min[0]; // 取最小宽度
    }

    public static int height(ComponentSocket socket) {
        if (socket == null || socket.size == null || socket.size.min.length < 2) return 0;
        return socket.size.min[1]; // 取最小高度
    }

    public static int depth(ComponentSocket socket) {
        return 1; // 新版 Socket 不包含 depth（门/窗通常是 1 格厚）
    }
}
