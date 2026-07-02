package com.formacraft.common.generation.structure.router;

import com.formacraft.common.generation.structure.StructureGenerator;

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
