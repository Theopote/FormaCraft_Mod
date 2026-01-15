package com.formacraft.common.component.archetype;

import com.formacraft.FormacraftMod;

/**
 * DefaultArchetypes（默认原型）：初始化系统默认的构件原型。
 * <p>
 * 在系统启动时调用，注册基础构件原型。
 */
public final class DefaultArchetypes {
    private DefaultArchetypes() {}

    /**
     * 初始化默认原型
     */
    public static void initialize() {
        FormacraftMod.LOGGER.info("Initializing default ComponentArchetypes...");

        // 注册基础门
        ComponentArchetype door = ComponentArchetype.createBasicDoor();
        ComponentArchetypeStorage.register(door);

        // 注册基础窗
        ComponentArchetype window = ComponentArchetype.createBasicWindow();
        ComponentArchetypeStorage.register(window);

        // 注册基础栏杆
        ComponentArchetype railing = ComponentArchetype.createRailing();
        ComponentArchetypeStorage.register(railing);

        FormacraftMod.LOGGER.info("Initialized {} default ComponentArchetypes", 3);
    }
}
