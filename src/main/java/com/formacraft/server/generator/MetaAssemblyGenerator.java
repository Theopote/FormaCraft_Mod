package com.formacraft.server.generator;

import com.formacraft.common.model.build.BuildingSpec;
import com.formacraft.server.assembly.AssemblySpec;
import com.formacraft.server.assembly.MetaAssemblyCompiler;
import com.formacraft.server.assembly.MetaAssemblyEngine;
import com.formacraft.server.assembly.validation.AssemblySpecValidator;
import com.formacraft.server.assembly.validation.AssemblyValidationIssue;
import com.formacraft.server.assembly.validation.AssemblySpecNormalizer;
import com.formacraft.server.assembly.validation.AssemblySpecNormalizeResult;
import com.formacraft.server.assembly.macro.AssemblyMacroApplier;
import com.formacraft.server.assembly.macro.AssemblyMacroApplyResult;
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
 * <p>
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

        // Auto-fix / normalize (never fails; emits WARNING issues for training/debug).
        AssemblySpecNormalizeResult norm = AssemblySpecNormalizer.normalize(assemblyObj);
        Object normalized = norm != null ? norm.normalized() : assemblyObj;

        // Macro layer (high-level gene sliders -> low-level knobs). Explicit low-level params always win.
        AssemblyMacroApplyResult macro = AssemblyMacroApplier.apply(normalized);
        Object applied = macro != null ? macro.applied() : normalized;

        // Validate early for stable LLM output & better error messages.
        List<AssemblyValidationIssue> issues = AssemblySpecValidator.validate(applied);
        long errCount = issues.stream().filter(i -> i.severity() == AssemblyValidationIssue.Severity.ERROR).count();
        if (errCount > 0) {
            StringBuilder sb = new StringBuilder("MetaAssembly (validation failed): ");
            int shown = 0;
            for (AssemblyValidationIssue is : issues) {
                if (is.severity() != AssemblyValidationIssue.Severity.ERROR) continue;
                if (shown++ >= 6) break;
                if (shown > 1) sb.append(" | ");
                sb.append(is.path()).append("[").append(is.code()).append("]").append(": ").append(is.message());
            }
            if (errCount > 6) sb.append(" | ... (").append(errCount).append(" errors)");
            return new GeneratedStructure(null, origin, sb.toString(), List.of());
        }

        AssemblySpec as = AssemblySpec.fromExtra(applied);
        if (as == null || as.ops.isEmpty()) {
            // allow higher-level graph/components form
            AssemblySpec compiled = MetaAssemblyCompiler.compile(applied);
            if (compiled != null) as = compiled;
        }
        if (as == null) {
            return new GeneratedStructure(null, origin, "MetaAssembly (missing extra.assembly)", List.of());
        }

        // palette preference: assembly.paletteId > extra.paletteId
        String paletteId = as.paletteId;
        if ((paletteId == null || paletteId.isBlank()) && extra.get("paletteId") != null) {
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


