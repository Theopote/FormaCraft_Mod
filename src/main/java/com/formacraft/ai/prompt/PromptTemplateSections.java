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
        sb.append("- Use semantic component types (MASS_MAIN, ENTRANCE, FACADE_WINDOWS, SIGNAGE, etc.), NOT block IDs.\n");
        sb.append("- All positions in components must be relative to the slot's anchor.\n");
        sb.append("- Every component must include relative_position and dimensions (width/depth/height > 0).\n");
        sb.append("- Origin conventions: MASS_* uses center unless params.anchor_mode=\"min_corner\"; TOWER uses center; facade/entrance/signage/roof/paving use min corner.\n");
        sb.append("- Respect the component_preset weights and densities when generating components.\n");
        sb.append("- Populate \"genome\" with topology/structure/form/material semantics to drive parameterized generation.\n");
        sb.append("- Use ComponentObject.params to express shape/plan/void/roof/setback/multi-mass intent instead of free-text features.\n");
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
     * Zoning Block（功能分区提示）
     * <p>
     * K3 新增：告诉 AI 建筑功能分区信息
     * <p>
     * 关键设计原则：
     * - ✅ LLM 看到"规划意图"（功能分区）
     * - ❌ LLM 不决定 slot 坐标（坐标仍由布局算法给）
     */
    static String zoningBlock(PromptContext ctx) {
        if (ctx == null || ctx.clusterLayout == null || !ctx.clusterLayout.isValid() || 
            ctx.zoningProfile == null) {
            return "";
        }

        com.formacraft.common.cluster.PathClusterLayout layout = ctx.clusterLayout;
        com.formacraft.common.cluster.zoning.ZoningProfile profile = ctx.zoningProfile;

        StringBuilder sb = new StringBuilder();
        sb.append("ZONING:\n");
        sb.append("- basis: path\n");
        sb.append("- profile: ").append(profile.id).append("\n");
        
        // 规则摘要
        sb.append("- rules_summary:\n");
        for (var rule : profile.rules) {
            String sideStr = rule.sides() != null && !rule.sides().isEmpty()
                    ? rule.sides().toString() : "both";
            String laneStr = rule.laneIndex() != null 
                    ? "lane=" + rule.laneIndex() : "all-lanes";
            String labelStr = rule.requiredLabel() != null 
                    ? "label=" + rule.requiredLabel() : "";
            
            sb.append("  * t=").append(String.format("%.2f", rule.startT()))
              .append("~").append(String.format("%.2f", rule.endT()))
              .append(" ").append(sideStr).append(" ").append(laneStr);
            if (!labelStr.isEmpty()) {
                sb.append(" ").append(labelStr);
            }
            sb.append(" -> ").append(rule.program().name()).append("\n");
        }
        
        // 槽位信息（前 10 个作为示例）
        sb.append("- slots (sample):\n");
        int count = 0;
        for (var slot : layout.slots) {
            if (count >= 10) break;
            sb.append("  * {t:").append(String.format("%.2f", slot.t))
              .append(",side:").append(slot.side.name())
              .append(",lane:").append(slot.laneIndex)
              .append(",program:").append(slot.program.name()).append("}\n");
            count++;
        }
        
        sb.append("- IMPORTANT: Building functions (programs) are already determined by zoning rules\n");
        sb.append("- Your role: generate appropriate components for each program (shop fronts, stalls, residential balconies, industrial chimneys, etc.)\n");
        sb.append("- Style is controlled by StyleProfile (palette), but components should match the program\n");
        
        return sb.toString();
    }

    /**
     * Component Preset Block（组件预设提示）
     * <p>
     * K3.1 新增：告诉 AI 每个 Slot 的组件装配清单
     * <p>
     * 关键设计原则：
     * - ✅ LLM 看到"组件装配清单"（preset）
     * - ✅ LLM 根据 preset 生成具体组件（而不是自由发散）
     * - ❌ LLM 不决定组件类型（组件类型由 preset 决定）
     */
    static String componentPresetBlock(PromptContext ctx) {
        if (ctx == null || ctx.clusterLayout == null || !ctx.clusterLayout.isValid()) {
            return "";
        }

        // 如果已经有 zonedSlots，使用 zonedSlots
        if (ctx.zonedSlots != null && !ctx.zonedSlots.isEmpty()) {
            return buildZonedSlotsJson(ctx.zonedSlots);
        }

        // 否则从 clusterLayout 构建（需要解析 preset）
        return buildSlotsWithPresets(ctx);
    }

    /**
     * 构建带预设的槽位 JSON（从 ZonedSlot 列表）
     */
    static String buildZonedSlotsJson(java.util.List<com.formacraft.common.cluster.ZonedSlot> zonedSlots) {
        StringBuilder sb = new StringBuilder();
        sb.append("COMPONENT_PRESETS:\n");
        sb.append("\"slots\": [\n");
        
        for (int i = 0; i < zonedSlots.size(); i++) {
            com.formacraft.common.cluster.ZonedSlot s = zonedSlots.get(i);
            sb.append("  {\n");
            sb.append("    \"anchor\": [").append(s.anchor().getX()).append(",")
              .append(s.anchor().getY()).append(",").append(s.anchor().getZ()).append("],\n");
            sb.append("    \"t\": ").append(String.format(java.util.Locale.ROOT, "%.3f", s.t())).append(",\n");
            sb.append("    \"side\": \"").append(s.side().name()).append("\",\n");
            sb.append("    \"lane\": ").append(s.laneIndex()).append(",\n");
            sb.append("    \"facing\": \"").append(s.facing().name()).append("\",\n");
            sb.append("    \"program\": \"").append(s.program().name()).append("\",\n");
            sb.append("    \"component_preset_id\": \"").append(s.presetId()).append("\",\n");
            sb.append("    \"component_preset\": ").append(jsonString(s.presetText())).append("\n");
            sb.append("  }");
            if (i < zonedSlots.size() - 1) sb.append(",");
            sb.append("\n");
        }
        
        sb.append("]\n");
        sb.append("- IMPORTANT: Component types are already determined by preset\n");
        sb.append("- Your role: generate specific instances of these components (e.g., actual shop windows, signage designs, etc.)\n");
        sb.append("- Follow the preset weights and densities when placing components\n");
        
        return sb.toString();
    }

    /**
     * 构建带预设的槽位（从 clusterLayout 解析）
     */
    static String buildSlotsWithPresets(PromptContext ctx) {
        // 如果没有 zonedSlots，尝试从 clusterLayout 和 zoningProfile 构建
        if (ctx.clusterLayout == null || ctx.zoningProfile == null) {
            return "";
        }

        // 获取风格 ID（如果有）
        String styleId = ctx.styleProfileId != null ? ctx.styleProfileId : null;

        StringBuilder sb = new StringBuilder();
        sb.append("COMPONENT_PRESETS:\n");
        sb.append("\"slots\": [\n");

        int count = 0;
        for (var slot : ctx.clusterLayout.slots) {
            if (count >= 20) break; // 限制输出数量

            // 解析 preset
            var preset = com.formacraft.common.cluster.zoning.ProgramPresetResolver.resolve(
                    styleId, slot.program
            );

            sb.append("  {\n");
            sb.append("    \"anchor\": [").append(slot.anchor.getX()).append(",")
              .append(slot.anchor.getY()).append(",").append(slot.anchor.getZ()).append("],\n");
            sb.append("    \"t\": ").append(String.format(java.util.Locale.ROOT, "%.3f", slot.t)).append(",\n");
            sb.append("    \"side\": \"").append(slot.side.name()).append("\",\n");
            sb.append("    \"lane\": ").append(slot.laneIndex).append(",\n");
            sb.append("    \"facing\": \"").append(slot.facing.name()).append("\",\n");
            sb.append("    \"program\": \"").append(slot.program.name()).append("\",\n");
            sb.append("    \"component_preset_id\": \"").append(preset.id).append("\",\n");
            sb.append("    \"component_preset\": ").append(jsonString(preset.toPromptText())).append("\n");
            sb.append("  }");
            if (count < Math.min(20, ctx.clusterLayout.slots.size() - 1)) sb.append(",");
            sb.append("\n");

            count++;
        }

        sb.append("]\n");
        sb.append("- IMPORTANT: Component types are already determined by preset\n");
        sb.append("- Your role: generate specific instances of these components\n");
        sb.append("- Follow the preset weights and densities when placing components\n");

        return sb.toString();
    }

    /**
     * 将字符串转换为 JSON 字符串（转义特殊字符）
     */
    static String jsonString(String text) {
        if (text == null) return "\"\"";
        // 简单转义：换行、引号、反斜杠
        String escaped = text.replace("\\", "\\\\")
                            .replace("\"", "\\\"")
                            .replace("\n", "\\n")
                            .replace("\r", "\\r")
                            .replace("\t", "\\t");
        return "\"" + escaped + "\"";
    }

    /**
     * 地形策略（Terrain Strategy）
     * <p>
     * 这是"人工智能建筑师"的核心：地形本身是建筑语义的一部分
     */
    static String terrainBlock(PromptContext ctx) {
        if (ctx == null || ctx.terrainPolicy == null) {
            // 默认策略：ADAPTIVE
            return defaultTerrainBlock();
        }

        TerrainPolicy policy = ctx.terrainPolicy;
        StringBuilder sb = new StringBuilder();
        sb.append("TERRAIN STRATEGY:\n");

        // 策略语义
        String strategySemantic = switch (policy.strategy) {
            case PRESERVE -> "preserve existing terrain; avoid terrain modification except minimal supports";
            case ADAPTIVE -> "adapt buildings individually to terrain; allow minor local leveling under each building";
            case TERRACE -> "use terraces/platforms; buildings sit on terraces; connect by stairs/ramps";
            case FLATTEN -> "flatten terrain within allowed scope before building";
        };
        sb.append("- strategy: ").append(policy.strategy.name()).append(" (").append(strategySemantic).append(")\n");

        // 作用域
        sb.append("- scope: ").append(policy.scope.name()).append("\n");

        // 限制
        sb.append("- limits:\n");
        sb.append("  * max_cut_depth: ").append(policy.maxCutDepth).append("\n");
        sb.append("  * max_fill_height: ").append(policy.maxFillHeight).append("\n");
        sb.append("  * allow_bridges: ").append(policy.allowBridges).append("\n");
        sb.append("  * allow_stairs: ").append(policy.allowStairs).append("\n");
        sb.append("  * allow_foundations: ").append(policy.allowFoundations).append("\n");

        // 重要提示
        if (policy.avoidLargeScaleFlatten && policy.strategy != com.formacraft.common.terrain.TerrainStrategy.FLATTEN) {
            sb.append("- IMPORTANT: avoid large-scale flattening; adapt buildings individually\n");
        }
        if (policy.preserveOverallShape) {
            sb.append("- preserve overall terrain shape (avoid massive earthwork)\n");
        }

        // 路径约束（如果有 PathTool）
        if (policy.scope == TerrainPolicy.Scope.PATH) {
            sb.append("\nPATH CONSTRAINTS:\n");
            sb.append("- follow the provided path geometry\n");
            sb.append("- adapt structure height smoothly to terrain\n");
            sb.append("- add steps/ramps where slope increases\n");
            if (policy.allowBridges) {
                sb.append("- use small bridges over gaps/water\n");
            }
        }

        // 建筑群规则
        sb.append("\nCLUSTER RULES:\n");
        sb.append("- buildings may sit at different elevations\n");
        if (policy.allowStairs) {
            sb.append("- connect buildings using terrain-following paths\n");
            sb.append("- use stairs, ramps, or small bridges where needed\n");
        }

        sb.append("\n");
        return sb.toString();
    }

    /**
     * 默认地形策略（ADAPTIVE）
     */
    static String defaultTerrainBlock() {
        return """
TERRAIN STRATEGY:
- strategy: ADAPTIVE (adapt buildings individually to terrain; allow minor local leveling under each building)
- scope: NONE
- limits:
  * max_cut_depth: 2
  * max_fill_height: 2
  * allow_bridges: true
  * allow_stairs: true
  * allow_foundations: true
- IMPORTANT: avoid large-scale flattening; adapt buildings individually
- preserve overall terrain shape (avoid massive earthwork)

CLUSTER RULES:
- buildings may sit at different elevations
- connect buildings using terrain-following paths
- use stairs, ramps, or small bridges where needed

""";
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
