package com.formacraft.common.component.variant;

import com.formacraft.common.logging.FcaLog;
import com.formacraft.common.semantic.SemanticPart;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtSizeTracker;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * 读取 Minecraft 结构方块导出的 {@code structure.nbt}（palette + blocks + size），
 * 并可选解析 Formacraft 扩展元数据（分段/语义标签）。
 * <p>
 * 兼容格式：
 * <ul>
 *   <li>Vanilla structure block export（gzip 压缩，标准 palette/blocks/size）</li>
 *   <li>Formacraft 扩展：顶层 {@code formacraft.tags[]} 携带 segment / semantic</li>
 * </ul>
 */
public final class StructureNbtReader {
    private StructureNbtReader() {}

    private static final FcaLog LOG = FcaLog.of("StructureNbtReader");

    public static StructureTemplate read(Path path) {
        if (path == null || !java.nio.file.Files.exists(path)) {
            return empty();
        }
        try {
            NbtCompound root = readRoot(path);
            if (root == null || root.isEmpty()) {
                return empty();
            }
            return parse(root);
        } catch (Throwable t) {
            LOG.warn("read structure nbt failed path={}", path, t);
            return empty();
        }
    }

    public static StructureTemplate parse(NbtCompound root) {
        if (root == null || root.isEmpty()) {
            return empty();
        }

        int[] size = readSize(root);
        int w = size[0] > 0 ? size[0] : 1;
        int h = size[1] > 0 ? size[1] : 1;
        int d = size[2] > 0 ? size[2] : 1;

        List<String> palette = readPalette(root);
        Map<String, TagMeta> tagMeta = readFormacraftTags(root);

        NbtList blocks = root.getList("blocks").orElse(new NbtList());
        List<Voxel> voxels = new ArrayList<>();
        for (int i = 0; i < blocks.size(); i++) {
            NbtCompound block = blocks.getCompound(i).orElse(null);
            if (block == null || block.isEmpty()) continue;

            int[] pos = readBlockPos(block);
            int stateId = block.getInt("state", 0);
            String blockState = paletteStateAt(palette, stateId);
            if (isAir(blockState)) continue;

            Voxel v = new Voxel(pos[0], pos[1], pos[2], blockState);
            TagMeta meta = tagMeta.get(posKey(pos[0], pos[1], pos[2]));
            if (meta != null) {
                if (meta.segment != null) v.addSegmentTag(meta.segment);
                if (meta.semantic != null) v.addSemanticTag(meta.semantic);
            } else {
                String inferred = inferSemanticFromBlock(blockState);
                if (inferred != null) v.addSemanticTag(inferred);
            }
            voxels.add(v);
        }

        if (voxels.isEmpty()) {
            return empty();
        }
        return new StructureTemplate(voxels, w, h, d);
    }

    private static NbtCompound readRoot(Path path) throws IOException {
        try {
            return NbtIo.readCompressed(path, NbtSizeTracker.ofUnlimitedBytes());
        } catch (Throwable compressedFailed) {
            LOG.debug("readCompressed failed path={}, trying uncompressed", path, compressedFailed);
            return NbtIo.read(path);
        }
    }

    private static int[] readSize(NbtCompound root) {
        NbtList sizeList = root.getList("size").orElse(new NbtList());
        if (sizeList.size() >= 3) {
            return new int[]{
                    sizeList.getInt(0, 1),
                    sizeList.getInt(1, 1),
                    sizeList.getInt(2, 1)
            };
        }
        return new int[]{1, 1, 1};
    }

    private static List<String> readPalette(NbtCompound root) {
        List<String> palette = new ArrayList<>();
        NbtList paletteList = root.getList("palette").orElse(new NbtList());
        for (int i = 0; i < paletteList.size(); i++) {
            NbtCompound entry = paletteList.getCompound(i).orElse(null);
            palette.add(paletteEntryToBlockState(entry));
        }
        if (palette.isEmpty()) {
            palette.add("minecraft:air");
        }
        return palette;
    }

    static String paletteEntryToBlockState(NbtCompound entry) {
        if (entry == null || entry.isEmpty()) {
            return "minecraft:air";
        }
        String name = entry.contains("Name")
                ? entry.getString("Name", "")
                : entry.getString("name", "");
        if (name == null || name.isBlank()) {
            return "minecraft:air";
        }

        NbtCompound props = entry.contains("Properties")
                ? entry.getCompound("Properties").orElse(new NbtCompound())
                : entry.getCompound("properties").orElse(new NbtCompound());
        if (props.isEmpty()) {
            return name;
        }

        TreeMap<String, String> sorted = new TreeMap<>();
        for (String key : props.getKeys()) {
            sorted.put(key, props.getString(key, ""));
        }
        StringBuilder sb = new StringBuilder(name).append('[');
        boolean first = true;
        for (Map.Entry<String, String> e : sorted.entrySet()) {
            if (!first) sb.append(',');
            first = false;
            sb.append(e.getKey()).append('=').append(e.getValue());
        }
        sb.append(']');
        return sb.toString();
    }

    private static int[] readBlockPos(NbtCompound block) {
        NbtList pos = block.getList("pos").orElse(new NbtList());
        if (pos.size() >= 3) {
            return new int[]{pos.getInt(0, 0), pos.getInt(1, 0), pos.getInt(2, 0)};
        }
        return new int[]{
                block.getInt("x", 0),
                block.getInt("y", 0),
                block.getInt("z", 0)
        };
    }

    private static String paletteStateAt(List<String> palette, int stateId) {
        if (palette == null || palette.isEmpty()) {
            return "minecraft:air";
        }
        if (stateId < 0 || stateId >= palette.size()) {
            return "minecraft:air";
        }
        String s = palette.get(stateId);
        return s != null ? s : "minecraft:air";
    }

    private static Map<String, TagMeta> readFormacraftTags(NbtCompound root) {
        Map<String, TagMeta> out = new HashMap<>();
        NbtCompound fc = root.getCompound("formacraft").orElse(null);
        if (fc == null || fc.isEmpty()) {
            return out;
        }
        NbtList tags = fc.getList("tags").orElse(new NbtList());
        for (int i = 0; i < tags.size(); i++) {
            NbtCompound t = tags.getCompound(i).orElse(null);
            if (t == null) continue;
            int x = t.getInt("x", 0);
            int y = t.getInt("y", 0);
            int z = t.getInt("z", 0);
            String segment = readOptionalString(t, "segment");
            String semantic = readOptionalString(t, "semantic");
            if (segment != null || semantic != null) {
                out.put(posKey(x, y, z), new TagMeta(segment, semantic));
            }
        }
        return out;
    }

    private static String readOptionalString(NbtCompound nbt, String key) {
        if (nbt == null || key == null || !nbt.contains(key)) {
            return null;
        }
        String s = nbt.getString(key, "").trim();
        return s.isEmpty() ? null : s;
    }

    private static String posKey(int x, int y, int z) {
        return x + "," + y + "," + z;
    }

    private static boolean isAir(String blockState) {
        if (blockState == null || blockState.isBlank()) return true;
        String lower = blockState.toLowerCase(Locale.ROOT);
        return lower.equals("minecraft:air")
                || lower.equals("minecraft:cave_air")
                || lower.equals("minecraft:void_air")
                || lower.startsWith("minecraft:air[");
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

    private static StructureTemplate empty() {
        return new StructureTemplate(List.of(), 0, 0, 0);
    }

    private record TagMeta(String segment, String semantic) {}
}
