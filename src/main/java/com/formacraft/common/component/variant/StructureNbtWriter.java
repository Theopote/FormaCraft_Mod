package com.formacraft.common.component.variant;

import com.formacraft.common.component.ComponentDefinition;
import com.formacraft.common.logging.FcaLog;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtList;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 将 {@link ComponentDefinition} 导出为 Minecraft 结构方块兼容的 {@code structure.nbt}。
 * <p>
 * 额外写入 Formacraft 扩展 {@code formacraft.tags[]}（语义标签；分段标签可由后续工具标注）。
 */
public final class StructureNbtWriter {
    private StructureNbtWriter() {}

    private static final FcaLog LOG = FcaLog.of("StructureNbtWriter");

    public static boolean write(ComponentDefinition def, Path path) {
        if (def == null || path == null || def.blocks == null || def.blocks.isEmpty()) {
            return false;
        }
        try {
            NbtCompound root = toNbt(def);
            if (root == null || root.isEmpty()) {
                return false;
            }
            Path parent = path.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            NbtIo.writeCompressed(root, path);
            return true;
        } catch (Throwable t) {
            LOG.warn("write structure nbt failed componentId={} path={}", def.id, path, t);
            return false;
        }
    }

    public static NbtCompound toNbt(ComponentDefinition def) {
        if (def == null || def.blocks == null || def.blocks.isEmpty()) {
            return new NbtCompound();
        }

        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (ComponentDefinition.BlockEntry be : def.blocks) {
            if (be == null) continue;
            minX = Math.min(minX, be.dx); maxX = Math.max(maxX, be.dx);
            minY = Math.min(minY, be.dy); maxY = Math.max(maxY, be.dy);
            minZ = Math.min(minZ, be.dz); maxZ = Math.max(maxZ, be.dz);
        }
        if (minX == Integer.MAX_VALUE) {
            return new NbtCompound();
        }

        int w = def.size != null && def.size.w > 0 ? def.size.w : (maxX - minX + 1);
        int h = def.size != null && def.size.h > 0 ? def.size.h : (maxY - minY + 1);
        int d = def.size != null && def.size.d > 0 ? def.size.d : (maxZ - minZ + 1);

        Map<String, Integer> paletteIndex = new LinkedHashMap<>();
        List<NbtCompound> paletteEntries = new ArrayList<>();
        NbtList blocks = new NbtList();
        NbtList formacraftTags = new NbtList();

        for (ComponentDefinition.BlockEntry be : def.blocks) {
            if (be == null) continue;
            String blockState = be.block != null && !be.block.isBlank() ? be.block : "minecraft:stone";
            if (isAir(blockState)) continue;

            int stateId = paletteIndex.computeIfAbsent(blockState, bs -> {
                paletteEntries.add(blockStateToPaletteEntry(bs));
                return paletteEntries.size() - 1;
            });

            NbtCompound block = new NbtCompound();
            NbtList pos = new NbtList();
            pos.add(net.minecraft.nbt.NbtInt.of(be.dx));
            pos.add(net.minecraft.nbt.NbtInt.of(be.dy));
            pos.add(net.minecraft.nbt.NbtInt.of(be.dz));
            block.put("pos", pos);
            block.putInt("state", stateId);
            blocks.add(block);

            if (be.semantic != null) {
                NbtCompound tag = new NbtCompound();
                tag.putInt("x", be.dx);
                tag.putInt("y", be.dy);
                tag.putInt("z", be.dz);
                tag.putString("semantic", be.semantic.name());
                formacraftTags.add(tag);
            }
        }

        if (blocks.isEmpty()) {
            return new NbtCompound();
        }

        NbtCompound root = new NbtCompound();
        NbtList size = new NbtList();
        size.add(net.minecraft.nbt.NbtInt.of(w));
        size.add(net.minecraft.nbt.NbtInt.of(h));
        size.add(net.minecraft.nbt.NbtInt.of(d));
        root.put("size", size);

        NbtList palette = new NbtList();
        for (NbtCompound entry : paletteEntries) {
            palette.add(entry);
        }
        root.put("palette", palette);
        root.put("blocks", blocks);

        if (!formacraftTags.isEmpty()) {
            NbtCompound fc = new NbtCompound();
            fc.putInt("version", 1);
            fc.put("tags", formacraftTags);
            root.put("formacraft", fc);
        }
        return root;
    }

    static NbtCompound blockStateToPaletteEntry(String blockState) {
        NbtCompound entry = new NbtCompound();
        if (blockState == null || blockState.isBlank()) {
            entry.putString("Name", "minecraft:air");
            return entry;
        }

        int bracket = blockState.indexOf('[');
        if (bracket < 0) {
            entry.putString("Name", blockState);
            return entry;
        }

        String name = blockState.substring(0, bracket);
        entry.putString("Name", name);

        int end = blockState.lastIndexOf(']');
        if (end <= bracket) {
            return entry;
        }

        String propsBody = blockState.substring(bracket + 1, end);
        if (propsBody.isBlank()) {
            return entry;
        }

        NbtCompound props = new NbtCompound();
        for (String part : propsBody.split(",")) {
            String[] kv = part.split("=", 2);
            if (kv.length != 2) continue;
            String k = kv[0].trim();
            String v = kv[1].trim();
            if (!k.isEmpty()) {
                props.putString(k, v);
            }
        }
        if (!props.isEmpty()) {
            entry.put("Properties", props);
        }
        return entry;
    }

    private static boolean isAir(String blockState) {
        if (blockState == null || blockState.isBlank()) return true;
        String lower = blockState.toLowerCase(Locale.ROOT);
        return lower.equals("minecraft:air")
                || lower.startsWith("minecraft:air[")
                || lower.equals("minecraft:cave_air")
                || lower.equals("minecraft:void_air");
    }
}
