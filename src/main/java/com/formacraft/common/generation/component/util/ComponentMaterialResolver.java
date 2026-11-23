package com.formacraft.common.generation.component.util;

import com.formacraft.common.compiler.semantic.SemanticComponent;
import com.formacraft.common.palette.component.PaletteLibrary;
import com.formacraft.common.palette.dynamic.DynamicPaletteResolver;
import com.formacraft.common.semantic.SemanticPart;

import java.util.Map;

/**
 * Shared material resolution for secondary component generators.
 * <p>
 * Priority: explicit params → {@link DynamicPaletteResolver} / style palette → technical default.
 * Does not invent architectural style; only applies already-authored materials or style_attributes.
 */
public final class ComponentMaterialResolver {
    private ComponentMaterialResolver() {}

    public static String resolve(
            SemanticComponent semantic,
            SemanticPart part,
            String fallback,
            String... paramKeys) {
        Map<String, Object> params = semantic != null && semantic.source() != null
                ? semantic.source().params() : null;
        String explicit = ComponentParamParsers.stringOrNull(params, paramKeys);
        if (explicit != null) {
            String mapped = DynamicPaletteResolver.mapMaterialToBlock(explicit);
            if (mapped != null && !mapped.isBlank()) return mapped;
            return explicit.contains(":") ? explicit : "minecraft:" + explicit;
        }
        String style = semantic != null && semantic.styleProfile() != null && !semantic.styleProfile().isBlank()
                ? semantic.styleProfile() : "DEFAULT";
        var attrs = semantic != null ? semantic.styleAttributes() : null;
        String resolved = PaletteLibrary.resolveBlock(part, style, attrs);
        if (resolved != null && !resolved.isBlank()) return resolved;
        return fallback;
    }
}
