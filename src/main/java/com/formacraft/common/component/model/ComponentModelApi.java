package com.formacraft.common.component.model;

import com.formacraft.common.component.ComponentDefinition;
import com.formacraft.common.component.ComponentDefinitionCompiler;
import com.formacraft.common.component.transform.Mirror;
import com.formacraft.common.json.JsonUtil;
import com.formacraft.common.patch.BlockPatch;
import net.minecraft.util.math.Direction;

import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
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

    // -------- Variant --------

    /**
     * 生成一个 Variant（不强制写盘）。
     * <p>
     * v1：variant_id 若为空，将根据 params 生成一个稳定 id。
     */
    public static ComponentVariant makeVariant(ComponentPrototype proto, ComponentVariant.Params params) {
        if (proto == null || proto.id == null || proto.id.isBlank()) return null;
        ComponentVariant v = new ComponentVariant();
        v.prototype_id = proto.id;
        v.params = params;

        String pid = proto.id.trim();
        String key = JsonUtil.toJson(params);
        String suffix = Integer.toHexString(key != null ? key.hashCode() : 0);
        v.variant_id = pid + "_" + suffix;
        return v;
    }

    public static void saveVariant(ComponentVariant variant, ComponentPrototype proto) {
        if (variant == null || proto == null) return;
        ComponentPrototypeStorage.saveVariant(variant, proto.category);
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

    // -------- Compile (v1: compatibility path) --------

    /**
     * 将 prototype + variant 编译为 BlockPatch（v1 兼容路径）。
     * <p>
     * 当前仅支持：
     * - structure.format == "component_v1_json" 或 "json"，且 structure.file 指向一个 ComponentDefinition JSON
     * <p>
     * NBT structure / 分段缩放 / 材质集等在后续补齐。
     */
    public static List<BlockPatch> compileToPatch(ComponentPrototype proto,
                                                 ComponentVariant variant,
                                                 int baseX, int baseY, int baseZ,
                                                 Direction facing,
                                                 Mirror mirror) {
        if (proto == null || proto.structure == null) return List.of();
        if (proto.id == null || proto.id.isBlank()) return List.of();
        if (proto.structure.file == null || proto.structure.file.isBlank()) return List.of();

        String fmt = proto.structure.format != null ? proto.structure.format.trim().toLowerCase() : "";
        boolean jsonOk = fmt.isEmpty() || fmt.equals("json") || fmt.equals("component_v1_json");
        if (!jsonOk) return List.of();

        Path dir = ComponentPrototypeStorage.getPrototypeDir(proto.category, proto.id);
        Path file = dir.resolve(proto.structure.file);
        if (!Files.exists(file)) return List.of();

        try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            ComponentDefinition def = JsonUtil.get().fromJson(r, ComponentDefinition.class);
            if (def == null) return List.of();
            // v1：先忽略 variant 的参数（scale/material/ornament），只走 facing/mirror 编译
            return ComponentDefinitionCompiler.compile(def, baseX, baseY, baseZ,
                    facing != null ? facing : Direction.SOUTH,
                    mirror != null ? mirror : Mirror.NONE,
                    false, null, 0L);
        } catch (Throwable ignored) {
            return List.of();
        }
    }
}

