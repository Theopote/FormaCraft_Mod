package com.formacraft.server.generator;

import com.formacraft.common.model.build.BuildingSpec;
import com.formacraft.server.assembly.AssemblySpec;
import com.formacraft.server.assembly.MetaAssemblyCompiler;
import com.formacraft.server.assembly.MetaAssemblyEngine;
import com.formacraft.server.build.GeneratedStructure;
import com.formacraft.server.build.PlannedBlock;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.util.List;
import java.util.Map;

/**
 * MetaAssemblyGenerator (v1):
 * Opt-in generator that executes extra.assembly via MetaAssemblyEngine.
 *
 * This is the "creator engine" entrypoint: topology + geometry + material semantics -> blocks.
 */
public class MetaAssemblyGenerator implements StructureGenerator {
    @Override
    public GeneratedStructure generate(BuildingSpec spec, BlockPos origin, ServerWorld world) {
        if (spec == null || world == null || origin == null) {
            return new GeneratedStructure(null, origin, "MetaAssembly (invalid spec)", List.of());
        }

        Map<String, Object> extra = spec.getExtra();
        Object assemblyObj = extra != null ? extra.get("assembly") : null;
        AssemblySpec as = AssemblySpec.fromExtra(assemblyObj);
        if (as == null || as.ops == null || as.ops.isEmpty()) {
            // allow higher-level graph/components form
            AssemblySpec compiled = MetaAssemblyCompiler.compile(assemblyObj);
            if (compiled != null) as = compiled;
        }
        if (as == null) {
            return new GeneratedStructure(null, origin, "MetaAssembly (missing extra.assembly)", List.of());
        }

        // palette preference: assembly.paletteId > extra.paletteId
        String paletteId = as.paletteId;
        if ((paletteId == null || paletteId.isBlank()) && extra != null && extra.get("paletteId") != null) {
            paletteId = String.valueOf(extra.get("paletteId")).trim();
        }

        Direction entrance = resolveEntranceFacing(spec);
        if (as.entranceFacing != null && !as.entranceFacing.isBlank()) {
            try {
                entrance = Direction.valueOf(as.entranceFacing.trim().toUpperCase(java.util.Locale.ROOT));
            } catch (Exception ignored) {}
        }

        MetaAssemblyEngine engine = new MetaAssemblyEngine();
        List<PlannedBlock> blocks = engine.execute(as, new MetaAssemblyEngine.Context(world, origin, entrance, paletteId));
        String desc = "MetaAssembly v1";
        return new GeneratedStructure(null, origin, desc, blocks);
    }

    private static Direction resolveEntranceFacing(BuildingSpec spec) {
        // Priority: extra.layout.entranceFacing > extra.facing > default SOUTH
        try {
            if (spec != null && spec.getExtra() != null) {
                Object layoutObj = spec.getExtra().get("layout");
                if (layoutObj instanceof Map<?, ?> m) {
                    Object ef = m.get("entranceFacing");
                    if (ef != null) {
                        String s = String.valueOf(ef).trim().toUpperCase(java.util.Locale.ROOT);
                        switch (s) {
                            case "N", "NORTH", "北", "朝北" -> { return Direction.NORTH; }
                            case "S", "SOUTH", "南", "朝南" -> { return Direction.SOUTH; }
                            case "E", "EAST", "东", "朝东" -> { return Direction.EAST; }
                            case "W", "WEST", "西", "朝西" -> { return Direction.WEST; }
                            default -> {}
                        }
                    }
                }
                Object v = spec.getExtra().get("facing");
                if (v != null) {
                    String s = String.valueOf(v).trim().toUpperCase(java.util.Locale.ROOT);
                    return switch (s) {
                        case "N", "NORTH", "北", "朝北" -> Direction.NORTH;
                        case "E", "EAST", "东", "朝东" -> Direction.EAST;
                        case "W", "WEST", "西", "朝西" -> Direction.WEST;
                        default -> Direction.SOUTH;
                    };
                }
            }
        } catch (Throwable ignored) {}
        return Direction.SOUTH;
    }
}


