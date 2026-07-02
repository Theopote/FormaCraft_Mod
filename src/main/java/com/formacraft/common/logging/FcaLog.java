package com.formacraft.common.logging;

import com.formacraft.FormacraftMod;
import net.minecraft.server.network.ServerPlayerEntity;

/**
 * 统一模块日志：在消息前附加 {@code [module]} 及可选的 requestId / playerId / componentId。
 * <p>
 * 用法：{@code FcaLog.of("ComponentStorage").componentId(id).warn("load failed path={}", path, t);}
 */
public final class FcaLog {
    private final String module;
    private String requestId;
    private String playerId;
    private String componentId;

    private FcaLog(String module) {
        this.module = module;
    }

    public static FcaLog of(String module) {
        return new FcaLog(module);
    }

    public FcaLog requestId(String requestId) {
        this.requestId = requestId;
        return this;
    }

    public FcaLog playerId(String playerId) {
        this.playerId = playerId;
        return this;
    }

    public FcaLog player(ServerPlayerEntity player) {
        if (player != null) {
            this.playerId = player.getName().getString();
        }
        return this;
    }

    public FcaLog componentId(String componentId) {
        this.componentId = componentId;
        return this;
    }

    public void debug(String message, Object... args) {
        FormacraftMod.LOGGER.debug(withPrefix(message), args);
    }

    public void warn(String message, Object... args) {
        FormacraftMod.LOGGER.warn(withPrefix(message), args);
    }

    public void warn(String message, Throwable t, Object... args) {
        FormacraftMod.LOGGER.warn(withPrefix(message), withThrowable(args, t));
    }

    public void error(String message, Throwable t, Object... args) {
        FormacraftMod.LOGGER.error(withPrefix(message), withThrowable(args, t));
    }

    private String withPrefix(String message) {
        return buildPrefix() + " " + message;
    }

    private String buildPrefix() {
        StringBuilder sb = new StringBuilder(64).append('[').append(module).append(']');
        if (requestId != null && !requestId.isBlank()) {
            sb.append(" requestId=").append(requestId);
        }
        if (playerId != null && !playerId.isBlank()) {
            sb.append(" playerId=").append(playerId);
        }
        if (componentId != null && !componentId.isBlank()) {
            sb.append(" componentId=").append(componentId);
        }
        return sb.toString();
    }

    private static Object[] withThrowable(Object[] args, Throwable t) {
        if (t == null) {
            return args;
        }
        if (args == null || args.length == 0) {
            return new Object[]{t};
        }
        Object[] out = new Object[args.length + 1];
        System.arraycopy(args, 0, out, 0, args.length);
        out[args.length] = t;
        return out;
    }
}
