package com.formacraft.server.compiler;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RequestIntentBaselineTest {
    @TestFactory
    List<DynamicTest> sharedKeywordBaseline() throws IOException {
        try (var input = getClass().getResourceAsStream("/intent_baseline_v1.json")) {
            assertNotNull(input, "Missing shared Python intent baseline");
            var fixture = JsonParser.parseReader(new InputStreamReader(input, StandardCharsets.UTF_8))
                    .getAsJsonObject();
            List<DynamicTest> tests = new ArrayList<>();
            for (var row : fixture.getAsJsonArray("cases")) {
                JsonObject testCase = row.getAsJsonObject();
                if (testCase.has("llm_declared")) continue;
                tests.add(DynamicTest.dynamicTest(testCase.get("id").getAsString(), () -> {
                    String prompt = testCase.get("prompt").getAsString();
                    var intent = RequestIntentKeywordExtractor.extractKeyword(prompt);
                    for (String key : List.of("building_class", "tower_subtype", "composition", "roof_type")) {
                        if (testCase.has(key)) {
                            assertEquals(testCase.get(key).getAsString(), intent.get(key), key);
                        }
                    }
                    var forbids = (List<?>) intent.get("forbids");
                    if (testCase.has("forbids_contains")) {
                        assertTrue(forbids.contains(testCase.get("forbids_contains").getAsString()));
                    }
                    if (testCase.has("forbids_excludes")) {
                        assertFalse(forbids.contains(testCase.get("forbids_excludes").getAsString()));
                    }
                    if (testCase.has("roof_forbids_contains")) {
                        var roofForbids = (List<?>) intent.get("roof_forbids");
                        String expected = testCase.get("roof_forbids_contains").getAsString();
                        assertTrue(roofForbids.contains(expected), () -> "Missing roof exclusion: " + expected);
                    }
                    if (testCase.has("materials_wall")) {
                        var materials = (Map<?, ?>) intent.get("materials");
                        assertEquals(testCase.get("materials_wall").getAsString(), materials.get("wall"), "materials.wall");
                    }
                    if (testCase.has("footprint")) {
                        var footprint = testCase.getAsJsonArray("footprint");
                        assertEquals(Map.of("width", footprint.get(0).getAsInt(),
                                "depth", footprint.get(1).getAsInt()), intent.get("footprint"), "footprint");
                    }
                }));
            }
            assertFalse(tests.isEmpty(), "Shared fixture has no keyword-only cases");
            return tests;
        }
    }
}
