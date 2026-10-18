package com.formacraft.server.compiler;

import com.formacraft.common.llm.dto.Component;
import com.formacraft.common.llm.parser.LlmPlanParser;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.fail;

class StyleLogRoundTwoDumpTest {
    @BeforeAll
    static void bootstrap() {
        com.formacraft.test.MinecraftRegistryTestBootstrap.initialize();
    }

    @Test
    @SuppressWarnings("unchecked")
    void dump() throws Exception {
        try (var in = getClass().getResourceAsStream("/style_log_cases_round2/case_2.json")) {
            var plan = LlmPlanParser.parseAndValidate(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            Method prepare = ComponentPlanCompiler.class.getDeclaredMethod(
                    "prepareComponents", com.formacraft.common.llm.dto.LlmPlan.class, Map.class, boolean.class);
            prepare.setAccessible(true);
            Object prepared = prepare.invoke(null, plan, Map.of(), false);
            List<Component> components = (List<Component>) prepared.getClass().getRecordComponents()[0].getAccessor().invoke(prepared);
            StringBuilder sb = new StringBuilder();
            for (Component c : components) {
                if (c == null || !"ROOF".equalsIgnoreCase(c.componentType()) && !"MASS_MAIN".equalsIgnoreCase(c.componentType())) continue;
                sb.append(c.componentType()).append(" pos=").append(c.relativePosition())
                        .append(" dims=").append(c.dimensions())
                        .append(" rt=").append(c.params().get("roof_type"))
                        .append(" oh=").append(c.params().get("overhang"))
                        .append("; ");
            }
            fail("ROOFMASS=" + sb);
        }
    }
}