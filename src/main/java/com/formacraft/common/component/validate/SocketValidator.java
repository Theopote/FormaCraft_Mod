package com.formacraft.common.component.validate;

import com.formacraft.common.component.socket.ComponentSocket;
import com.formacraft.common.component.socket.SocketRole;
import com.formacraft.common.component.socket.SocketShape;
import com.formacraft.common.component.socket.SocketContext;
import com.formacraft.common.component.socket.SocketFacingPolicy;

import java.util.List;

import static com.formacraft.common.component.validate.EnumUtil.isBlank;

/**
 * Socket 验证器（ComponentSocket）。
 */
public final class SocketValidator {
    private SocketValidator() {}

    public static void validate(List<ComponentSocket> sockets, ValidationResult out) {
        if (sockets == null) {
            // sockets 是可选的
            return;
        }

        if (sockets.size() > 32) {
            out.warn("sockets", "Too many sockets (>32). Consider consolidating.");
        }

        for (int i = 0; i < sockets.size(); i++) {
            ComponentSocket socket = sockets.get(i);
            String base = "sockets[" + i + "]";
            if (socket == null) {
                out.warn(base, "Null socket");
                continue;
            }

            // id
            if (isBlank(socket.id)) {
                out.error(base + ".id", "Missing socket id");
            } else {
                String id = socket.id.trim();
                if (!id.matches("^[a-z0-9_\\-\\.]+$")) {
                    out.error(base + ".id", "Invalid socket id format. Use lowercase, digits, underscore, hyphen, or dot. Got: " + id);
                }
            }

            // role（枚举，类型安全）
            if (socket.role == null) {
                out.error(base + ".role", "Missing role");
            }

            // shape（枚举）
            if (socket.shape == null) {
                out.error(base + ".shape", "Missing shape");
            }

            // context（枚举）
            if (socket.context == null) {
                out.warn(base + ".context", "Missing context");
            }

            // facingPolicy（枚举）
            if (socket.facingPolicy == null) {
                out.warn(base + ".facingPolicy", "Missing facingPolicy");
            }

            // size（SizeConstraint）
            if (socket.size != null) {
                if (socket.size.min == null || socket.size.min.length == 0) {
                    out.error(base + ".size.min", "SizeConstraint.min must be non-empty array");
                } else {
                    for (int j = 0; j < socket.size.min.length; j++) {
                        if (socket.size.min[j] < 0) {
                            out.error(base + ".size.min[" + j + "]", "SizeConstraint.min[" + j + "] must be >= 0");
                        }
                    }
                }
                if (socket.size.max == null || socket.size.max.length == 0) {
                    out.error(base + ".size.max", "SizeConstraint.max must be non-empty array");
                } else {
                    for (int j = 0; j < socket.size.max.length; j++) {
                        if (socket.size.max[j] < 0) {
                            out.error(base + ".size.max[" + j + "]", "SizeConstraint.max[" + j + "] must be >= 0");
                        }
                    }
                }
                // 检查 min <= max
                if (socket.size.min != null && socket.size.max != null &&
                    socket.size.min.length == socket.size.max.length) {
                    for (int j = 0; j < socket.size.min.length; j++) {
                        if (socket.size.min[j] > socket.size.max[j]) {
                            out.error(base + ".size", String.format(
                                "SizeConstraint.min[%d]=%d > max[%d]=%d", j, socket.size.min[j], j, socket.size.max[j]));
                        }
                    }
                }
            }

            // tags
            if (socket.tags != null) {
                if (socket.tags.size() > 16) {
                    out.warn(base + ".tags", "Too many tags (>16)");
                }
                for (String tag : socket.tags) {
                    if (isBlank(tag)) {
                        out.warn(base + ".tags", "Blank tag");
                    }
                }
            }

            // 合理性检查：根据 shape 检查 size
            if (socket.shape != null && socket.size != null) {
                switch (socket.shape) {
                    case RECT, LINE -> {
                        // RECT 和 LINE 通常需要 size
                        // 这里可以检查 SizeConstraint 是否有合理的 min/max
                    }
                    case POINT -> {
                        // POINT 通常不需要 size（或 size 很小）
                    }
                    case RING -> {
                        // RING 可能需要特殊的 size 检查
                    }
                }
            }
        }
    }
}
