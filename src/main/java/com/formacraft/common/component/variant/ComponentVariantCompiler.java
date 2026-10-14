package com.formacraft.common.component.variant;

import com.formacraft.common.component.model.ComponentPrototype;
import com.formacraft.common.component.model.PersistedComponentVariant;
import com.formacraft.common.component.transform.Mirror;
import com.formacraft.common.logging.FcaLog;
import com.formacraft.common.patch.BlockPatch;
import com.formacraft.common.style.profile.StyleProfile;
import net.minecraft.util.math.Direction;

import java.util.List;

/**
 * ComponentVariantCompiler（变体编译器）v1：核心编译流水线。
 */
public final class ComponentVariantCompiler {
    private ComponentVariantCompiler() {}

    private static final FcaLog LOG = FcaLog.of("ComponentVariantCompiler");

    /** Structured compile; prefer this over {@link #compile}. */
    public static VariantCompileResult compileResult(
            ComponentPrototype proto,
            PersistedComponentVariant variant,
            Direction targetFacing,
            StyleProfile style
    ) {
        if (proto == null) {
            return VariantCompileResult.failure(VariantCompileResult.Status.INVALID_INPUT, "prototype_null");
        }
        if (variant == null || variant.params == null) {
            return VariantCompileResult.failure(VariantCompileResult.Status.INVALID_INPUT, "variant_params_missing");
        }

        Direction originalFacing = parseOriginalFacing(proto);

        StructureTemplate tpl = StructureLoader.load(proto);
        if (tpl == null || tpl.all().isEmpty()) {
            return VariantCompileResult.failure(VariantCompileResult.Status.TEMPLATE_MISSING,
                    "structure_template_missing id=" + proto.id);
        }

        try {
            VoxelGrid grid = SegmentScaler.applyScaling(tpl, getScalingRules(proto), variant.params.scale);
            if (style != null && proto.variant_rules != null && proto.variant_rules.material != null) {
                SemanticMaterialApplier.apply(grid, proto.variant_rules.material, variant.params.material_set, style);
            }
            Mirror mirror = parseMirror(variant.params.mirror);
            grid = TransformApplier.apply(grid, targetFacing, mirror, originalFacing);
            return VariantCompileResult.success(PatchEmitter.emit(grid));
        } catch (IllegalArgumentException e) {
            return VariantCompileResult.failure(VariantCompileResult.Status.GEOMETRY_INVALID,
                    e.getMessage() == null ? "geometry_invalid" : e.getMessage());
        }
    }

    /**
     * Legacy entry: returns patches only for SUCCESS/EMPTY_ALLOWED; logs other failures.
     */
    public static List<BlockPatch> compile(
            ComponentPrototype proto,
            PersistedComponentVariant variant,
            Direction targetFacing,
            StyleProfile style
    ) {
        return compileToPatches(compileResult(proto, variant, targetFacing, style));
    }

    public static List<BlockPatch> compileToPatches(VariantCompileResult result) {
        if (result == null) return List.of();
        if (result.ok()) return result.patches();
        LOG.warn("variant compile failed status={} message={}", result.status(), result.message());
        return List.of();
    }

    private static ComponentPrototype.VariantRules.Scaling getScalingRules(ComponentPrototype proto) {
        if (proto.variant_rules == null) return null;
        return proto.variant_rules.scaling;
    }

    private static Direction parseOriginalFacing(ComponentPrototype proto) {
        if (proto.structure == null || proto.structure.default_facing == null) return Direction.SOUTH;
        try {
            Direction d = Direction.valueOf(proto.structure.default_facing.trim().toUpperCase());
            return d.getAxis().isHorizontal() ? d : Direction.SOUTH;
        } catch (Throwable t) {
            LOG.debug("parse default_facing failed componentId={} value={}", proto.id, proto.structure.default_facing, t);
            return Direction.SOUTH;
        }
    }

    private static Mirror parseMirror(String mirror) {
        if (mirror == null || mirror.isBlank()) return Mirror.NONE;
        try {
            return Mirror.valueOf(mirror.trim().toUpperCase());
        } catch (Throwable t) {
            LOG.debug("parse mirror failed value={}", mirror, t);
            return Mirror.NONE;
        }
    }
}
