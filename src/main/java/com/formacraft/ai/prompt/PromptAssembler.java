package com.formacraft.ai.prompt;

import com.formacraft.ai.context.SelectionContext;
import com.formacraft.common.skeleton.PathSkeleton;
import com.formacraft.common.terrain.TerrainPolicy;

/**
 * Prompt 拼接器：把用户自然语言增强为“可控、可解析”的结构化 Prompt。
 */
public final class PromptAssembler {
    private PromptAssembler() {}

    public static String assembleUserPrompt(String userInput) {
        // 兼容旧入口：默认 BUILD 模式
        return assemble(userInput, PromptMode.BUILD);
    }

    /**
     * 新入口：支持 PromptMode + PromptContext（工具→结构化上下文→最终 Prompt）。
     */
    public static String assemble(String userInput, PromptMode mode) {
        String raw = userInput == null ? "" : userInput.trim();
        if (raw.isEmpty()) return "";

        PromptContext ctx = new PromptContext();
        ctx.userMessage = raw;
        ctx.mode = mode == null ? PromptMode.BUILD : mode;

        // 1) 工具 → 结构化上下文
        ToolPromptBuilder.buildToolContext(ctx);

        // 1.5) 解析地形策略（从工具状态 + 用户输入）
        TerrainPolicy basePolicy = TerrainPolicyResolver.resolve(ctx);
        ctx.terrainPolicy = TerrainPolicyResolver.resolveFromUserText(raw, basePolicy);

        // 2) 模式规则（预留：目前聊天建造仍走 BUILD）
        switch (ctx.mode) {
            case PATCH -> ctx.rules.add("- 仅输出增量修改（patch），不要重新生成整栋建筑");
            case MODIFY_REGION -> {
                if (SelectionContext.hasSelection()) {
                    ctx.rules.add("- 仅修改选区内的方块，保持选区外完全不变");
                } else {
                    ctx.rules.add("- 仅修改指定区域，保持其它区域不变");
                }
            }
            case BUILD -> {
                // no-op
            }
        }

        // 3) 拼装最终 Prompt
        return buildFinalPrompt(ctx);
    }

    private static String buildFinalPrompt(PromptContext ctx) {
        StringBuilder sb = new StringBuilder(1024);

        // 1. System Role（AI 身份，永远固定）
        sb.append(systemRole());

        // 2. Spatial Constraints（空间约束块）
        sb.append(spatialConstraints(ctx));

        // 3. User Intent（玩家原始描述）
        sb.append(userIntent(ctx));

        // 4. Structured JSON Template（结构化 JSON 模板 - K3.1 新增）
        sb.append(structuredJsonTemplate(ctx));

        return sb.toString();
    }

    /**
     * System Prompt（AI 身份，永远固定）
     * 
     * K3.1 更新：完整的 System Prompt，包含输出格式硬约束
     */
    private static String systemRole() {
        return """
You are Formacraft Core, a Minecraft architectural planning engine.

Your task is to convert structured spatial constraints into a BUILD BLUEPRINT or PATCH PLAN.
You DO NOT place blocks directly.
You ONLY output structured JSON following the schema below.

Core rules:
- Coordinate system: X/Z = horizontal plane, Y = vertical height.
- All positions are relative to the provided anchor (0,0,0).
- Respect all spatial constraints: path, outline, forbidden zones, symmetry, terrain strategy.
- Use semantic components (TOWER, WALL, ROOF, ENTRANCE, SIGNAGE, etc.), NOT blocks.
- If information is missing, infer reasonable defaults consistent with style and program.
- Output VALID JSON ONLY. No comments, no explanations.

If mode = "patch":
- Only modify components inside the allowed area.
- Do NOT affect protected or forbidden zones.

If mode = "build":
- Produce a full blueprint.

Output schema:

{
  "mode": "build | patch",
  "style_profile": "string",
  "anchor": { "x": int, "y": int, "z": int },
  "global_constraints": {
    "facing": "NORTH | SOUTH | EAST | WEST",
    "symmetry": "NONE | MIRROR_X | MIRROR_Z | RADIAL",
    "terrain_strategy": "PRESERVE | ADAPTIVE | TERRACE | FLATTEN"
  },
  "layout": {
    "skeleton_type": "LINEAR_PATH | RADIAL_RING | GRID | COMPOUND",
    "path_based": true,
    "slots": [ SlotObject ]
  },
  "components": [ ComponentObject ]
}

SlotObject:
{
  "slot_id": "string",
  "anchor": { "x": int, "y": int, "z": int },
  "facing": "NORTH | SOUTH | EAST | WEST",
  "program": "COMMERCIAL | RESIDENTIAL | PLAZA | INDUSTRIAL | DEFENSIVE | CIVIC | LANDMARK",
  "component_preset_id": "string",
  "component_preset": "text description"
}

ComponentObject:
{
  "component_type": "string",
  "slot_id": "string",
  "relative_position": { "x": int, "y": int, "z": int },
  "dimensions": { "width": int, "depth": int, "height": int },
  "features": [ "string" ]
}

""";
    }

