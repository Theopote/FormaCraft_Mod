package com.formacraft.common.orchestrator;

import java.util.List;

/**
 * Python /build 在 {@code kind=clarification} 时返回的追问内容。
 */
public record ClarificationResponse(
        String messageZh,
        List<String> questions,
        String reason,
        String sessionId
) {}
