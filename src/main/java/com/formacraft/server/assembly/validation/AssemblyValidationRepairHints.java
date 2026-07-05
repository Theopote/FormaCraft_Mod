package com.formacraft.server.assembly.validation;

import java.util.List;
import java.util.StringJoiner;

/**
 * Formats validation issues for LLM repair prompts or server logs (Nodecraft-style repair hints).
 */
public final class AssemblyValidationRepairHints {

    private AssemblyValidationRepairHints() {}

    public static String formatForPrompt(List<AssemblyValidationIssue> issues) {
        if (issues == null || issues.isEmpty()) {
            return "";
        }
        StringJoiner joiner = new StringJoiner("\n");
        for (AssemblyValidationIssue issue : issues) {
            if (issue == null) {
                continue;
            }
            if (issue.severity() != AssemblyValidationIssue.Severity.ERROR) {
                continue;
            }
            joiner.add("- [" + issue.code() + "] " + issue.path() + ": " + issue.message());
        }
        String body = joiner.toString();
        if (body.isBlank()) {
            return "";
        }
        return """
                Fix the assembly JSON and retry:
                """ + body + """

                Rules:
                - Use exact component type ids (SHELL_BOX, SPLINE_SWEEP, …).
                - Connection endpoints must be "ComponentId.port" with ports listed in schema.
                - For spiral towers prefer preset: { "preset": "spiral_watchtower", "presetParams": { ... } }.
                """;
    }
}
