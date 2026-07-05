package com.formacraft.server.generation.component.impl;

import com.formacraft.FormacraftMod;
import com.formacraft.common.compiler.semantic.SemanticComponent;
import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.dto.CapabilityGap;
import com.formacraft.common.llm.dto.Dimensions;
import com.formacraft.common.llm.dto.Vec3i;
import com.formacraft.common.build.PlannedBlock;
import com.formacraft.common.model.build.BuildingSpec;
import com.formacraft.common.model.build.Footprint;
import com.formacraft.common.patch.BlockPatch;
import com.formacraft.server.assembly.AssemblyCompileDiagnostics;
import com.formacraft.server.assembly.AssemblySpec;
import com.formacraft.server.assembly.MetaAssemblyCompiler;
import com.formacraft.server.assembly.MetaAssemblyEngine;
import com.formacraft.server.assembly.macro.AssemblyMacroApplier;
import com.formacraft.server.assembly.macro.AssemblyMacroApplyResult;
import com.formacraft.server.assembly.validation.AssemblySpecNormalizeResult;
import com.formacraft.server.assembly.validation.AssemblySpecNormalizer;
import com.formacraft.server.assembly.preset.AssemblyPresetApplier;
import com.formacraft.server.assembly.validation.AssemblySpecValidator;
import com.formacraft.server.assembly.validation.AssemblyValidationRepairHints;
import com.formacraft.server.assembly.validation.AssemblyValidationIssue;
import net.minecraft.registry.Registries;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 将 LlmPlan {@code component_type=ASSEMBLY} 的 {@code params.assembly} 直接编译为 BlockPatch。
 * <p>
 * Patch 坐标相对 slot anchor；{@link com.formacraft.server.compiler.ComponentPlanCompiler} 再叠加 slot 偏移。
 * Failures set {@link AssemblyCompileDiagnostics} instead of silently returning empty patches.
 */
public final class AssemblyPatchGenerator {

    public record GenerateResult(List<BlockPatch> patches, CapabilityGap gap) {
        public static GenerateResult ok(List<BlockPatch> patches) {
            return new GenerateResult(patches == null ? List.of() : patches, null);
        }

        public static GenerateResult failed(CapabilityGap gap) {
            return new GenerateResult(List.of(), gap);
        }

        public boolean hasGap() {
            return gap != null;
        }
    }

    private AssemblyPatchGenerator() {}

    public static List<BlockPatch> tryGenerate(SemanticComponent semantic, ServerWorld world) {
        return generate(semantic, world).patches();
    }

