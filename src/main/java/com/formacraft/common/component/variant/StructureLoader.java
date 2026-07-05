package com.formacraft.common.component.variant;

import com.formacraft.common.component.ComponentDefinition;
import com.formacraft.common.component.model.ComponentPrototype;
import com.formacraft.common.component.model.ComponentPrototypeStorage;
import com.formacraft.common.json.JsonUtil;
import com.formacraft.common.logging.FcaLog;
import com.formacraft.common.semantic.SemanticPart;

import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * StructureLoader（结构加载器）：从磁盘加载结构模板并转换为 {@link StructureTemplate}。
 * <p>
 * 支持格式（按 prototype.structure.format 或自动探测）：
 * <ol>
 *   <li>{@code nbt} / {@code structure.nbt} — Minecraft 结构方块 + Formacraft 扩展</li>
 *   <li>{@code component_v1_json} / {@code json} — {@code component.json}</li>
 * </ol>
 */
public final class StructureLoader {
    private StructureLoader() {}

    private static final FcaLog LOG = FcaLog.of("StructureLoader");

    /**
     * 从 prototype 加载结构模板。
     */
    public static StructureTemplate load(ComponentPrototype proto) {
        if (proto == null || proto.structure == null) return emptyTemplate();
        if (proto.id == null || proto.id.isBlank()) return emptyTemplate();

        Path dir = resolvePrototypeDir(proto);
        if (dir == null) return emptyTemplate();

        String fmt = proto.structure.format != null ? proto.structure.format.trim().toLowerCase(Locale.ROOT) : "";
        String file = proto.structure.file;

        // 1) 显式 format
        StructureTemplate explicit = loadByFormat(fmt, file, dir, proto);
        if (!explicit.all().isEmpty()) {
            return explicit;
        }

        // 2) 自动探测（NBT 优先，再 JSON）
        for (String candidate : new String[]{"structure.nbt", "component.json", "structure.json"}) {
            StructureTemplate detected = loadCandidate(dir, candidate, proto);
            if (!detected.all().isEmpty()) {
                return detected;
            }
        }

        return emptyTemplate();
    }

    /**
     * 从 ComponentDefinition（v1 结构）转换为 StructureTemplate。
     */
    public static StructureTemplate fromComponentDefinition(ComponentDefinition def) {
        if (def == null || def.blocks == null || def.blocks.isEmpty()) return emptyTemplate();

        int w = def.size != null ? def.size.w : 1;
        int h = def.size != null ? def.size.h : 1;
        int d = def.size != null ? def.size.d : 1;

        List<Voxel> voxels = new ArrayList<>();
        for (ComponentDefinition.BlockEntry be : def.blocks) {
            if (be == null) continue;
            String blockState = be.block != null ? be.block : "minecraft:air";
            Voxel v = new Voxel(be.dx, be.dy, be.dz, blockState);

            if (be.semantic != null) {
                v.addSemanticTag(be.semantic.name());
            } else {
                String inferred = inferSemanticFromBlock(blockState);
                if (inferred != null) v.addSemanticTag(inferred);
            }
            voxels.add(v);
        }

        return new StructureTemplate(voxels, w, h, d);
    }

    private static StructureTemplate loadByFormat(String fmt, String file, Path dir, ComponentPrototype proto) {
        if (isNbtFormat(fmt)) {
            String nbtFile = (file != null && !file.isBlank()) ? file : "structure.nbt";
            return loadCandidate(dir, nbtFile, proto);
        }
        if (isJsonFormat(fmt)) {
            String jsonFile = (file != null && !file.isBlank()) ? file : "component.json";
            return loadJsonFile(dir.resolve(jsonFile), proto.id);
        }
        return emptyTemplate();
    }

    private static StructureTemplate loadCandidate(Path dir, String filename, ComponentPrototype proto) {
        Path primary = dir.resolve(filename);
        if (Files.exists(primary)) {
            StructureTemplate tpl = loadFile(primary, proto.id);
            if (!tpl.all().isEmpty()) return tpl;
        }
        Path legacyDir = ComponentPrototypeStorage.getLegacyPrototypeDir(proto.category, proto.id);
        Path legacyFile = legacyDir.resolve(filename);
        if (Files.exists(legacyFile)) {
            return loadFile(legacyFile, proto.id);
        }
        return emptyTemplate();
    }

    private static StructureTemplate loadFile(Path file, String componentId) {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.endsWith(".nbt")) {
            StructureTemplate tpl = StructureNbtReader.read(file);
            if (!tpl.all().isEmpty()) {
                return tpl;
            }
        }
        return loadJsonFile(file, componentId);
    }

    private static StructureTemplate loadJsonFile(Path file, String componentId) {
        if (file == null || !Files.exists(file)) {
            return emptyTemplate();
        }
        try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            ComponentDefinition def = JsonUtil.get().fromJson(r, ComponentDefinition.class);
            if (def == null) return emptyTemplate();
            return fromComponentDefinition(def);
        } catch (Throwable t) {
            LOG.warn("load structure json failed componentId={} path={}", componentId, file, t);
            return emptyTemplate();
        }
    }

    private static Path resolvePrototypeDir(ComponentPrototype proto) {
        if (proto.category == null || proto.id == null) {
            return null;
        }
        return ComponentPrototypeStorage.getPrototypeDir(proto.category, proto.id);
    }

    private static boolean isNbtFormat(String fmt) {
        return fmt.equals("nbt") || fmt.equals("structure_nbt") || fmt.equals("minecraft_nbt");
    }

    private static boolean isJsonFormat(String fmt) {
        return fmt.isEmpty() || fmt.equals("json") || fmt.equals("component_v1_json");
    }

    private static String inferSemanticFromBlock(String block) {
        if (block == null || block.isBlank()) return null;
        String b = block.toLowerCase(Locale.ROOT);
        if (b.contains("glass")) return SemanticPart.WINDOW.name();
        if (b.contains("door")) return SemanticPart.DOOR.name();
        if (b.contains("pillar") || b.contains("column")) return SemanticPart.PILLAR.name();
        if (b.contains("stairs") || b.contains("slab")) return SemanticPart.STAIRS.name();
        return SemanticPart.GENERIC.name();
    }

    private static StructureTemplate emptyTemplate() {
        return new StructureTemplate(List.of(), 0, 0, 0);
    }
}
