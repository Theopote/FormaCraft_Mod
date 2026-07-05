package com.formacraft.common.component;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ComponentFeatureFlagsTest {

    @Test
    void semanticSkinDefaultsFalseWithoutStyleHint() {
        assertFalse(ComponentFeatureFlags.resolveSemanticSkin(Map.of(), null, "semantic_skin"));
    }

    @Test
    void semanticSkinDefaultsTrueWhenStyleProvided() {
        assertTrue(ComponentFeatureFlags.resolveSemanticSkin(
                Map.of("semantic_style_id", "MEDIEVAL_CASTLE"), null, "semantic_skin"));
    }

    @Test
    void semanticSkinRespectsExplicitFalse() {
        assertFalse(ComponentFeatureFlags.resolveSemanticSkin(
                Map.of("semantic_skin", false, "semantic_style_id", "DEFAULT"), "DEFAULT", "semantic_skin"));
    }
}
