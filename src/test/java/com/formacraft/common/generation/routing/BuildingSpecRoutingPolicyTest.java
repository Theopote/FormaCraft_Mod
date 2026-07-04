package com.formacraft.common.generation.routing;

import com.formacraft.common.model.build.BuildingSpec;
import com.formacraft.common.model.request.FormaRequest;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BuildingSpecRoutingPolicyTest {

    @Test
    void shouldSkipLlmPlanPreview_onlyWhenOutputFormatIsBuildingSpec() {
        FormaRequest req = new FormaRequest();
        req.setUserMessage("建一座四合院");
        assertFalse(BuildingSpecRoutingPolicy.shouldSkipLlmPlanPreview(req));

        req.setOutputFormat("buildingspec");
        assertTrue(BuildingSpecRoutingPolicy.shouldSkipLlmPlanPreview(req));

        req.setOutputFormat("llmplan");
        assertFalse(BuildingSpecRoutingPolicy.shouldSkipLlmPlanPreview(req));
    }

    @Test
    void isMingQingCourtyardIntent_requiresPeriodAndCourtyardTerms() {
        assertFalse(BuildingSpecRoutingPolicy.isMingQingCourtyardIntent("build a courtyard"));
        assertFalse(BuildingSpecRoutingPolicy.isMingQingCourtyardIntent("ming dynasty temple"));
        assertTrue(BuildingSpecRoutingPolicy.isMingQingCourtyardIntent("明清官式四合院"));
        assertTrue(BuildingSpecRoutingPolicy.isMingQingCourtyardIntent("landmark:mingqing_courtyard"));
    }

    @Test
    void forcesSingleBuildingSpec_notTriggeredByIntentAlone() {
        assertFalse(BuildingSpecRoutingPolicy.forcesSingleBuildingSpec(
                "明清官式四合院", null));
    }

    @Test
    void forcesSingleBuildingSpec_whenSpecHasExplicitForceFlag() {
        BuildingSpec spec = new BuildingSpec();
        Map<String, Object> extra = new HashMap<>();
        extra.put(BuildingSpecRoutingPolicy.EXTRA_FORCE_BUILDING_SPEC_PATH, true);
        spec.setExtra(extra);

        assertTrue(BuildingSpecRoutingPolicy.forcesSingleBuildingSpec("", spec));
    }

    @Test
    void applySpecDefaults_setsTemplateButNotForceFlag() {
        FormaRequest req = new FormaRequest();
        req.setUserMessage("明清官式四合院");
        BuildingSpec spec = new BuildingSpec();

        assertTrue(BuildingSpecRoutingPolicy.applySpecDefaults(spec, req));
        assertEquals(
                BuildingSpecRoutingPolicy.TEMPLATE_MINGQING_COURTYARD,
                spec.getExtra().get("template")
        );
        assertFalse(Boolean.TRUE.equals(
                spec.getExtra().get(BuildingSpecRoutingPolicy.EXTRA_FORCE_BUILDING_SPEC_PATH)
        ));
    }
}
