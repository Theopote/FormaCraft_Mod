package com.formacraft.common.component.model;

import com.formacraft.common.component.ComponentDefinition;
import com.formacraft.common.component.query.ComponentQuery;
import com.formacraft.common.component.transform.Mirror;
import com.formacraft.common.component.variant.ComponentVariant;
import com.formacraft.common.component.variant.ComponentVariantApplier;
import com.formacraft.common.component.variant.ComponentVariantSpec;
import com.formacraft.common.component.variant.SegmentScaler;
import com.formacraft.common.component.variant.StructureLoader;
import com.formacraft.common.component.variant.StructureTemplate;
import com.formacraft.common.component.variant.VoxelGrid;
import com.formacraft.common.component.variant.VoxelGridConverter;
import com.formacraft.common.json.JsonUtil;
import com.formacraft.common.patch.BlockPatch;
import com.formacraft.common.style.profile.StyleProfile;
import net.minecraft.util.math.Direction;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

/**
 * Prototype / Variant / Instance 的最小 API（v1）。
 * <p>
 * 目标：把三层模型先“变成可调用的代码入口”，后续实现（NBT 结构、分段缩放、材质集、细节级别）
 * 都可以在不改变调用方的情况下逐步补齐。
 */
public final class ComponentModelApi {
    private ComponentModelApi() {}

    // -------- Prototype --------

    public static ComponentPrototype loadPrototype(String prototypeId) {
        return ComponentPrototypeStorage.loadPrototype(prototypeId);
    }

    public static void savePrototype(ComponentPrototype proto) {
        ComponentPrototypeStorage.savePrototype(proto);
    }

    public static ComponentPrototypeCatalog loadPrototypeCatalog() {
        return ComponentPrototypeStorage.loadCatalog();
    }

    public static ComponentPrototypeCatalog rebuildPrototypeCatalog() {
        return ComponentPrototypeStorage.rebuildCatalog();
    }

    public static List<ComponentPrototypeCatalog.Entry> listPrototypes() {
        ComponentPrototypeCatalog c = loadPrototypeCatalog();
        return (c != null && c.prototypes != null) ? c.prototypes : List.of();
    }

    public static ComponentPrototype importPrototypeFromComponentDefinition(ComponentDefinition def) {
        return ComponentPrototypeStorage.importFromComponentDefinition(def);
    }

    // -------- Variant --------

    /**
     * 生成一个 Variant（不强制写盘）。
     * <p>
     * v1：variant_id 若为空，将根据 params 生成一个稳定 id。
     */
    public static PersistedComponentVariant makeVariant(ComponentPrototype proto, PersistedComponentVariant.Params params) {
        if (proto == null || proto.id == null || proto.id.isBlank()) return null;
        PersistedComponentVariant v = new PersistedComponentVariant();
        v.prototype_id = proto.id;
        v.params = params;

        String pid = proto.id.trim();
        String key = JsonUtil.toJson(params);
        String suffix = Integer.toHexString(key != null ? key.hashCode() : 0);
        v.variant_id = pid + "_" + suffix;
        return v;
    }

    public static void saveVariant(PersistedComponentVariant variant, ComponentPrototype proto) {
        if (variant == null || proto == null) return;
        ComponentPrototypeStorage.saveVariant(variant, proto.category);
    }

    public static List<PersistedComponentVariant> loadPresetVariants(ComponentPrototype proto) {
        if (proto == null || proto.id == null || proto.id.isBlank()) return List.of();
        return ComponentPrototypeStorage.loadPresetVariants(proto.id, proto.category);
    }

    // -------- Instance --------

    public static ComponentInstance newInstance(String prototypeId, String variantId) {
        ComponentInstance inst = new ComponentInstance();
        inst.uuid = UUID.randomUUID().toString();
        inst.prototype_id = prototypeId;
        inst.variant_id = variantId;
        return inst;
    }

    public static void saveInstance(Path worldDir, ComponentInstance inst) {
        ComponentInstanceStorage.saveInstance(worldDir, inst);
    }

