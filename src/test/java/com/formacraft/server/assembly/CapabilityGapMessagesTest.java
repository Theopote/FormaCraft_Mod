package com.formacraft.server.assembly;

import com.formacraft.common.llm.dto.CapabilityGap;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class CapabilityGapMessagesTest {

    @Test
    void formatsBilingualMessageWithSuggestions() {
        CapabilityGap gap = new CapabilityGap(
                "E_CONN_UNKNOWN_PORT",
                "from port Tower.bad_port not found",
                "components[0].params.assembly.graph.connections[0].from",
                List.of("Use top_center instead of bad_port", "Prefer preset spiral_watchtower")
        );
        String msg = CapabilityGapMessages.formatPlayerMessage(gap);
        assertTrue(msg.contains("【ASSEMBLY 能力缺口】"));
        assertTrue(msg.contains("连接端口无效"));
        assertTrue(msg.contains("[Capability gap] E_CONN_UNKNOWN_PORT"));
        assertTrue(msg.contains("建议："));
    }

    @Test
    void handlesNullGap() {
        String msg = CapabilityGapMessages.formatPlayerMessage(null);
        assertTrue(msg.contains("【ASSEMBLY 能力缺口】"));
        assertTrue(msg.contains("[Capability gap]"));
    }
}
