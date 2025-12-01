package com.formacraft.common.builder;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import net.minecraft.block.Block;

public class MaterialRequirement {
    private final Map<Block, Integer> requirements = new HashMap<>();

    public void add(Block block, int count) {
        if (block == null || count <= 0) return;
        requirements.merge(block, count, Integer::sum);
    }

    public Map<Block, Integer> getRequirements() {
        return Collections.unmodifiableMap(requirements);
    }

    public boolean isEmpty() {
        return requirements.isEmpty();
    }
}