    public static GenerateResult generate(SemanticComponent semantic, ServerWorld world) {
        if (semantic == null || world == null || semantic.source() == null) {
            return GenerateResult.ok(List.of());
        }
        Component component = semantic.source();
        if (!"ASSEMBLY".equals(normalizeType(component.componentType()))) {
            return GenerateResult.ok(List.of());
        }
        if (component.relativePosition() == null) {
            return fail("E_ASSEMBLY_MISSING_ANCHOR",
                    "ASSEMBLY component missing relative_position",
                    "components[].relative_position",
                    List.of("Add relative_position {x,y,z} for the ASSEMBLY slot anchor."));
        }

        Object assemblyObj = resolveAssemblyPayload(component.params());
        if (assemblyObj == null) {
            return fail("E_ASSEMBLY_MISSING",
                    "ASSEMBLY component missing params.assembly (or ops/graph/macro/preset)",
                    "components[].params.assembly",
                    List.of(
                            "Use params.assembly with preset, graph.components, or ops[].",
                            "Prefer preset: spiral_watchtower | suspension_bridge_simple | gothic_shell_box."
                    ));
        }

        AssemblySpecNormalizeResult norm = AssemblySpecNormalizer.normalize(assemblyObj);
        AssemblyPresetApplier.ApplyResult presetApplied = AssemblyPresetApplier.apply(norm.normalized());
        for (AssemblyValidationIssue issue : presetApplied.issues()) {
            if (issue.severity() == AssemblyValidationIssue.Severity.ERROR) {
                return failFromIssue(issue, List.of("Fix preset id or presetParams.", "See ai-assembly-schema.json presets."));
            }
        }
        AssemblyMacroApplyResult macroApplied = AssemblyMacroApplier.apply(presetApplied.applied());
        Object applied = macroApplied.applied();

        List<AssemblyValidationIssue> issues = AssemblySpecValidator.validate(applied);
        List<AssemblyValidationIssue> errors = issues.stream()
                .filter(i -> i.severity() == AssemblyValidationIssue.Severity.ERROR)
                .toList();
        if (!errors.isEmpty()) {
            FormacraftMod.LOGGER.warn("AssemblyPatchGenerator: validation failed ({} errors). Hints:\n{}",
                    errors.size(), AssemblyValidationRepairHints.formatForPrompt(issues));
            AssemblyValidationIssue first = errors.getFirst();
            List<String> suggestions = errors.stream()
                    .limit(4)
                    .map(i -> i.code() + ": " + i.message())
                    .collect(Collectors.toList());
            return failFromIssue(first, suggestions);
        }

        BuildingSpec footprintHint = buildFootprintHint(component);
        AssemblySpec spec = AssemblySpec.fromExtra(applied);
        if (spec == null || spec.ops == null || spec.ops.isEmpty()) {
            spec = MetaAssemblyCompiler.compile(applied, footprintHint);
        }
        if (spec == null || spec.ops == null || spec.ops.isEmpty()) {
            return fail("E_ASSEMBLY_COMPILE_EMPTY",
                    "Could not compile assembly graph/ops to executable MetaAssembly ops",
                    "components[].params.assembly",
                    List.of(
                            "Ensure graph.components[] types match exported schema.",
                            "Or use a known preset shorthand.",
                            "Return plan_status=capability_gap if geometry is unsupported."
                    ));
        }

        Vec3i rp = component.relativePosition();
        BlockPos origin = new BlockPos(rp.x(), rp.y(), rp.z());
        Direction entrance = resolveEntranceFacing(semantic, component.params(), spec.entranceFacing);
        String paletteId = resolvePaletteId(semantic, component.params(), spec.paletteId);

        MetaAssemblyEngine engine = new MetaAssemblyEngine();
        List<PlannedBlock> blocks = engine.execute(
                spec,
                new MetaAssemblyEngine.Context(world, origin, entrance, paletteId)
        );
        if (blocks.isEmpty()) {
            return fail("E_ASSEMBLY_EMPTY_OUTPUT",
                    "MetaAssemblyEngine produced zero blocks for ASSEMBLY component",
                    "components[].params.assembly",
                    List.of(
                            "Check dimensions and params (w/d/h, points, span).",
                            "Do not rely on silent fallback to HOUSE/MASS."
                    ));
        }

        List<BlockPatch> out = new ArrayList<>(blocks.size());
        for (PlannedBlock pb : blocks) {
            if (pb == null || pb.getPos() == null || pb.getTargetState() == null) {
                continue;
            }
            BlockPos pos = pb.getPos();
            String blockId = Registries.BLOCK.getId(pb.getTargetState().getBlock()).toString();
            String action = pb.getTargetState().isAir() ? BlockPatch.REMOVE : BlockPatch.PLACE;
            out.add(new BlockPatch(action, pos.getX(), pos.getY(), pos.getZ(), blockId));
        }

        FormacraftMod.LOGGER.info("AssemblyPatchGenerator: generated {} patches for ASSEMBLY component", out.size());
        return GenerateResult.ok(out);
    }

    private static GenerateResult fail(String code, String message, String path, List<String> suggestions) {
        CapabilityGap gap = new CapabilityGap(code, message, path, suggestions);
        AssemblyCompileDiagnostics.set(gap);
        FormacraftMod.LOGGER.warn("AssemblyPatchGenerator: {} at {} — {}", code, path, message);
        return GenerateResult.failed(gap);
    }

