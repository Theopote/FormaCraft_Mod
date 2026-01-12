package com.formacraft.common.component.socket;

import com.formacraft.common.component.transform.Mirror;

/**
 * ComponentMount：把一个构件安装到某个 socket 上的绑定信息。
 * <p>
 * 注意：当前线上协议仍以 component_request JSON 的 host_id/socket_id/mount_id 表达 mount；
 * 该 record 用于把语义结构化（便于 prompt/bridge/后续编译器接入）。
 */
public record ComponentMount(
        String componentId,
        String socketId,
        Mirror mirror
) {
    public static ComponentMount of(String componentId, String socketId) {
        return new ComponentMount(componentId, socketId, Mirror.NONE);
    }
}

