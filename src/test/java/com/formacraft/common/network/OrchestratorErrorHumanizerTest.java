package com.formacraft.common.network;

import com.formacraft.common.model.request.FormaRequest;
import org.junit.jupiter.api.Test;

import java.net.ConnectException;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.*;

class OrchestratorErrorHumanizerTest {

    @Test
    void humanizeConnectionRefused() {
        Throwable ex = new CompletionException(new ConnectException("Connection refused"));
        String msg = OrchestratorErrorHumanizer.humanize("BuildingSpec", new FormaRequest(), ex);
        assertTrue(msg.contains("无法连接到后端服务"));
        assertTrue(msg.contains("连接被拒绝"));
    }

    @Test
    void humanizeQuotaError() {
        String msg = OrchestratorErrorHumanizer.humanize(
                "LlmPlan",
                new FormaRequest(),
                new RuntimeException("error code: 429 insufficient_quota")
        );
        assertTrue(msg.contains("额度/配额不足"));
        assertTrue(msg.startsWith("LLM 调用失败"));
    }

    @Test
    void humanizeAnthropicCreditBalance() {
        String msg = OrchestratorErrorHumanizer.humanize(
                "AiPlan",
                new FormaRequest(),
                new RuntimeException(
                        "Orchestrator returned status: 502 body={\"detail\":\"LLM call failed for LlmPlan: "
                                + "Error code: 400 - credit balance is too low to access the Anthropic API\"}"
                )
        );
        assertTrue(msg.contains("Anthropic（Claude）账户余额不足"));
        assertFalse(msg.contains("后端请求失败"));
    }

    @Test
    void humanize403Forbidden() {
        String msg = OrchestratorErrorHumanizer.humanize(
                "LlmPlan",
                new FormaRequest(),
                new RuntimeException("Error code: 403 - permission denied")
        );
        assertTrue(msg.contains("403"));
        assertTrue(msg.contains("访问被拒绝"));
    }

    @Test
    void rootCauseUnwrapsCompletionException() {
        RuntimeException root = new RuntimeException("inner");
        Throwable wrapped = new CompletionException(wrappedAgain(root));
        assertSame(root, OrchestratorErrorHumanizer.rootCause(wrapped));
    }

    private static Throwable wrappedAgain(Throwable t) {
        return new java.util.concurrent.ExecutionException(t);
    }
}
