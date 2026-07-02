package com.formacraft.common.init;

import com.formacraft.FormacraftMod;
import com.formacraft.common.component.archetype.DefaultArchetypes;

/**
 * Component 语义系统初始化：默认原型注册等。
 */
public final class ComponentSystemInitializer {
    private ComponentSystemInitializer() {}

    public static void initialize() {
        FormacraftMod.LOGGER.info("Initializing Component System...");
        DefaultArchetypes.initialize();
        FormacraftMod.LOGGER.info("  ✓ Component archetypes initialized");
    }
}
