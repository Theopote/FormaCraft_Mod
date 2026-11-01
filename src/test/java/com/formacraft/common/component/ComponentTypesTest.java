package com.formacraft.common.component;

import com.formacraft.common.component.placement.AttachmentType;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ComponentTypesTest {
    @Test void allCoarseCategoriesHavePresetsAndRoofDetailsHaveDistinctSuggestions() {
        assertEquals(ComponentTypes.PRESETS.size(), ComponentTypes.PRESETS.stream().map(p -> p.type().key()).distinct().count());
        for (var category : ComponentCategory.values()) assertEquals(category, ComponentTypes.base(category).category());
        assertEquals(AttachmentType.ROOF_SURFACE, ComponentTypes.preset("formacraft:chimney").attachment());
        assertEquals(AttachmentType.ROOF_RIDGE, ComponentTypes.preset("formacraft:ridge").attachment());
        assertEquals(ComponentCategory.COLUMN, ComponentTypes.preset("formacraft:decorative_column").category());
    }
    @Test void customIdentityNormalizesSpellingButPreservesDisplayCaseAndNeverBecomesAFileName() {
        var first = ComponentTypes.custom(" Ｍｙ  Item "); var second = ComponentTypes.custom("my item");
        assertEquals(first.key(), second.key()); assertEquals("My Item", first.label());
        assertTrue(first.key().matches("custom:[0-9a-f]{64}"));
        assertNull(ComponentTypes.error(first, ComponentCategory.GENERIC));
        assertNull(ComponentTypes.error(first, ComponentCategory.RAILING));
    }
    @Test void invalidCustomNamesAndForgedKeysAreRejected() {
        for (var name : List.of("", " ", "a".repeat(65), "line\nbreak", "\uD800"))
            assertThrows(IllegalArgumentException.class, () -> ComponentTypes.custom(name));
        var type = ComponentTypes.custom("飞檐挂落");
        assertNotNull(ComponentTypes.error(new ComponentTypes.Ref(type.key(), "另一类型"), ComponentCategory.GENERIC));
        assertNotNull(ComponentTypes.error(new ComponentTypes.Ref("unknown:window", "窗"), ComponentCategory.WINDOW));
    }
    @Test void presetKeysRequireCorrectBaseButDisplayLabelsCanBeLocalized() {
        assertNotNull(ComponentTypes.error(ComponentTypes.preset("formacraft:door").type(), ComponentCategory.WINDOW));
        assertNull(ComponentTypes.error(new ComponentTypes.Ref("formacraft:door", "Door"), ComponentCategory.DOOR));
        assertTrue(ComponentTypes.matches("门", null, ComponentCategory.DOOR));
    }
    @Test void classificationChangesDoNotCreateDifferentContentIdentity() {
        var first = new ComponentDefinition(); first.id = "first";
        first.type = ComponentTypes.custom("花架");
        var second = new ComponentDefinition(); second.id = "second";
        second.type = ComponentTypes.custom("组合支架");
        assertEquals(ComponentContentIdentity.Match.EXACT_CONTENT, ComponentContentIdentity.compare(first, second));
        assertEquals(ComponentContentIdentity.fingerprints(first), ComponentContentIdentity.fingerprints(second));
    }
}