    /**
     * Tool → Spatial Constraint Block（关键）
     * 这是自动化的灵魂
     */
    private static String spatialConstraints(PromptContext ctx) {
        StringBuilder sb = new StringBuilder();
        sb.append("=== SPATIAL CONSTRAINTS ===\n");

        // 1. 锚点 + 朝向
        sb.append(anchorBlock(ctx));

        // 2. 选区
        sb.append(selectionBlock(ctx));

        // 3. 轮廓
        sb.append(footprintBlock(ctx));

        // 4. 禁区
        sb.append(noBuildBlock(ctx));

        // 5. 对称
        sb.append(symmetryBlock(ctx));

        // 6. 区域语义标注
        sb.append(semanticBlock(ctx));

        // 6.5. 路径约束（PathTool - 地形自适应系统的灵魂）
        sb.append(pathBlock(ctx));

        // 6.5.5. Skeleton Hint（骨架提示 - 显式告诉 AI 路径骨架）
        sb.append(skeletonHintBlock(ctx));

        // 6.5.6. Cluster Layout（建筑群布局提示 - K1 新增）
        sb.append(clusterLayoutBlock(ctx));

        // 6.6. 地形策略（新增）
        sb.append(terrainBlock(ctx));

        // 7. 其他约束（从 ctx.constraints）
        if (!ctx.constraints.isEmpty()) {
            sb.append("\nADDITIONAL CONSTRAINTS:\n");
            for (String c : ctx.constraints) {
                sb.append("- ").append(c).append("\n");
            }
        }

        // 8. 硬规则（从 ctx.rules）
        if (!ctx.rules.isEmpty()) {
            sb.append("\nHARD RULES:\n");
            for (String r : ctx.rules) {
                sb.append("- ").append(r).append("\n");
            }
        }

        sb.append("\n=== END CONSTRAINTS ===\n\n");
        return sb.toString();
    }

    /**
     * 锚点 + 朝向（Anchor / Facing）
     */
    private static String anchorBlock(PromptContext ctx) {
        PromptMode mode = (ctx != null && ctx.mode != null) ? ctx.mode : PromptMode.BUILD;
        boolean restrict = (mode == PromptMode.MODIFY_REGION) || 
                          com.formacraft.client.preview.PromptModeState.restrictToSelection();
        com.formacraft.common.buildcontext.BuildContext bc = 
            com.formacraft.client.buildcontext.BuildContextResolver.resolve(restrict);
        
        if (bc == null || bc.origin == null) {
            return "";
        }

        String facing = bc.facing != null ? bc.facing.name() : "AUTO";
        
        return String.format("""
ANCHOR:
- anchor_position: (%d, %d, %d)
- anchor_semantic: build around anchor
- facing: %s

Interpret all relative positions with anchor as origin (0,0,0).

""",
            bc.origin.getX(),
            bc.origin.getY(),
            bc.origin.getZ(),
            facing
        );
    }

    /**
     * 选区（Selection）
     */
    private static String selectionBlock(PromptContext ctx) {
        if (!com.formacraft.ai.context.SelectionContext.hasSelection()) {
            return "";
        }

        return """
SELECTION CONSTRAINT:
- All building components MUST be fully inside the selected region.
- You may scale, rotate, or decompose buildings to fit.
- Do NOT place anything outside the region.

""";
    }

    /**
     * 轮廓（Footprint）
     */
    private static String footprintBlock(PromptContext ctx) {
        if (!com.formacraft.ai.context.OutlineContext.hasOutline()) {
            return "";
        }

        return """
FOOTPRINT CONSTRAINT:
- The building footprint is explicitly defined.
- All ground-contact blocks MUST be inside the footprint.
- Upper floors may overhang ONLY if explicitly reasonable for the style.

""";
    }

    /**
     * 禁区（No-Build Zones）
     */
    private static String noBuildBlock(PromptContext ctx) {
        if (!com.formacraft.ai.context.ProtectedZoneContext.hasZones()) {
            return "";
        }

        return """
NO-BUILD ZONES:
- The following areas are strictly forbidden.
- You must route, shape, or omit components to avoid them.
- Never place blocks inside forbidden zones.

""";
    }

