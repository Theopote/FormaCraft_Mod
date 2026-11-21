package com.formacraft.server.orchestrator;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.formacraft.common.llm.dto.CapabilityGap;
import com.formacraft.common.llm.dto.LlmPlanTestFixtures;
import com.formacraft.common.model.request.FormaRequest;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class FlatRoofRepairHttpTest {
    @Test void acceptsOneIntegerProposalAndRejectsCoercionAndAmbiguousJson() throws Exception {
        var response = new AtomicReference<String>();
        var received = new AtomicReference<com.fasterxml.jackson.databind.JsonNode>();
        var mapper = new ObjectMapper();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/components/flat-roof/repair", exchange -> {
            received.set(mapper.readTree(exchange.getRequestBody()));
            byte[] bytes = response.get().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (var out = exchange.getResponseBody()) { out.write(bytes); }
        });
        server.start();
        try {
            var client = new OrchestratorClient("http://127.0.0.1:" + server.getAddress().getPort());
            var req = new FormaRequest();
            req.setUserMessage("云纹折带馆，保留屋顶材料");
            var plan = LlmPlanTestFixtures.builder().build();
            var gap = new CapabilityGap("E_FLAT_ROOF_HOST_COVERAGE", "", "components[]", List.of(),
                    Map.of("roof_sources", List.of("roof-a")));
            String prefix = "{\"component_id\":\"roof-a\",\"field\":\"dimensions.width\",\"value\":";
            response.set(prefix + "9}");
            var edit = client.requestFlatRoofRepair(req, plan, gap).get(5, TimeUnit.SECONDS);
            assertEquals(9, edit.value());
            assertEquals(1, received.get().path("round").asInt());
            assertEquals("roof-a", received.get().at("/diagnostic/details/roof_sources/0").asText());
            for (String invalid : List.of(prefix + "\"9\"}", prefix + "9.5}", prefix + "true}",
                    prefix + "9,\"value\":10}", prefix + "9}{}", prefix.replace("\"roof-a\"", "4") + "9}")) {
                response.set(invalid);
                assertThrows(ExecutionException.class,
                        () -> client.requestFlatRoofRepair(req, plan, gap).get(5, TimeUnit.SECONDS), invalid);
            }
        } finally {
            server.stop(0);
        }
    }
}
