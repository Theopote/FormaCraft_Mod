package com.formacraft.server.generator.router;

import com.formacraft.server.generator.StructureGenerator;

/**
 * @deprecated 使用 {@link StructureGeneratorRegistry#create(String)}。
 */
@Deprecated
public final class ArchetypeGeneratorFactory {
    private ArchetypeGeneratorFactory() {}

    /**
     * @see StructureGeneratorRegistry#create(String)
     */
    @Deprecated
    public static StructureGenerator fromGeneratorId(String generatorIdLower) {
        return StructureGeneratorRegistry.create(generatorIdLower);
    }
}
