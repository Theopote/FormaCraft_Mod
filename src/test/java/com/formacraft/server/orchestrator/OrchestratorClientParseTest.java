package com.formacraft.server.orchestrator;

import com.formacraft.common.llm.dto.LlmPlan;
import com.formacraft.common.model.build.BuildingSpec;
import com.formacraft.common.model.build.BuildingType;
import com.formacraft.common.model.city.CitySpec;
import com.formacraft.common.model.composite.CompositeSpec;
import com.formacraft.common.orchestrator.AiPlanResult;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class OrchestratorClientParseTest {

    @Test
    void parseLlmPlanResponse() {
        String body = """
                {
                  "mode": "layout",
                  "anchor": {"x": 0, "y": 64, "z": 0},
                  "components": []
                }
                """;
        AiPlanResult result = OrchestratorClient.parseAiPlanResponse(body);
        assertInstanceOf(AiPlanResult.LlmPlan.class, result);
        assertEquals(LlmPlan.Mode.layout, ((AiPlanResult.LlmPlan) result).plan().mode());
    }

    @Test
    void parseBuildingSpecResponse() {
        String body = """
                {
                  "type": "HOUSE",
                  "style": "DEFAULT",
                  "footprint": {"shape": "rectangle", "width": 8, "depth": 10},
                  "height": 6,
                  "floors": 1,
                  "materials": {},
                  "features": {}
                }
                """;
        AiPlanResult result = OrchestratorClient.parseAiPlanResponse(body);
        assertInstanceOf(AiPlanResult.BuildingSpec.class, result);
        BuildingSpec spec = ((AiPlanResult.BuildingSpec) result).spec();
        assertEquals(BuildingType.HOUSE, spec.getType());
    }

    @Test
    void parseCompositeSpecResponse() {
        String body = """
                {
                  "structures": [
                    {
                      "type": "offset",
                      "spec": {
                        "type": "HOUSE",
                        "style": "DEFAULT",
                        "footprint": {"shape": "rectangle", "width": 5, "depth": 5},
                        "height": 5,
                        "floors": 1,
                        "materials": {},
                        "features": {}
                      }
                    }
                  ]
                }
                """;
        AiPlanResult result = OrchestratorClient.parseAiPlanResponse(body);
        assertInstanceOf(AiPlanResult.CompositeSpec.class, result);
        CompositeSpec composite = ((AiPlanResult.CompositeSpec) result).spec();
        assertNotNull(composite.getStructures());
        assertFalse(composite.getStructures().isEmpty());
    }

    @Test
    void parseCitySpecResponse() {
        String body = """
                {
                  "cityName": "Testville",
                  "zones": [
                    {"name": "center", "type": "plaza", "radius": 12, "center": {"x": 0, "y": 64, "z": 0}}
                  ]
                }
                """;
        AiPlanResult result = OrchestratorClient.parseAiPlanResponse(body);
        assertInstanceOf(AiPlanResult.CitySpec.class, result);
        CitySpec city = ((AiPlanResult.CitySpec) result).spec();
        assertEquals("Testville", city.getCityName());
    }
}
