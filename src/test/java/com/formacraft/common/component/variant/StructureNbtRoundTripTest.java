package com.formacraft.common.component.variant;

import com.formacraft.common.component.ComponentCategory;
import com.formacraft.common.component.ComponentDefinition;
import com.formacraft.common.semantic.SemanticPart;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StructureNbtRoundTripTest {

    @TempDir
    Path tempDir;

    @Test
    void writesAndReadsVanillaCompatibleStructure() throws Exception {
        ComponentDefinition def = new ComponentDefinition();
        def.id = "nbt.roundtrip";
        def.category = ComponentCategory.RAILING;
        def.size = new ComponentDefinition.Size();
        def.size.w = 2;
        def.size.h = 1;
        def.size.d = 1;

        ComponentDefinition.BlockEntry a = new ComponentDefinition.BlockEntry();
        a.dx = 0;
        a.dy = 0;
        a.dz = 0;
        a.block = "minecraft:stone_bricks";
        a.semantic = SemanticPart.WALL;

        ComponentDefinition.BlockEntry b = new ComponentDefinition.BlockEntry();
        b.dx = 1;
        b.dy = 0;
        b.dz = 0;
        b.block = "minecraft:oak_fence";
        b.semantic = SemanticPart.RAILING;

        def.blocks = java.util.List.of(a, b);

        Path nbtPath = tempDir.resolve("structure.nbt");
        assertTrue(StructureNbtWriter.write(def, nbtPath));

        StructureTemplate tpl = StructureNbtReader.read(nbtPath);
        assertEquals(2, tpl.all().size());
        assertTrue(tpl.all().stream().anyMatch(v -> v.blockState().contains("stone_bricks")));
        assertTrue(tpl.all().stream().anyMatch(v -> v.hasSemanticTag(SemanticPart.RAILING.name())));
    }

    @Test
    void parsesInlineNbtCompound() {
        NbtCompound root = new NbtCompound();

        NbtList size = new NbtList();
        size.add(net.minecraft.nbt.NbtInt.of(1));
        size.add(net.minecraft.nbt.NbtInt.of(1));
        size.add(net.minecraft.nbt.NbtInt.of(1));
        root.put("size", size);

        NbtList palette = new NbtList();
        NbtCompound paletteEntry = new NbtCompound();
        paletteEntry.putString("Name", "minecraft:stone");
        palette.add(paletteEntry);
        root.put("palette", palette);

        NbtList blocks = new NbtList();
        NbtCompound block = new NbtCompound();
        NbtList pos = new NbtList();
        pos.add(net.minecraft.nbt.NbtInt.of(0));
        pos.add(net.minecraft.nbt.NbtInt.of(0));
        pos.add(net.minecraft.nbt.NbtInt.of(0));
        block.put("pos", pos);
        block.putInt("state", 0);
        blocks.add(block);
        root.put("blocks", blocks);

        StructureTemplate tpl = StructureNbtReader.parse(root);
        assertEquals(1, tpl.all().size());
        assertEquals("minecraft:stone", tpl.all().getFirst().blockState());
    }

    @Test
    void paletteEntryRoundTripsProperties() {
        String original = "minecraft:oak_stairs[facing=south,half=bottom]";
        NbtCompound entry = StructureNbtWriter.blockStateToPaletteEntry(original);
        String parsed = StructureNbtReader.paletteEntryToBlockState(entry);
        assertEquals(original, parsed);
    }

    @Test
    void skipsAirBlocks() {
        NbtCompound root = new NbtCompound();
        NbtList size = new NbtList();
        size.add(net.minecraft.nbt.NbtInt.of(1));
        size.add(net.minecraft.nbt.NbtInt.of(1));
        size.add(net.minecraft.nbt.NbtInt.of(1));
        root.put("size", size);

        NbtList palette = new NbtList();
        NbtCompound air = new NbtCompound();
        air.putString("Name", "minecraft:air");
        palette.add(air);
        NbtCompound stone = new NbtCompound();
        stone.putString("Name", "minecraft:stone");
        palette.add(stone);
        root.put("palette", palette);

        NbtList blocks = new NbtList();
        NbtCompound airBlock = new NbtCompound();
        NbtList pos0 = new NbtList();
        pos0.add(net.minecraft.nbt.NbtInt.of(0));
        pos0.add(net.minecraft.nbt.NbtInt.of(0));
        pos0.add(net.minecraft.nbt.NbtInt.of(0));
        airBlock.put("pos", pos0);
        airBlock.putInt("state", 0);
        blocks.add(airBlock);

        NbtCompound stoneBlock = new NbtCompound();
        NbtList pos1 = new NbtList();
        pos1.add(net.minecraft.nbt.NbtInt.of(0));
        pos1.add(net.minecraft.nbt.NbtInt.of(1));
        pos1.add(net.minecraft.nbt.NbtInt.of(0));
        stoneBlock.put("pos", pos1);
        stoneBlock.putInt("state", 1);
        blocks.add(stoneBlock);
        root.put("blocks", blocks);

        StructureTemplate tpl = StructureNbtReader.parse(root);
        assertEquals(1, tpl.all().size());
        assertFalse(tpl.all().getFirst().blockState().contains("air"));
    }
}
