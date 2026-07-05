package com.formacraft.ai.prompt;

/**
 * Structured JSON template and user-intent blocks for the final prompt.
 */
final class PromptTemplateSections {
    private PromptTemplateSections() {}

    static String structuredJsonTemplate(PromptContext ctx) {
        if (ctx == null) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("=== STRUCTURED JSON TEMPLATE ===\n");
        sb.append("Fill in the following JSON structure based on the constraints above:\n\n");

        // 获取 anchor
        com.formacraft.common.buildcontext.BuildContext bc = 
            com.formacraft.client.buildcontext.BuildContextResolver.resolve(false);
        int anchorX = (bc != null && bc.origin != null) ? bc.origin.getX() : 0;
        int anchorY = (bc != null && bc.origin != null) ? bc.origin.getY() : 0;
        int anchorZ = (bc != null && bc.origin != null) ? bc.origin.getZ() : 0;

        // 获取 facing
        String facing = (bc != null && bc.facing != null) ? bc.facing.name() : "SOUTH";

        // 获取 symmetry
        String symmetry = com.formacraft.ai.context.SymmetryContext.enabled() ? "MIRROR_X" : "NONE";

        // 获取 terrain strategy
        String terrainStrategy = (ctx.terrainPolicy != null && ctx.terrainPolicy.strategy != null)
                ? ctx.terrainPolicy.strategy.name() : "ADAPTIVE";

        // 获取 style profile
        String styleProfile = ctx.styleProfileId != null ? ctx.styleProfileId : "DEFAULT";

        // 获取 skeleton type
        String skeletonType = "LINEAR_PATH";
        if (ctx.pathSkeleton != null) {
            skeletonType = "LINEAR_PATH";
        }

        // 开始构建 JSON
        sb.append("{\n");
        sb.append("  \"mode\": \"").append(ctx.mode != null ? ctx.mode.name().toLowerCase() : "build").append("\",\n");
        sb.append("  \"style_profile\": \"").append(styleProfile).append("\",\n");
        sb.append("  \"style_attributes\": { \"wall_color\": null, \"roof_color\": null, \"accent_color\": null, ")
          .append("\"wall_material\": null, \"roof_material\": null, \"floor_material\": null, \"decorative_elements\": [] },\n");
        sb.append("  \"anchor\": { \"x\": ").append(anchorX).append(", \"y\": ").append(anchorY).append(", \"z\": ").append(anchorZ).append(" },\n");
        sb.append("  \"genome\": {\n");
        sb.append("    \"genomeVersion\": \"1.0\",\n");
        sb.append("    \"archetype\": { \"id\": \"generic\", \"confidence\": 0.0 },\n");
        sb.append("    \"topology\": { \"layout\": \"rectangular\", \"composition\": \"single\", \"axis\": \"none\", \"levels\": \"mixed\" },\n");
        sb.append("    \"structure\": { \"type\": \"hybrid\", \"massiveness\": 0.5, \"voidRatio\": 0.2, \"supports\": \"distributed\" },\n");
        sb.append("    \"form\": { \"repetition\": \"none\", \"progression\": \"uniform\", \"curvature\": \"straight\", \"rhythm\": \"regular\" },\n");
        sb.append("    \"symmetry\": { \"type\": \"none\", \"order\": null, \"mirror\": null },\n");
        sb.append("    \"modules\": [\"roof\", \"windows\"],\n");
        sb.append("    \"materials\": { \"primary\": \"stone\", \"secondary\": \"wood\", \"accent\": \"metal\", \"textureBias\": \"aged\" },\n");
        sb.append("    \"culturalStyle\": { \"region\": \"modern\", \"era\": \"modern\", \"keywords\": [] },\n");
        sb.append("    \"constraints\": { \"maxHeight\": null, \"respectTerrain\": true, \"insideSelectionOnly\": false },\n");
        sb.append("    \"aiHints\": { \"priority\": [], \"avoid\": [] }\n");
        sb.append("  },\n");
        sb.append("\n");
        sb.append("  \"global_constraints\": {\n");
        sb.append("    \"facing\": \"").append(facing).append("\",\n");
        sb.append("    \"symmetry\": \"").append(symmetry).append("\",\n");
        sb.append("    \"terrain_strategy\": \"").append(terrainStrategy).append("\"\n");
        sb.append("  },\n");
        sb.append("\n");
        sb.append("  \"layout\": {\n");
        sb.append("    \"skeleton_type\": \"").append(skeletonType).append("\",\n");
        sb.append("    \"path_based\": ").append(ctx.pathSkeleton != null ? "true" : "false").append(",\n");
        
        // Slots
        if (ctx.clusterLayout != null && ctx.clusterLayout.isValid()) {
            sb.append("    \"slots\": [\n");
            
            int slotIndex = 0;
            for (var slot : ctx.clusterLayout.slots) {
                if (slotIndex >= 20) break; // 限制输出数量
                
                // 解析 preset
                var preset = com.formacraft.common.cluster.zoning.ProgramPresetResolver.resolve(
                        ctx.styleProfileId, slot.program
                );
                
                // 计算相对 anchor 的位置
                int relX = slot.anchor.getX() - anchorX;
                int relY = slot.anchor.getY() - anchorY;
                int relZ = slot.anchor.getZ() - anchorZ;
                
                // 解析 facing
                String slotFacing = facing;
                if (slot.facing == com.formacraft.common.cluster.PathClusterLayout.Facing.LEFT_OF_PATH) {
                    slotFacing = "EAST"; // 简化：左侧建筑朝向路径（东）
                } else if (slot.facing == com.formacraft.common.cluster.PathClusterLayout.Facing.RIGHT_OF_PATH) {
                    slotFacing = "WEST"; // 简化：右侧建筑朝向路径（西）
                }
                
                sb.append("      {\n");
                sb.append("        \"slot_id\": \"slot_").append(String.format("%02d", slotIndex + 1)).append("\",\n");
                sb.append("        \"anchor\": { \"x\": ").append(relX).append(", \"y\": ").append(relY).append(", \"z\": ").append(relZ).append(" },\n");
                sb.append("        \"facing\": \"").append(slotFacing).append("\",\n");
                sb.append("        \"program\": \"").append(slot.program.name()).append("\",\n");
                sb.append("        \"component_preset_id\": \"").append(preset.id).append("\",\n");
                sb.append("        \"component_preset\": \"").append(escapeJsonString(preset.descriptionForPrompt)).append("\"\n");
                sb.append("      }");
                
                if (slotIndex < Math.min(20, ctx.clusterLayout.slots.size() - 1)) {
                    sb.append(",");
                }
                sb.append("\n");
                
                slotIndex++;
            }
            
            sb.append("    ]\n");
        } else {
            sb.append("    \"slots\": []\n");
        }
        
        sb.append("  },\n");
        sb.append("\n");
        sb.append("  \"components\": []\n");
        sb.append("}\n");
        
        sb.append("\n");
        sb.append("IMPORTANT:\n");
        sb.append("- Fill the \"components\" array with ComponentObject entries based on the component_preset for each slot.\n");
        sb.append("- Use semantic component types (MASS_MAIN, ENTRANCE, FACADE_WINDOWS, ASSEMBLY, PRIMITIVE, SIGNAGE, etc.), NOT block IDs.\n");
        sb.append("- All positions in components must be relative to the slot's anchor.\n");
        sb.append("- Every component must include relative_position and dimensions (width/depth/height > 0).\n");
        sb.append("- Origin conventions: MASS_* uses center unless params.anchor_mode=\"min_corner\"; TOWER uses center; facade/entrance/signage/roof/paving use min corner.\n");
        sb.append("- Respect the component_preset weights and densities when generating components.\n");
        sb.append("- Populate \"genome\" with topology/structure/form/material semantics to drive parameterized generation.\n");
        sb.append("- Use ComponentObject.params to express shape/plan/void/roof/setback/multi-mass intent instead of free-text features.\n");
        sb.append("- Slot anchors in layout.slots must be RELATIVE to plan.anchor (y usually 0); never repeat plan.anchor world coordinates.\n");
        sb.append("- For shapes beyond MASS/ROOF enums, use component_type ASSEMBLY with params.assembly, or top-level plan_program / PRIMITIVE.\n");
        sb.append("- Output ONLY valid JSON. No comments, no explanations.\n");
        
        return sb.toString();
    }

    /**
     * 转义 JSON 字符串（用于 component_preset 字段）
     */
    static String escapeJsonString(String text) {
        if (text == null) return "";
        return text.replace("\\", "\\\\")
                  .replace("\"", "\\\"")
                  .replace("\n", "\\n")
                  .replace("\r", "\\r")
                  .replace("\t", "\\t");
    }

    /**
     * User Intent（玩家原始描述）
     */
    static String userIntent(PromptContext ctx) {
        if (ctx == null || ctx.userMessage == null || ctx.userMessage.trim().isEmpty()) {
            return "";
        }

        return """
USER REQUEST:
""" + ctx.userMessage.trim() + "\n\n";
    }

}