    // -------- Compile (v2: 使用新变体编译器) --------

    /**
     * 生产路径：优先 SegmentScaler 分段缩放，再回退 {@link ComponentVariantApplier}。
     */
    public static ComponentDefinition applyRuntimeVariant(
            ComponentDefinition base,
            ComponentQuery query,
            ComponentVariant runtimeVariant
    ) {
        if (base == null) {
            return null;
        }
        if (query != null && query.geometry != null && !query.geometry.scalable) return base;
        // CAPTURED without author variantRules: never adapt via archetype-injected rules or Applier.
        if (base.isCapturedOrigin() && base.variantRules == null) return base;
        ComponentDefinition segmented = tryApplySegmentScaling(base, query, runtimeVariant);
        if (segmented != null) {
            return segmented; // Never scale/trim again after a segmented fit.
        }
        // Author-ruled defs never fall through to integer scale/trim.
        if (base.variantRules != null) return base;
        return runtimeVariant != null ? ComponentVariantApplier.apply(base, runtimeVariant) : base;
    }

    /** Return null when no permitted geometry fits the requested dimensions and tolerance. */
    public static ComponentDefinition fitToQuery(ComponentDefinition base, ComponentQuery query,
                                                 ComponentVariant runtimeVariant) {
        try {
            if (com.formacraft.common.component.variant.CapturedComponentRuleValidator.validate(base) != null) return null;
            if (base != null && base.variantRules != null && matchesTarget(base, query)) return base;
            ComponentDefinition out = applyRuntimeVariant(base, query, runtimeVariant);
            return matchesTarget(out, query) ? out : null;
        } catch (IllegalArgumentException invalidFit) {
            return null;
        }
    }

    private static boolean matchesTarget(ComponentDefinition component, ComponentQuery query) {
        if (component == null || component.size == null || component.blocks == null || component.blocks.isEmpty()) return false;
        if (query == null || query.geometry == null) return true;
        var g = query.geometry;
        int tolerance = Math.max(0, Math.min(SegmentScaler.MAX_AXIS_SIZE, g.tolerance));
        return matchesDimension(component.size.w, g.openingWidth, tolerance)
                && matchesDimension(component.size.h, g.openingHeight, tolerance)
                && matchesDimension(component.size.d, g.targetDepth, tolerance);
    }

    private static boolean matchesDimension(int actual, Integer wanted, int tolerance) {
        return wanted == null || wanted > 0 && Math.abs((long) actual - wanted) <= tolerance;
    }

    /**
     * 尝试用 SegmentScaler 做 START/MID/END 分段缩放。
     *
     * @return 缩放后的构件；若无规则或无尺寸变化则返回 null（调用方应走 Applier 回退）
     */
    public static ComponentDefinition tryApplySegmentScaling(
            ComponentDefinition base,
            ComponentQuery query,
            ComponentVariant runtimeVariant
    ) {
        if (base == null || base.blocks == null || base.blocks.isEmpty()) {
            return null;
        }
        // Only author-declared rules scale; do not invent REPEAT from archetype for captures.
        if (base.variantRules == null || base.variantRules.scaling == null) {
            return null;
        }

        ComponentPrototype proto = RuntimeComponentPrototypeFactory.fromDefinition(base);
        if (proto == null || proto.variant_rules == null || proto.variant_rules.scaling == null) {
            return null;
        }

        PersistedComponentVariant.Params.Scale target = resolveTargetScale(base, query, runtimeVariant);
        if (!needsSegmentScaling(base, target)) {
            return null;
        }

        StructureTemplate tpl = StructureLoader.fromComponentDefinition(base);
        if (tpl == null || tpl.all().isEmpty()) {
            return null;
        }

        VoxelGrid grid = SegmentScaler.applyScaling(tpl, proto.variant_rules.scaling, target);
        if (grid == null || grid.size() == 0) {
            return null;
        }

        ComponentDefinition out = VoxelGridConverter.toComponentDefinition(base, grid);
        if (out == null || out.size == null || base.size == null) {
            return null;
        }
        return com.formacraft.common.component.variant.AdaptedComponentMetadata.remap(
                base, out, tpl, proto.variant_rules.scaling, target);
    }

