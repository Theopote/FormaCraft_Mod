package com.formacraft.common.generation.component;

/**
 * @deprecated 使用 {@link ComponentGeneratorRegistry}。保留为兼容门面。
 */
@Deprecated
public final class GeneratorRegistry {

    private GeneratorRegistry() {}

    public static void register(String type, ComponentGenerator generator) {
        ComponentGeneratorRegistry.register(type, generator);
    }

    public static ComponentGenerator getGenerator(String type) {
        return ComponentGeneratorRegistry.getGenerator(type);
    }

    public static boolean hasGenerator(String type) {
        return ComponentGeneratorRegistry.hasGenerator(type);
    }
}
