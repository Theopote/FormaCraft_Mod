package com.formacraft.common.archetype;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LandmarkAliasMatcherTest {

    private static final List<String> GOTHIC_ALIASES = List.of(
            "哥特大教堂", "gothic cathedral", "notre dame", "cologne cathedral", "chartres"
    );

    @Test
    void rejectsSagradaFamiliaViaBroadCathedralAlias() {
        assertNull(LandmarkAliasMatcher.matchIntent(
                "生成圣家族大教堂",
                "gothic_cathedral",
                List.of("大教堂", "哥特大教堂")
        ));
    }

    @Test
    void acceptsExplicitGothicCathedralName() {
        LandmarkAliasMatcher.Match match = LandmarkAliasMatcher.matchIntent(
                "哥特大教堂",
                "gothic_cathedral",
                GOTHIC_ALIASES
        );
        assertNotNull(match);
        assertTrue(match.explicit());
        assertEquals("哥特大教堂", match.matchedAlias());
    }

    @Test
    void rejectsFushimiInariViaBroadShrineAlias() {
        assertNull(LandmarkAliasMatcher.matchIntent(
                "建造伏见稻荷神社",
                "japanese_shrine",
                List.of("日本神社", "神社", "torii")
        ));
    }

    @Test
    void acceptsExplicitJiangnanPhrase() {
        LandmarkAliasMatcher.Match match = LandmarkAliasMatcher.matchIntent(
                "江南水乡",
                "jiangnan_water_town",
                List.of("江南水乡", "江南水镇", "water town")
        );
        assertNotNull(match);
        assertTrue(match.explicit());
    }

    @Test
    void rejectsDisneyCastleViaBroadCastleAlias() {
        assertNull(LandmarkAliasMatcher.matchIntent(
                "迪士尼城堡",
                "castle_compound",
                List.of("城堡", "中世纪城堡")
        ));
    }

    @Test
    void acceptsMedievalCastlePhrase() {
        LandmarkAliasMatcher.Match match = LandmarkAliasMatcher.matchIntent(
                "中世纪城堡",
                "castle_compound",
                List.of("城堡", "中世纪城堡")
        );
        assertNotNull(match);
        assertEquals("中世纪城堡", match.matchedAlias());
    }

    @Test
    void lenientMatchFlagsApproximate() {
        LandmarkAliasMatcher.Match match = LandmarkAliasMatcher.matchIntentLenient(
                "圣家族大教堂",
                "gothic_cathedral",
                List.of("大教堂", "哥特大教堂")
        );
        assertNotNull(match);
        assertFalse(match.explicit());
    }
}
