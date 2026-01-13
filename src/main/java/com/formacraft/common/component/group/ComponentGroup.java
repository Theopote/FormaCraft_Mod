package com.formacraft.common.component.group;

import com.formacraft.common.component.socket.ComponentSocket;

import java.util.List;

/**
 * ComponentGroup：复合构件（布局图 + 子构件引用 + 对外 sockets）。
 *
 * - components：内部子构件布局（相对 group 原点）
 * - sockets：对外暴露的 socket（相对 group 原点）
 */
public class ComponentGroup {
    private final String id;
    private final String displayName;
    private final List<GroupComponentEntry> components;
    private final List<ComponentSocket> sockets;

    public ComponentGroup(String id,
                          String displayName,
                          List<GroupComponentEntry> components,
                          List<ComponentSocket> sockets) {
        this.id = id;
        this.displayName = displayName;
        this.components = components;
        this.sockets = sockets;
    }

    public String getId() {
        return id;
    }

    public String getDisplayName() {
        return displayName;
    }

    public List<GroupComponentEntry> getComponents() {
        return components;
    }

    public List<ComponentSocket> getSockets() {
        return sockets;
    }
}