    /**
     * 对称 / 镜像
     */
    private static String symmetryBlock(PromptContext ctx) {
        if (!com.formacraft.ai.context.SymmetryContext.enabled()) {
            return "";
        }

        // 尝试获取对称轴信息（简化处理）
        String axis = "X"; // 默认，实际应该从 SymmetryContext 获取
        return String.format("""
SYMMETRY CONSTRAINT:
- The design MUST respect %s-axis symmetry.
- All major components should be mirrored across the symmetry plane.

""", axis);
    }

    /**
     * 区域语义标注（Semantic Tags）
     */
    private static String semanticBlock(PromptContext ctx) {
        if (!com.formacraft.ai.context.SemanticLabelContext.hasLabels()) {
            return "";
        }

        return """
SEMANTIC REGIONS:
- courtyard: open, non-roofed, low height
- sacred: central, dominant, vertical emphasis
- circulation: paths, stairs, bridges only
- residential: modular, repeatable units

""";
    }

    /**
     * 路径约束（PathTool - 地形自适应系统的灵魂）
     * 
     * PathTool 驱动：
     * - 道路生成
     * - 长城生成
     * - 建筑沿线布局
     * - 台阶 / 缓坡 / 桥
     */
    private static String pathBlock(PromptContext ctx) {
        if (ctx == null || ctx.pathSkeleton == null || !ctx.pathSkeleton.isValid()) {
            return "";
        }

        PathSkeleton skeleton = ctx.pathSkeleton;
        StringBuilder sb = new StringBuilder();
        sb.append("PATH CONSTRAINT:\n");
        sb.append("- follow the provided path geometry\n");
        sb.append("- generate terrain-following structures\n");
        sb.append("- adapt structure height smoothly to terrain\n");
        
        if (ctx.terrainPolicy != null) {
            if (ctx.terrainPolicy.allowStairs) {
                sb.append("- add steps or ramps where slope increases\n");
            }
            
            if (ctx.terrainPolicy.allowBridges) {
                sb.append("- use small bridges over gaps/water\n");
            }
        }
        
        sb.append("- keep within path corridor (radius: ").append(skeleton.corridorRadius).append(")\n");
        sb.append("- path intent: ").append(skeleton.intent.name()).append("\n");
        sb.append("\n");
        return sb.toString();
    }

    /**
     * Skeleton Hint（骨架提示）
     * 
     * 显式告诉 AI 路径骨架信息，让 AI 知道必须使用 PATH_POLYLINE
     */
    private static String skeletonHintBlock(PromptContext ctx) {
        if (ctx == null || ctx.pathSkeleton == null || !ctx.pathSkeleton.isValid()) {
            return "";
        }

        PathSkeleton skeleton = ctx.pathSkeleton;
        String sb = "SKELETON HINT:\n" +
                "- type: PATH_POLYLINE\n" +
                "- intent: " + skeleton.intent.name() + "\n" +
                "- corridor_radius: " + skeleton.corridorRadius + "\n" +
                "- node_count: " + skeleton.getNodeCount() + "\n" +
                "- IMPORTANT: You MUST use PATH_POLYLINE skeleton type\n" +
                "- The path topology is fixed; you can only adjust style and details\n" +
                "\n";
        return sb;
    }

    /**
     * Cluster Layout（建筑群布局提示）
     * 
     * K1 新增：告诉 AI 建筑站位已经确定，AI 只需要决定建筑样式
     * K2 扩展：添加街道布局信息（多排、对称等）
     * 
     * 关键设计原则：
     * - ❌ AI 不决定"建筑站哪"（站位由算法决定）
     * - ✅ AI 决定"建筑长什么样"（风格、细节由 AI 决定）
     */
    private static String clusterLayoutBlock(PromptContext ctx) {
        if (ctx == null || ctx.clusterLayout == null || !ctx.clusterLayout.isValid()) {
            return "";
        }

        com.formacraft.common.cluster.PathClusterLayout layout = ctx.clusterLayout;
        String strategy = ctx.terrainPolicy != null ? ctx.terrainPolicy.strategy.name() : "ADAPTIVE";
        
        StringBuilder sb = new StringBuilder();
        sb.append("CLUSTER LAYOUT:\n");
        sb.append("- type: PATH_ALIGNED\n");
        sb.append("- building_count: ").append(layout.getSlotCount()).append("\n");
        sb.append("- spacing: 8 (algorithm-determined)\n");
        sb.append("- placement_rule: buildings aligned along path, placed on both sides\n");
        sb.append("- terrain_strategy: ").append(strategy).append("\n");
        
        // K2 新增：街道布局信息
        if (ctx.streetProfile != null) {
            sb.append("\nSTREET LAYOUT:\n");
            sb.append("- type: MULTI_LANE_PATH\n");
            sb.append("- lanes_per_side: ").append(ctx.streetProfile.laneCount()).append("\n");
            sb.append("- road_width: ").append(ctx.streetProfile.roadWidth()).append("\n");
            sb.append("- lane_spacing: ").append(ctx.streetProfile.laneSpacing()).append("\n");
            sb.append("- symmetry: ").append(ctx.streetProfile.symmetric()).append("\n");
            
            // 样式分布建议（AI 可以遵循）
            if (ctx.streetProfile.laneCount() >= 2) {
                sb.append("- style_distribution:\n");
                sb.append("  * inner_lane: commercial (closer to road)\n");
                sb.append("  * outer_lane: residential (farther from road)\n");
            }
        }
        
        sb.append("\n");
        sb.append("- IMPORTANT: Building positions (anchors) are already determined by algorithm\n");
        sb.append("- Your role: decide building style, details, and variations\n");
        sb.append("- Do NOT change building positions or anchors\n");
        
        // K3 新增：功能分区信息
        if (ctx.clusterLayout != null && ctx.zoningProfile != null) {
            sb.append("\n");
            sb.append(zoningBlock(ctx));
        }
        
        // K3.1 新增：组件预设信息
        if (ctx.clusterLayout != null) {
            String presetBlock = componentPresetBlock(ctx);
            if (!presetBlock.isEmpty()) {
                sb.append("\n");
                sb.append(presetBlock);
            }
        }
        
        sb.append("\n");
        return sb.toString();
    }

