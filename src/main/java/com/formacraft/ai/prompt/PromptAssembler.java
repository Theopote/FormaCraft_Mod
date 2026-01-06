package com.formacraft.ai.prompt;

import com.formacraft.ai.context.SelectionContext;
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

        // 4. Output Contract（输出契约）
        sb.append(outputContract());

        return sb.toString();
    }

    /**
     * System Prompt（AI 身份，永远固定）
     */
    private static String systemRole() {
        return """
You are Formacraft Core, a Minecraft architectural planning engine.

You do NOT place blocks directly.
You ONLY output structured JSON building blueprints.

All geometry must obey the spatial constraints below.
If constraints conflict, you must adapt the design, not break constraints.

Terrain is part of architectural semantics. Respect terrain strategy constraints.
Do NOT flatten terrain by default. Adapt buildings to terrain naturally.

Output MUST be valid JSON. No explanation text.

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
        StringBuilder sb = new StringBuilder();
        sb.append("SKELETON HINT:\n");
        sb.append("- type: PATH_POLYLINE\n");
        sb.append("- intent: ").append(skeleton.intent.name()).append("\n");
        sb.append("- corridor_radius: ").append(skeleton.corridorRadius).append("\n");
        sb.append("- node_count: ").append(skeleton.getNodeCount()).append("\n");
        sb.append("- IMPORTANT: You MUST use PATH_POLYLINE skeleton type\n");
        sb.append("- The path topology is fixed; you can only adjust style and details\n");
        sb.append("\n");
        return sb.toString();
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

    /**
     * Output Contract（AI 不敢乱来）
     */
    private static String outputContract() {
        return """
OUTPUT FORMAT (STRICT JSON):

{
  "style_profile": "string",
  "skeleton_type": "string",
  "components": [
    {
      "semantic": "TOWER | WALL | HALL | COURTYARD | BRIDGE | PATH",
      "shape": "CYLINDER | CUBOID | LINE | RING | CURVE",
      "relative_position": {"x": int, "y": int, "z": int},
      "dimensions": {...},
      "notes": "optional"
    }
  ]
}

Rules:
- All positions are relative to anchor (0,0,0)
- Must obey all constraints above
- JSON only
- No explanation text
- No markdown code blocks
- Must be directly parseable

""";
    }

}

