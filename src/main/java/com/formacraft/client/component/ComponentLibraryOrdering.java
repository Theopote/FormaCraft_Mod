package com.formacraft.client.component;

import com.formacraft.common.component.ComponentCatalog;
import java.util.*;
import java.util.function.*;

public final class ComponentLibraryOrdering {
    private ComponentLibraryOrdering() {}
    public static Comparator<ComponentCatalog.Entry> comparator(String sort, ToIntFunction<ComponentCatalog.Entry> score,
                                                                ToLongFunction<ComponentCatalog.Entry> lastLoaded) {
        Comparator<ComponentCatalog.Entry> names = Comparator.comparing(e -> e.name == null || e.name.isBlank() ? e.id : e.name,String.CASE_INSENSITIVE_ORDER);
        Comparator<ComponentCatalog.Entry> recent = Comparator.comparingLong((ComponentCatalog.Entry e) -> e.updatedAtMs == null ? 0 : e.updatedAtMs).reversed();
        Comparator<ComponentCatalog.Entry> result = switch (sort == null ? "RECENT" : sort.toUpperCase(Locale.ROOT)) {
            case "NAME" -> names;
            case "CATEGORY" -> Comparator.comparing((ComponentCatalog.Entry e) -> e.category == null ? "ZZZ" : e.category.name()).thenComparing(names);
            case "LOADED" -> Comparator.comparingLong(lastLoaded).reversed().thenComparing(recent).thenComparing(names);
            case "TAG_MATCH" -> Comparator.comparingInt(score).reversed().thenComparing(recent).thenComparing(names);
            default -> recent.thenComparing(names);
        };
        return result.thenComparing(e -> e.id);
    }
}
