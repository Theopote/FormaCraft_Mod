package com.formacraft.server.orchestrator;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.formacraft.common.model.request.FormaRequest;
import com.formacraft.common.orchestrator.AiPlanResult;
import com.formacraft.server.dsl.FbdRepairLoop;
import com.sun.net.httpserver.HttpServer;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class FbdRepairHttpTest {
    @Test void buildResponsePassesThroughJavaDiagnosticHttpPatchAndRevalidation() throws Exception {
        var json=new ObjectMapper();
        var plan=(ObjectNode)json.readTree(Files.readString(Path.of("src/main/resources/assets/formacraft/fbd/examples/repeated_pavilions.llmplan.json")));
        String path="/components/0/params/fbd_program/functions/0/body/1/params/type";
        ((ObjectNode)plan.at(path.substring(0,path.lastIndexOf('/')))).put("type","BANANA"); plan.put("kind","llmplan");
        var repairs=new AtomicInteger(); var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/build",exchange->{
            var body=json.writeValueAsBytes(plan); exchange.sendResponseHeaders(200,body.length);
            try(var out=exchange.getResponseBody()){out.write(body);}
        });
        server.createContext("/fbd/repair",exchange->{
            var received=json.readTree(exchange.getRequestBody());
            assertEquals("E_FBD_INVALID_ENUM",received.at("/diagnostic/code").asText());
            assertEquals("roof",received.at("/diagnostic/details/node").asText());
            assertEquals(path,received.at("/diagnostic/path").asText()); assertEquals(1,received.path("round").asInt());
            repairs.incrementAndGet();
            byte[] body=("{\"path\":\""+path+"\",\"value\":\"FLAT\"}").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200,body.length);try(var out=exchange.getResponseBody()){out.write(body);}
        });
        server.start();
        try {
            var request=new FormaRequest(); request.setPlayerPos(new BlockPos(0,64,0)); request.setUserMessage("使用 FBD 建造三个亭子");
            var result=new OrchestratorClient("http://127.0.0.1:"+server.getAddress().getPort()).requestAiPlan(request).get(10,TimeUnit.SECONDS);
            var repaired=assertInstanceOf(AiPlanResult.LlmPlan.class,result).plan();
            assertEquals(1,repairs.get()); assertFalse(repaired.hasCapabilityGap()); assertNull(FbdRepairLoop.inspect(repaired));
        } finally { server.stop(0); }
    }
}
