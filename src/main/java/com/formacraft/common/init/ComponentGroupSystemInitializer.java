package com.formacraft.common.init;

import com.formacraft.FormacraftMod;
import com.formacraft.common.component.group.ComponentGroup;
import com.formacraft.common.component.group.ComponentGroupRegistry;
import com.formacraft.common.component.group.GroupComponentEntry;
import com.formacraft.common.component.socket.ComponentSocket;
import com.formacraft.common.component.socket.*;
import com.formacraft.common.component.transform.Mirror;
import com.formacraft.common.skeleton.transform.YRotation;

import java.util.List;

/**
 * Component Group 系统初始化器：
 * - 注册内置示例 groups（用于 prompt 引导 + 直接可用的复合装配）
 *
 * 注意：
 * - 这些 group 引用的 componentId 需要你在玩家构件库中保存同名构件（id=组件名的 snake_case）。
 *   例如保存名为 "tower_shell" 的构件，会得到 id "tower_shell"（见 ComponentTool.makeId）。
 */
public final class ComponentGroupSystemInitializer {
    private ComponentGroupSystemInitializer() {}

    public static void initialize() {
        FormacraftMod.LOGGER.info("Initializing Component Group System...");

        // 示例 1：基础塔楼（外壳 + 楼板 + 楼梯 + 屋顶），对外暴露 door socket
        ComponentGroupRegistry.register(new ComponentGroup(
                "TOWER_BASIC",
                "Basic Tower",
                List.of(
                        new GroupComponentEntry("tower_shell", 0, 0, 0, YRotation.NONE, Mirror.NONE),
                        new GroupComponentEntry("tower_floor", 0, 4, 0, YRotation.NONE, Mirror.NONE),
                        new GroupComponentEntry("tower_stair", 1, 0, 1, YRotation.NONE, Mirror.NONE),
                        new GroupComponentEntry("tower_roof", 0, 12, 0, YRotation.NONE, Mirror.NONE)
                ),
                List.of(
                        ComponentSocket.builder("door")
                                .role(SocketRole.CONSUMER)
                                .shape(SocketShape.RECT)
                                .context(SocketContext.WALL)
                                .facingPolicy(SocketFacingPolicy.IN_OUT)
                                .size(SizeConstraint.rect(2, 3, 2, 3))
                                .tag("door")
                                .build()
                )
        ));

        // 示例 2：城门（gatehouse），对外暴露左右墙段接口（WALL sockets）
        ComponentGroupRegistry.register(new ComponentGroup(
                "MEDIEVAL_GATEHOUSE",
                "Medieval Gatehouse",
                List.of(
                        new GroupComponentEntry("gate_wall", 0, 0, 0, YRotation.NONE, Mirror.NONE),
                        new GroupComponentEntry("gate_door", 4, 1, 0, YRotation.NONE, Mirror.NONE),
                        new GroupComponentEntry("gate_tower_left", -3, 0, 0, YRotation.NONE, Mirror.NONE),
                        new GroupComponentEntry("gate_tower_right", 7, 0, 0, YRotation.NONE, Mirror.NONE)
                ),
                List.of(
                        ComponentSocket.builder("wall_left")
                                .role(SocketRole.PROVIDER)
                                .shape(SocketShape.LINE)
                                .context(SocketContext.WALL)
                                .facingPolicy(SocketFacingPolicy.AXIS)
                                .size(SizeConstraint.line(1, 10))
                                .tag("wall")
                                .build(),
                        ComponentSocket.builder("wall_right")
                                .role(SocketRole.PROVIDER)
                                .shape(SocketShape.LINE)
                                .context(SocketContext.WALL)
                                .facingPolicy(SocketFacingPolicy.AXIS)
                                .size(SizeConstraint.line(1, 10))
                                .tag("wall")
                                .build()
                )
        ));

        // 示例 3：城墙段（Wall Segment）——对外暴露前后两个接口，支持链式拼接
        // 需要你在构件库中保存名为 "wall_segment" 的构件（或拆成多件自行改 entry 列表）。
        ComponentGroupRegistry.register(new ComponentGroup(
                "WALL_SEGMENT",
                "Wall Segment",
                List.of(
                        new GroupComponentEntry("wall_segment", 0, 0, 0, YRotation.NONE, Mirror.NONE)
                ),
                List.of(
                        // prev/next socket：城墙段的链式连接接口
                        ComponentSocket.builder("prev")
                                .role(SocketRole.PROVIDER)
                                .shape(SocketShape.LINE)
                                .context(SocketContext.WALL)
                                .facingPolicy(SocketFacingPolicy.AXIS)
                                .size(SizeConstraint.line(1, 10))
                                .tag("wall_segment")
                                .build(),
                        ComponentSocket.builder("next")
                                .role(SocketRole.PROVIDER)
                                .shape(SocketShape.LINE)
                                .context(SocketContext.WALL)
                                .facingPolicy(SocketFacingPolicy.AXIS)
                                .size(SizeConstraint.line(1, 10))
                                .tag("wall_segment")
                                .build()
                )
        ));

        FormacraftMod.LOGGER.info("  ✓ Component groups registered: {}", ComponentGroupRegistry.list().size());
    }
}