    /**
     * Structured JSON Template（结构化 JSON 模板）
     * 
     * K3.1 新增：生成可直接测试大模型的完整 JSON 模板
     * 
     * 包含所有关键信息：anchor, facing, path, slots, program, component preset, terrain strategy
     */
    private static String structuredJsonTemplate(PromptContext ctx) {
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
            switch (ctx.pathSkeleton.intent) {
                case ROAD -> skeletonType = "LINEAR_PATH";
                case WALL -> skeletonType = "LINEAR_PATH";
                case BRIDGE -> skeletonType = "LINEAR_PATH";
                default -> skeletonType = "LINEAR_PATH";
            }
        }

        // 开始构建 JSON
        sb.append("{\n");
        sb.append("  \"mode\": \"").append(ctx.mode != null ? ctx.mode.name().toLowerCase() : "build").append("\",\n");
        sb.append("  \"style_profile\": \"").append(styleProfile).append("\",\n");
        sb.append("  \"anchor\": { \"x\": ").append(anchorX).append(", \"y\": ").append(anchorY).append(", \"z\": ").append(anchorZ).append(" },\n");
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
        sb.append("- Respect the component_preset weights and densities when generating components.\n");
        sb.append("- Output ONLY valid JSON. No comments, no explanations.\n");
        
        return sb.toString();
    }

    /**
     * 转义 JSON 字符串（用于 component_preset 字段）
     */
    private static String escapeJsonString(String text) {
        if (text == null) return "";
        return text.replace("\\", "\\\\")
                  .replace("\"", "\\\"")
                  .replace("\n", "\\n")
                  .replace("\r", "\\r")
                  .replace("\t", "\\t");
    }

    /**
     * Zoning Block（功能分区提示）
     * 
     * K3 新增：告诉 AI 建筑功能分区信息
     * 
     * 关键设计原则：
     * - ✅ LLM 看到"规划意图"（功能分区）
     * - ❌ LLM 不决定 slot 坐标（坐标仍由布局算法给）
     */
    private static String zoningBlock(PromptContext ctx) {
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
     * 
     * K3.1 新增：告诉 AI 每个 Slot 的组件装配清单
     * 
     * 关键设计原则：
     * - ✅ LLM 看到"组件装配清单"（preset）
     * - ✅ LLM 根据 preset 生成具体组件（而不是自由发散）
     * - ❌ LLM 不决定组件类型（组件类型由 preset 决定）
     */
    private static String componentPresetBlock(PromptContext ctx) {
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
    private static String buildZonedSlotsJson(java.util.List<com.formacraft.common.cluster.ZonedSlot> zonedSlots) {
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
    private static String buildSlotsWithPresets(PromptContext ctx) {
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
    private static String jsonString(String text) {
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
     * 
     * 这是"人工智能建筑师"的核心：地形本身是建筑语义的一部分
     */
    private static String terrainBlock(PromptContext ctx) {
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
    private static String defaultTerrainBlock() {
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
    private static String userIntent(PromptContext ctx) {
        if (ctx == null || ctx.userMessage == null || ctx.userMessage.trim().isEmpty()) {
            return "";
        }

        return """
USER REQUEST:
""" + ctx.userMessage.trim() + "\n\n";
    }

}