    private static boolean needsSegmentScaling(ComponentDefinition base, PersistedComponentVariant.Params.Scale target) {
        if (base == null || base.size == null || target == null) {
            return false;
        }
        return target.x != base.size.w || target.y != base.size.h || target.z != base.size.d;
    }

    private static PersistedComponentVariant.Params.Scale resolveTargetScale(
            ComponentDefinition base,
            ComponentQuery query,
            ComponentVariant runtimeVariant
    ) {
        int bw = base.size != null && base.size.w > 0 ? base.size.w : 1;
        int bh = base.size != null && base.size.h > 0 ? base.size.h : 1;
        int bd = base.size != null && base.size.d > 0 ? base.size.d : 1;

        PersistedComponentVariant.Params.Scale scale = new PersistedComponentVariant.Params.Scale();
        scale.x = bw;
        scale.y = bh;
        scale.z = bd;

        if (query != null && query.geometry != null) {
            if (query.geometry.openingWidth != null && query.geometry.openingWidth > 0) {
                scale.x = query.geometry.openingWidth;
            }
            if (query.geometry.openingHeight != null && query.geometry.openingHeight > 0) {
                scale.y = query.geometry.openingHeight;
            }
        }

        if (query != null && query.geometry != null && query.geometry.targetDepth != null
                && query.geometry.targetDepth > 0) scale.z = query.geometry.targetDepth;

        if (base.variantRules != null && base.variantRules.scaling != null
                && base.variantRules.scaling.axes != null) {
            scale.x = permittedTarget(base, "X", scale.x, bw);
            scale.y = permittedTarget(base, "Y", scale.y, bh);
            scale.z = permittedTarget(base, "Z", scale.z, bd);
        }

        // Captured without scaling rules: never invent size from runtimeVariant.repeatCount.
        if (!base.isCapturedOrigin() && base.variantRules == null
                && runtimeVariant != null && runtimeVariant.repeatCount > 1) {
            int repeat = runtimeVariant.repeatCount;
            ComponentVariantSpec.Axis axis = runtimeVariant.repeatAxis != null
                    ? runtimeVariant.repeatAxis
                    : ComponentVariantSpec.Axis.X;
            switch (axis) {
                case Y -> scale.y = Math.max(scale.y, bh * repeat);
                case Z -> scale.z = Math.max(scale.z, bd * repeat);
                default -> scale.x = Math.max(scale.x, bw * repeat);
            }
        }
        return scale;
    }

    private static int permittedTarget(ComponentDefinition base, String axis, int desired, int original) {
        var rule = base.variantRules.scaling.axes.get(axis);
        if (rule == null || "FIXED".equalsIgnoreCase(rule.type)) return original;
        int min = Math.max(1, (rule.fixedStart == null ? 1 : rule.fixedStart)
                + (rule.fixedEnd == null ? 1 : rule.fixedEnd) + 1);
        if (rule.min != null) min = Math.max(min, rule.min);
        int max = "TRIM".equalsIgnoreCase(rule.type) ? original : SegmentScaler.MAX_AXIS_SIZE;
        if (rule.max != null) max = Math.min(max, rule.max);
        if (min > max) throw new IllegalArgumentException("Invalid permitted dimension range");
        return Math.clamp(desired, min, max);
    }

