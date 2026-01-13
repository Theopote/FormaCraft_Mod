package com.formacraft.common.component.variant;

import com.formacraft.common.component.ComponentDefinition;
import com.formacraft.common.component.model.ComponentPrototype;
import com.formacraft.common.component.model.ComponentPrototypeStorage;
import com.formacraft.common.json.JsonUtil;
import com.formacraft.common.semantic.SemanticPart;

import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * StructureLoader（结构加载器）v1：从磁盘加载结构模板并转换为 StructureTemplate。
 * <p>
 * 支持格式（按优先级）：
 * 1. component.json（v1 兼容路径）
 * 2. structure.nbt（v2，后续补充）
 * 3. structure.json（v2，后续补充）
 */
public final class StructureLoader {
    private StructureLoader() {}

    /**
     * 从 prototype 加载结构模板。
     * <p>
     * v1：仅支持 structure.format = "component_v1_json" 或 "json"
     */
    public static StructureTemplate load(ComponentPrototype proto) {
        if (proto == null || proto.structure == null) return emptyTemplate();
        if (proto.id == null || proto.id.isBlank()) return emptyTemplate();
        if (proto.structure.file == null || proto.structure.file.isBlank()) return emptyTemplate();

        String fmt = proto.structure.format != null ? proto.structure.format.trim().toLowerCase() : "";
        boolean jsonOk = fmt.isEmpty() || fmt.equals("json") || fmt.equals("component_v1_json");
        if (!jsonOk) return emptyTemplate();

        Path dir = ComponentPrototypeStorage.getPrototypeDir(proto.category, proto.id);
        Path file = dir.resolve(proto.structure.file);
        if (!Files.exists(file)) {
            // legacy fallback
            Path legacyDir = ComponentPrototypeStorage.getLegacyPrototypeDir(proto.category, proto.id);
            file = legacyDir.resolve(proto.structure.file);
        }
        if (!Files.exists(file)) return emptyTemplate();

        try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            ComponentDefinition def = JsonUtil.get().fromJson(r, ComponentDefinition.class);
            if (def == null) return emptyTemplate();
            return fromComponentDefinition(def);
        } catch (Throwable ignored) {
            return emptyTemplate();
        }
    }

    /**
     * 从 ComponentDefinition（v1 结构）转换为 StructureTemplate。
     * <p>
     * 策略：
     * - blocks[].semantic → semantic tag（语义标签）
     * - 坐标分布 → 启发式推断 segment tag（分段标签）
     */
    private static StructureTemplate fromComponentDefinition(ComponentDefinition def) {
        if (def.blocks == null || def.blocks.isEmpty()) return emptyTemplate();

        int w = def.size != null ? def.size.w : 1;
        int h = def.size != null ? def.size.h : 1;
        int d = def.size != null ? def.size.d : 1;

        List<Voxel> voxels = new ArrayList<>();
        for (ComponentDefinition.BlockEntry be : def.blocks) {
            if (be == null) continue;
            String blockState = be.block != null ? be.block : "minecraft:air";
            Voxel v = new Voxel(be.dx, be.dy, be.dz, blockState);

            // semantic tag（语义标签）
            if (be.semantic != null) {
                v.addSemanticTag(be.semantic.name());
            } else {
                // 启发式推断（基于方块名）
                String inferred = inferSemanticFromBlock(blockState);
                if (inferred != null) v.addSemanticTag(inferred);
            }

            // segment tag（分段标签）会在 StructureTemplate.getSegment() 中启发式推断（如果没有显式标注）
            // v1：我们不在这里预先标注，让 StructureTemplate 按需推断即可

            voxels.add(v);
        }

        return new StructureTemplate(voxels, w, h, d);
    }

    /**
     * 启发式推断语义标签（从方块名推断）。
     * <p>
     * v1：简化规则（后续可扩展为查表或规则引擎）
     */
    private static String inferSemanticFromBlock(String block) {
        if (block == null || block.isBlank()) return null;
        String b = block.toLowerCase();
        if (b.contains("glass")) return SemanticPart.WINDOW.name();
        if (b.contains("door")) return SemanticPart.DOOR.name();
        if (b.contains("pillar") || b.contains("column")) return SemanticPart.PILLAR.name();
        if (b.contains("stairs") || b.contains("slab")) return SemanticPart.STAIRS.name();
        // 兜底：返回 GENERIC（后续可由 semantic_map 覆盖）
        return SemanticPart.GENERIC.name();
    }

    private static StructureTemplate emptyTemplate() {
        return new StructureTemplate(List.of(), 0, 0, 0);
    }
}