    private static GenerateResult failFromIssue(AssemblyValidationIssue issue, List<String> suggestions) {
        return fail(
                issue.code(),
                issue.message(),
                issue.path(),
                suggestions
        );
    }

    /**
     * 从 params 解析 assembly 载荷：优先 {@code params.assembly}，否则 params 本身含 graph/ops/macro 时视为 assembly 根对象。
     */
    public static Object resolveAssemblyPayload(Map<String, Object> params) {
        if (params == null || params.isEmpty()) {
            return null;
        }
        Object nested = params.get("assembly");
        if (nested instanceof Map<?, ?>) {
            return nested;
        }
        if (params.containsKey("ops") || params.containsKey("components") || params.containsKey("graph")
                || params.containsKey("macro") || params.containsKey("preset") || params.containsKey("presetId")) {
            return new HashMap<>(params);
        }
        return null;
    }

    private static BuildingSpec buildFootprintHint(Component component) {
        BuildingSpec spec = new BuildingSpec();
        spec.setExtra(new HashMap<>());
        Footprint footprint = new Footprint();
        footprint.setShape("rectangle");
        Dimensions dims = component.dimensions();
        if (dims != null) {
            footprint.setWidth(Math.max(1, dims.width()));
            footprint.setDepth(Math.max(1, dims.depth()));
            spec.setHeight(Math.max(1, dims.height()));
        } else {
            footprint.setWidth(10);
            footprint.setDepth(10);
            spec.setHeight(10);
        }
        spec.setFootprint(footprint);
        return spec;
    }

    private static Direction resolveEntranceFacing(
            SemanticComponent semantic,
            Map<String, Object> params,
            String specFacing
    ) {
        if (specFacing != null && !specFacing.isBlank()) {
            Direction parsed = parseDirection(specFacing);
            if (parsed != null) {
                return parsed;
            }
        }
        String fromParams = getParamString(params, "entranceFacing", "entrance_facing", "facing");
        if (fromParams != null) {
            Direction parsed = parseDirection(fromParams);
            if (parsed != null) {
                return parsed;
            }
        }
        if (semantic != null && semantic.slot() != null && semantic.slot().facing() != null) {
            return directionFromFacing(semantic.slot().facing());
        }
        return Direction.SOUTH;
    }

    private static String resolvePaletteId(SemanticComponent semantic, Map<String, Object> params, String specPalette) {
        if (specPalette != null && !specPalette.isBlank()) {
            return specPalette.trim();
        }
        String fromParams = getParamString(params, "paletteId", "palette_id");
        if (fromParams != null && !fromParams.isBlank()) {
            return fromParams.trim();
        }
        if (semantic != null && semantic.styleProfile() != null && !semantic.styleProfile().isBlank()) {
            return semantic.styleProfile().trim();
        }
        return null;
    }

    private static Direction parseDirection(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Direction.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return switch (value.trim().toUpperCase(Locale.ROOT)) {
                case "N", "北" -> Direction.NORTH;
                case "E", "东" -> Direction.EAST;
                case "W", "西" -> Direction.WEST;
                default -> Direction.SOUTH;
            };
        }
    }

    private static Direction directionFromFacing(com.formacraft.common.llm.dto.GlobalConstraints.Facing facing) {
        if (facing == null) {
            return Direction.SOUTH;
        }
        return switch (facing) {
            case NORTH -> Direction.NORTH;
            case EAST -> Direction.EAST;
            case WEST -> Direction.WEST;
            default -> Direction.SOUTH;
        };
    }

    private static String normalizeType(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    private static String getParamString(Map<String, Object> params, String... keys) {
        if (params == null || keys == null) {
            return null;
        }
        for (String key : keys) {
            if (key == null) {
                continue;
            }
            Object v = params.get(key);
            if (v == null) {
                continue;
            }
            String s = String.valueOf(v).trim();
            if (!s.isEmpty()) {
                return s;
            }
        }
        return null;
    }
}
