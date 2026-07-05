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
        ComponentDefinition segmented = tryApplySegmentScaling(base, query, runtimeVariant);
        if (segmented != null) {
            return runtimeVariant != null
                    ? ComponentVariantApplier.applyWithoutRepeat(segmented, runtimeVariant)
                    : segmented;
        }
        return runtimeVariant != null
                ? ComponentVariantApplier.apply(base, runtimeVariant)
                : base;
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
        if (out.size.w == base.size.w && out.size.h == base.size.h && out.size.d == base.size.d) {
            return null;
        }
        return out;
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

        if (runtimeVariant != null && runtimeVariant.repeatCount > 1) {
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
    public static List<BlockPatch> compileToPatch(ComponentPrototype proto,
                                                 PersistedComponentVariant variant,
                                                 Direction facing,
                                                 StyleProfile style) {
        if (proto == null) return List.of();

        // 如果 variant 为 null，创建一个默认变体（原始尺寸、无材质替换）
        if (variant == null) {
            variant = makeDefaultVariant(proto);
        }

        // 使用新变体编译器（支持分段缩放 + 语义材质映射）
        return com.formacraft.common.component.variant.ComponentVariantCompiler.compile(proto, variant, facing, style);
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