    /**
     * 将 prototype + variant 编译为 BlockPatch（v2：完整变体编译器）。
     * <p>
     * 支持：
     * - 分段缩放（REPEAT/TRIM/FIXED）
     * - 语义材质映射（semantic_map → StyleProfile.palette）
     * - 朝向/镜像变换
     * <p>
     * 注意：
     * - 如果 variant 为 null，会创建一个默认变体（原始尺寸、无材质替换）
     * - 如果 style 为 null，会跳过语义材质替换（保持原始方块）
     */
    /** Structured compile for production callers that must not treat empty lists as success. */
    public static com.formacraft.common.component.variant.VariantCompileResult compileToPatchResult(
            ComponentPrototype proto,
            PersistedComponentVariant variant,
            Direction facing,
            StyleProfile style) {
        if (proto == null) {
            return com.formacraft.common.component.variant.VariantCompileResult.failure(
                    com.formacraft.common.component.variant.VariantCompileResult.Status.INVALID_INPUT,
                    "prototype_null");
        }
        if (variant == null) {
            variant = makeDefaultVariant(proto);
        }
        var result = com.formacraft.common.component.variant.ComponentVariantCompiler.compileResult(
                proto, variant, facing, style);
        if (!result.ok()) {
            com.formacraft.FormacraftMod.LOGGER.error(
                    "[ComponentModelApi] variant compile failed id={} status={} message={}",
                    proto.id, result.status(), result.message());
        }
        return result;
    }

    public static List<BlockPatch> compileToPatch(ComponentPrototype proto,
                                                 PersistedComponentVariant variant,
                                                 Direction facing,
                                                 StyleProfile style) {
        return com.formacraft.common.component.variant.ComponentVariantCompiler.compileToPatches(
                compileToPatchResult(proto, variant, facing, style));
    }

    /**
     * 将 prototype + variant 编译为 BlockPatch（v2 重载：带基础坐标）。
     * <p>
     * 注意：
     * - baseX/baseY/baseZ 不在编译器内部处理（BlockPatch 是相对坐标）
     * - 调用方需要自行叠加 baseX/baseY/baseZ 到 BlockPatch.dx/dy/dz
     */
    public static List<BlockPatch> compileToPatch(ComponentPrototype proto,
                                                 PersistedComponentVariant variant,
                                                 int baseX, int baseY, int baseZ,
                                                 Direction facing,
                                                 StyleProfile style) {
        List<BlockPatch> patches = compileToPatch(proto, variant, facing, style);
        // 叠加基础坐标（转换为绝对坐标）
        return patches.stream()
                .map(p -> new BlockPatch(p.action(), p.dx() + baseX, p.dy() + baseY, p.dz() + baseZ, p.targetBlock()))
                .toList();
    }

    /**
     * 兼容 v1 调用方（传 Mirror 而非 StyleProfile）。
     * <p>
     * 注意：v1 的 Mirror 参数在 variant.params.mirror 中已包含，这里忽略传入的 mirror 参数。
     */
    public static List<BlockPatch> compileToPatch(ComponentPrototype proto,
                                                 PersistedComponentVariant variant,
                                                 int baseX, int baseY, int baseZ,
                                                 Direction facing,
                                                 Mirror mirror) {
        // v1 兼容路径：不传 StyleProfile，跳过语义材质替换
        StyleProfile style = null;
        return compileToPatch(proto, variant, baseX, baseY, baseZ, facing, style);
    }

    /**
     * 创建默认变体（原始尺寸、无材质替换）。
     */
    private static PersistedComponentVariant makeDefaultVariant(ComponentPrototype proto) {
        PersistedComponentVariant v = new PersistedComponentVariant();
        v.prototype_id = proto.id;
        v.variant_id = proto.id + "_default";
        v.params = new PersistedComponentVariant.Params();
        v.params.scale = new PersistedComponentVariant.Params.Scale();
        v.params.scale.x = proto.structure != null && proto.structure.bounds != null ? proto.structure.bounds.w : 1;
        v.params.scale.y = proto.structure != null && proto.structure.bounds != null ? proto.structure.bounds.h : 1;
        v.params.scale.z = proto.structure != null && proto.structure.bounds != null ? proto.structure.bounds.d : 1;
        v.params.mirror = "NONE";
        v.params.material_set = null;
        v.params.ornament_level = null;
        return v;
    }
}

