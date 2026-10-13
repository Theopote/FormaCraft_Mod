package com.formacraft.common.component.semantic;

import com.formacraft.common.component.transform.BlockStateStringUtil;
import com.formacraft.common.palette.component.MaterialBlockFamily;
import com.formacraft.common.patch.BlockPatchTargetResolver;
import net.minecraft.block.*;
import net.minecraft.registry.Registries;
import net.minecraft.state.property.Property;
import net.minecraft.util.Identifier;

/** Material changes never turn stairs/slabs/functional objects into solid cubes. */
public final class ShapePreservingMaterialSwap {
    private ShapePreservingMaterialSwap() {}

    public static BlockState apply(String captured, BlockState requested) {
        BlockState original = BlockPatchTargetResolver.parse(captured);
        if (original == null || requested == null) return original;
        Block from = original.getBlock();
        Block to = requested.getBlock();
        if (original.isAir() || from instanceof BlockEntityProvider || from instanceof DoorBlock
                || from instanceof BedBlock || from instanceof TrapdoorBlock) return original;
        String base = Registries.BLOCK.getId(to).toString();
        String shape = null;
        if (from instanceof StairsBlock && !(to instanceof StairsBlock)) shape = MaterialBlockFamily.stairs(base);
        else if (from instanceof SlabBlock && !(to instanceof SlabBlock)) shape = MaterialBlockFamily.slab(base);
        else if (from instanceof WallBlock && !(to instanceof WallBlock)) shape = family(base, "wall");
        else if (from instanceof FenceBlock && !(to instanceof FenceBlock)) shape = family(base, "fence");
        else if (from instanceof PaneBlock && !(to instanceof PaneBlock)) shape = base.endsWith("_glass")
                ? base + "_pane" : base.equals("minecraft:glass") ? "minecraft:glass_pane" : null;
        if (shape != null) {
            BlockState shaped = BlockPatchTargetResolver.parse(shape);
            if (shaped != null) { requested = shaped; to = shaped.getBlock(); }
        }
        boolean compatible = from instanceof StairsBlock && to instanceof StairsBlock
                || from instanceof SlabBlock && to instanceof SlabBlock
                || from instanceof WallBlock && to instanceof WallBlock
                || from instanceof FenceBlock && to instanceof FenceBlock
                || from instanceof PaneBlock && to instanceof PaneBlock
                || from instanceof PillarBlock && to instanceof PillarBlock
                || from.getClass() == Block.class && to.getClass() == Block.class;
        // Transparent full blocks retain their role as glass rather than becoming stone walls.
        if (Registries.BLOCK.getId(from).getPath().contains("glass")
                && !Registries.BLOCK.getId(to).getPath().contains("glass")) compatible = false;
        if (!compatible) return original;
        return copyProperties(original, requested);
    }

    public static String apply(String captured, String requested) {
        BlockState out = apply(captured, BlockPatchTargetResolver.parse(requested));
        return out == null ? captured : BlockStateStringUtil.fromState(out);
    }

    private static String family(String base, String shape) {
        String stem = base.replaceFirst("_(planks|stairs|slab|wall|fence)$", "")
                .replaceFirst("bricks$", "brick").replaceFirst("tiles$", "tile");
        Identifier id = Identifier.tryParse(stem + "_" + shape);
        return id != null && Registries.BLOCK.containsId(id) ? id.toString() : null;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static BlockState copyProperties(BlockState from, BlockState to) {
        for (Property property : from.getProperties()) {
            Property target = to.getBlock().getStateManager().getProperty(property.getName());
            if (target == null) continue;
            String value = property.name(from.get(property));
            var parsed = target.parse(value);
            if (parsed.isPresent()) to = to.with(target, (Comparable) parsed.get());
        }
        return to;
    }
}
