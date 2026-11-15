package com.formacraft.common.model.blueprint;

import com.formacraft.common.json.JsonUtil;
import com.formacraft.common.model.build.BuildingSpec;
import com.formacraft.common.model.build.BuildingType;
import com.formacraft.common.model.city.CitySpec;
import com.formacraft.common.model.composite.CompositeSpec;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Historical blueprint codec fixtures: each type FormaCraftCommands load/save still supports.
 * Does not delete codec branches; proves round-trip JSON for BuildingSpec / CitySpec / CompositeSpec.
 */
class BlueprintCodecRoundTripTest {

    @ParameterizedTest
    @ValueSource(strings = {"BuildingSpec", "CitySpec", "CompositeSpec"})
    void blueprintEnvelopeRoundTripsForEachLegacyType(String type) {
        Blueprint bp = new Blueprint();
        bp.setType(type);
        bp.setName("fixture_" + type.toLowerCase());
        bp.setDescription("PR-3 historical fixture");
        bp.setFormatVersion(1);
        bp.setData(JsonParser.parseString(sampleDataJson(type)));

        String json = JsonUtil.toJson(bp);
        Blueprint loaded = JsonUtil.fromJson(json, Blueprint.class);
        assertNotNull(loaded);
        assertEquals(type, loaded.getType());
        assertEquals(bp.getName(), loaded.getName());
        assertEquals(1, loaded.getFormatVersion());
        assertNotNull(loaded.getData());

        String dataJson = loaded.getData().toString();
        switch (type) {
            case "BuildingSpec" -> {
                BuildingSpec spec = JsonUtil.fromJson(dataJson, BuildingSpec.class);
                assertNotNull(spec);
                assertEquals(BuildingType.HOUSE, spec.getType());
            }
            case "CitySpec" -> {
                CitySpec city = JsonUtil.fromJson(dataJson, CitySpec.class);
                assertNotNull(city);
                assertEquals("fixture_city", city.getCityName());
            }
            case "CompositeSpec" -> {
                CompositeSpec composite = JsonUtil.fromJson(dataJson, CompositeSpec.class);
                assertNotNull(composite);
                assertNotNull(composite.getStructures());
                assertFalse(composite.getStructures().isEmpty());
            }
            default -> fail("unexpected type");
        }
    }

    @Test
    void unknownTypeStillDeserializesEnvelope() {
        Blueprint bp = new Blueprint();
        bp.setType("UnknownFutureType");
        bp.setName("x");
        bp.setData(JsonParser.parseString("{}"));
        Blueprint loaded = JsonUtil.fromJson(JsonUtil.toJson(bp), Blueprint.class);
        assertEquals("UnknownFutureType", loaded.getType());
    }

    private static String sampleDataJson(String type) {
        return switch (type) {
            case "BuildingSpec" -> """
                    {"type":"HOUSE","height":5,"floors":1}
                    """;
            case "CitySpec" -> """
                    {"cityName":"fixture_city","structures":[]}
                    """;
            case "CompositeSpec" -> """
                    {"structures":[{"type":"HOUSE","offset":{"x":0,"y":0,"z":0},"spec":{"type":"HOUSE","height":4}}]}
                    """;
            default -> "{}";
        };
    }
}
