package com.formacraft.common.component.socket;

import java.util.HashSet;
import java.util.Set;

/**
 * ComponentSocket（组件接口/插槽）v1：组件间连接的唯一合法接口。
 * <p>
 * 核心理念：
 * - Socket 是"组件可以被放置/连接/生长的语义接口"
 * - 它是 Component × Component、Component × Skeleton、Component × AI 的唯一连接点
 * <p>
 * 职责边界：
 * - ✅ 能不能放（合法性）
 * - ✅ 放在哪里（定位）
 * - ✅ 怎么对齐（朝向/尺寸/约束）
 * - ❌ 不做：方块生成、AI 推理、UI 操作
 * <p>
 * 示例：
 * - 墙体（PROVIDER）：提供 "wall_opening" socket，允许插入门/窗
 * - 门（CONSUMER）：声明 "door_mount" socket，需要墙体洞口
 * - 匹配算法：role/context/shape/tags/size 全部兼容 → 可匹配
 */
public final class ComponentSocket {
    public final String id;                      // socket 唯一标识（例如 "door_opening"）
    public final SocketRole role;                 // PROVIDER / CONSUMER
    public final SocketShape shape;               // RECT / LINE / POINT / RING
    public final SocketContext context;           // WALL / EDGE / ROOF / GROUND
    public final SocketFacingPolicy facingPolicy; // NONE / IN_OUT / AXIS / FREE
    public final SizeConstraint size;             // min/max 尺寸约束
    public final Set<String> tags;                // 语义标签（例如 ["door","window"]）

    public ComponentSocket(
            String id,
            SocketRole role,
            SocketShape shape,
            SocketContext context,
            SocketFacingPolicy facingPolicy,
            SizeConstraint size,
            Set<String> tags
    ) {
        this.id = id != null ? id : "unknown";
        this.role = role != null ? role : SocketRole.CONSUMER;
        this.shape = shape != null ? shape : SocketShape.POINT;
        this.context = context != null ? context : SocketContext.WALL;
        this.facingPolicy = facingPolicy != null ? facingPolicy : SocketFacingPolicy.NONE;
        this.size = size != null ? size : SizeConstraint.none();
        this.tags = tags != null ? new HashSet<>(tags) : new HashSet<>();
    }

    /**
     * 快速检查是否包含指定标签（不区分大小写）。
     */
    public boolean hasTag(String tag) {
        if (tag == null || tag.isBlank()) return false;
        String t = tag.trim().toLowerCase();
        return tags.stream().anyMatch(s -> s != null && s.toLowerCase().equals(t));
    }

    /**
     * 快速检查是否包含所有指定标签。
     */
    public boolean hasAllTags(Set<String> requiredTags) {
        if (requiredTags == null || requiredTags.isEmpty()) return true;
        for (String tag : requiredTags) {
            if (!hasTag(tag)) return false;
        }
        return true;
    }

    @Override
    public String toString() {
        return String.format("Socket[id=%s, role=%s, shape=%s, context=%s, facing=%s, size=%s, tags=%s]",
                id, role, shape, context, facingPolicy, size, tags);
    }

    /**
     * Builder 模式（可选，方便构造）。
     */
    public static Builder builder(String id) {
        return new Builder(id);
    }

    public static class Builder {
        private final String id;
        private SocketRole role = SocketRole.CONSUMER;
        private SocketShape shape = SocketShape.POINT;
        private SocketContext context = SocketContext.WALL;
        private SocketFacingPolicy facingPolicy = SocketFacingPolicy.NONE;
        private SizeConstraint size = SizeConstraint.none();
        private final Set<String> tags = new HashSet<>();

        public Builder(String id) {
            this.id = id;
        }

        public Builder role(SocketRole role) {
            this.role = role;
            return this;
        }

        public Builder shape(SocketShape shape) {
            this.shape = shape;
            return this;
        }

        public Builder context(SocketContext context) {
            this.context = context;
            return this;
        }

        public Builder facingPolicy(SocketFacingPolicy facingPolicy) {
            this.facingPolicy = facingPolicy;
            return this;
        }

        public Builder size(SizeConstraint size) {
            this.size = size;
            return this;
        }

        public Builder tag(String tag) {
            if (tag != null && !tag.isBlank()) this.tags.add(tag.trim());
            return this;
        }

        public Builder tags(Set<String> tags) {
            if (tags != null) this.tags.addAll(tags);
            return this;
        }

        public ComponentSocket build() {
            return new ComponentSocket(id, role, shape, context, facingPolicy, size, tags);
        }
    }
}
