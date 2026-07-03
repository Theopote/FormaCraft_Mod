package com.formacraft.ai.prompt;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * RAG memory retrieval and prompt block assembly.
 */
final class PromptMemorySections {
    private PromptMemorySections() {}

    static MemoryContext retrieveMemory(PromptContext ctx) {
        if (ctx == null) {
            return null;
        }

        com.formacraft.server.memory.MemoryManager memoryManager;
        try {
            memoryManager = com.formacraft.server.build.BuildExecutionService.getInstance().getMemoryManager();
        } catch (Exception e) {
            return null;
        }

        if (memoryManager == null) {
            return null;
        }

        Set<com.formacraft.server.memory.ProjectMemory> result = new LinkedHashSet<>();

        try {
            com.formacraft.common.buildcontext.BuildContext bc =
                com.formacraft.client.buildcontext.BuildContextResolver.resolve(false);
            if (bc != null && bc.origin != null) {
                result.addAll(memoryManager.findAtPosition(bc.origin));

                com.formacraft.server.memory.ProjectMemory nearest =
                    memoryManager.findNearest(bc.origin, 32.0);
                if (nearest != null) {
                    result.add(nearest);
                }
            }
        } catch (Exception e) {
            // client or uninitialized
        }

        Set<String> keywords = extractKeywords(ctx.userMessage);
        if (!keywords.isEmpty()) {
            for (String keyword : keywords) {
                if (keyword.length() >= 2) {
                    result.addAll(memoryManager.searchContains(keyword));
                }
            }
        }

        try {
            com.formacraft.common.buildcontext.BuildContext bc =
                com.formacraft.client.buildcontext.BuildContextResolver.resolve(false);
            if (bc != null && bc.origin != null) {
                com.formacraft.server.memory.ProjectMemory farNearest =
                    memoryManager.findNearest(bc.origin, 64.0);
                if (farNearest != null) {
                    result.add(farNearest);
                }

                String lowerMessage = (ctx.userMessage != null) ? ctx.userMessage.toLowerCase() : "";
                if (lowerMessage.contains("house") || lowerMessage.contains("房子") || lowerMessage.contains("住宅")) {
                    result.addAll(memoryManager.searchContains("house"));
                    result.addAll(memoryManager.searchContains("residential"));
                }
                if (lowerMessage.contains("tower") || lowerMessage.contains("塔") || lowerMessage.contains("楼")) {
                    result.addAll(memoryManager.searchContains("tower"));
                }
                if (lowerMessage.contains("castle") || lowerMessage.contains("城堡")) {
                    result.addAll(memoryManager.searchContains("castle"));
                }
                if (lowerMessage.contains("temple") || lowerMessage.contains("庙") || lowerMessage.contains("寺")) {
                    result.addAll(memoryManager.searchContains("temple"));
                }
            }
        } catch (Exception e) {
            // ignore
        }

        List<com.formacraft.server.memory.ProjectMemory> top =
            result.stream().limit(5).toList();

        if (top.isEmpty()) {
            return null;
        }

        return new MemoryContext(top, summarize(top));
    }

    static String memoryContextBlock(MemoryContext memoryContext) {
        if (memoryContext == null || memoryContext.isEmpty()) {
            return "";
        }
        return memoryContext.summary;
    }

    private static Set<String> extractKeywords(String input) {
        Set<String> keys = new HashSet<>();

        if (input == null || input.trim().isEmpty()) {
            return keys;
        }

        String[] tokens = input.toLowerCase().split("[^a-zA-Z0-9\\u4e00-\\u9fa5]+");

        for (String t : tokens) {
            if (t.length() >= 2) {
                keys.add(t);
            }
        }

        return keys;
    }

    private static String summarize(List<com.formacraft.server.memory.ProjectMemory> buildings) {
        if (buildings == null || buildings.isEmpty()) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("\n=== WORLD MEMORY CONTEXT (CRITICAL - USE FOR REFERENCE) ===\n");
        sb.append("The following buildings already exist near the build location.\n");
        sb.append("IMPORTANT: Use these as reference to ensure architectural consistency, style coherence, and spatial relationships.\n");
        sb.append("Consider: similar materials, complementary styles, appropriate scale, and functional relationships.\n\n");

        for (com.formacraft.server.memory.ProjectMemory b : buildings) {
            sb.append("- Building: ").append(b.getName() != null ? b.getName() : "Unnamed").append("\n");
            sb.append("  UUID: ").append(b.getUuid()).append("\n");

            if (b.getDescription() != null && !b.getDescription().isEmpty()) {
                sb.append("  Description: ").append(b.getDescription()).append("\n");
            }

            if (b.getTags() != null && !b.getTags().isEmpty()) {
                sb.append("  Tags: ").append(String.join(", ", b.getTags())).append("\n");
            }

            if (b.getBounds() != null) {
                com.formacraft.server.memory.ProjectMemory.SpatialBounds bounds = b.getBounds();
                if (bounds.getMin() != null && bounds.getMax() != null) {
                    int[] min = bounds.getMin();
                    int[] max = bounds.getMax();
                    sb.append("  Bounds: (")
                      .append(min[0]).append(",").append(min[1]).append(",").append(min[2])
                      .append(") → (")
                      .append(max[0]).append(",").append(max[1]).append(",").append(max[2])
                      .append(")\n");
                }
            }

            if (b.getBounds() != null && b.getBounds().getAnchors() != null && !b.getBounds().getAnchors().isEmpty()) {
                sb.append("  Anchors:\n");
                for (var e : b.getBounds().getAnchors().entrySet()) {
                    int[] coords = e.getValue();
                    sb.append("    - ")
                      .append(e.getKey())
                      .append(": (")
                      .append(coords[0]).append(",")
                      .append(coords[1]).append(",")
                      .append(coords[2]).append(")\n");
                }
            }

            if (b.getGeneData() != null) {
                com.formacraft.common.model.build.BuildingSpec spec = b.getGeneData();
                sb.append("  Gene Summary: ");
                if (spec.getType() != null) {
                    sb.append("type=").append(spec.getType().name()).append(", ");
                }
                if (spec.getStyle() != null) {
                    sb.append("style=").append(spec.getStyle().name()).append(", ");
                }
                if (spec.getHeight() > 0) {
                    sb.append("height=").append(spec.getHeight());
                }
                sb.append("\n");
            }

            sb.append("\n");
        }

        sb.append("IMPORTANT:\n");
        sb.append("- You MUST treat the above buildings as existing structures.\n");
        sb.append("- Only modify them if the user explicitly requests changes.\n");
        sb.append("- When modifying existing buildings, preserve their core identity and style.\n");
        sb.append("- Use anchors to reference specific parts of buildings (e.g., \"main_entrance\").\n");
        sb.append("\n");

        return sb.toString();
    }
}
