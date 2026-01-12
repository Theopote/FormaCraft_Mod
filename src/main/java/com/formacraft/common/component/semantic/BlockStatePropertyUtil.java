package com.formacraft.common.component.semantic;

import net.minecraft.block.BlockState;
import net.minecraft.state.property.Property;
import net.minecraft.util.math.Direction;

import java.util.Locale;
import java.util.Optional;

/**
 * v1：对 BlockState 进行“尽力而为”的属性写入（目前主要用于 facing/horizontal_facing）。
 */
public final class BlockStatePropertyUtil {
    private BlockStatePropertyUtil() {}

    public static BlockState applyFacing(BlockState state, Direction facing) {
        if (state == null || facing == null) return state;

        // 优先：facing
        BlockState s = setEnumProperty(state, "facing", facing.name().toLowerCase(Locale.ROOT));
        if (s != state) return s;

        // 次选：horizontal_facing
        return setEnumProperty(state, "horizontal_facing", facing.name().toLowerCase(Locale.ROOT));
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static BlockState setEnumProperty(BlockState state, String propName, String value) {
        if (state == null || propName == null || propName.isBlank() || value == null || value.isBlank()) return state;
        for (Property<?> p : state.getProperties()) {
            if (p == null) continue;
            if (!propName.equalsIgnoreCase(p.getName())) continue;
            Optional<?> parsed = p.parse(value.toLowerCase(Locale.ROOT));
            if (parsed.isEmpty()) return state;
            Object v = parsed.get();
            if (!(v instanceof Comparable<?> c)) return state;
            Property rawProp = (Property) p;
            Comparable rawValue = (Comparable) c;
            // 这里的 unchecked 是可接受的：Property.parse() 成功意味着类型匹配。
            //noinspection unchecked
            return state.with(rawProp, rawValue);
        }
        return state;
    }
}

