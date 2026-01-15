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

        // 1.25. ComponentQuery System Prompt（构件查询系统 - AI-first 组件选择）
        sb.append(componentQuerySystemPrompt());

        // 1.5. Memory Context（记忆上下文 - RAG 功能）
        MemoryContext memoryContext = retrieveMemory(ctx);
        if (memoryContext != null && !memoryContext.isEmpty()) {
            sb.append(memoryContextBlock(memoryContext));
        }

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
     * <p>
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
- Player prefab components may have a placement contract (placementSpec: Attachment/Context/FacingPolicy/Constraints).
- If you choose to use a prefab component, you MUST satisfy its placement contract by selecting a compatible host (socket / outline edge / corner).
- If no compatible host exists, omit that prefab component instead of forcing an invalid placement.
- If information is missing, infer reasonable defaults consistent with style and program.
- Output VALID JSON ONLY. No comments, no explanations.

STYLE ANALYSIS (IMPORTANT):
- Analyze the user's description to extract style characteristics:
  * Colors: wall_color (white, gray, red, brown, black, etc.), roof_color, accent_color
  * Materials: wall_material (stone, brick, wood, concrete, terracotta, etc.), roof_material (tile, shingle, slate, metal), floor_material
  * Decorative elements: wood_carvings, lattice_windows, columns, etc.
- Output these in the "style_attributes" field
- If the user mentions specific colors or materials, use them explicitly
- If the user mentions a known style (e.g., "Chinese", "Medieval", "Modern", "徽派"), infer appropriate attributes
- Be creative and specific: "red brick walls" → wall_color: "red", wall_material: "brick"
- For traditional styles, include characteristic decorative elements

If mode = "patch":
- Only modify components inside the allowed area.
- Do NOT affect protected or forbidden zones.

If mode = "build":
- Produce a full blueprint.

Output schema:

{
  "mode": "build | patch",
  "style_profile": "string",
  "style_attributes": { StyleAttributesObject },
  "anchor": { "x": int, "y": int, "z": int },
  "genome": { BuildingGenomeObject },
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
  "features": [ "string" ],
  "params": { ComponentParamsObject },
  "component_query": { ComponentQueryObject }  // OPTIONAL: Use ComponentQuery instead of exact component_id
}

ComponentQueryObject (use in component_query field):
{
  "semantic": {
    "role": "door | window | column | balcony | railing | ornament | canopy | bracket",
    "tags": [ "string" ],
    "importance": [ "role | placement | style | geometry" ]
  },
  "context": {
    "placement": "wall | roof | edge | ground | interior",
    "side": "exterior | interior | both",
    "heightLevel": "ground | mid | roof | any",
    "edgeCondition": "flat | corner | convex | concave | any"
  },
  "geometry": {
    "requiresOpening": boolean,
    "openingWidth": integer | null,
    "openingHeight": integer | null,
    "tolerance": integer,
    "scalable": boolean
  },
  "style": {
    "styleProfile": "string | null",
    "materialTone": "string | null"
  },
  "constraints": {
    "mustHave": [ "string" ],
    "forbiddenTags": [ "string" ]
  },
  "usageHint": {
    "frequency": "primary | secondary | decorative",
    "visibility": "high | medium | low"
  }
}

BuildingGenomeObject (v1):
{
  "genomeVersion": "1.0",
  "archetype": { "id": "generic", "confidence": 0.0 },
  "topology": { "layout": "rectangular|circular|linear|radial|freeform", "composition": "single|cluster|chain|grid", "axis": "centered|axial|none", "levels": "horizontal|vertical|mixed" },
  "structure": { "type": "solid|frame|hybrid|suspended", "massiveness": 0.0-1.0, "voidRatio": 0.0-1.0, "supports": "central|distributed" },
  "form": { "repetition": "none|horizontal|vertical|radial", "progression": "uniform|tapering|stepping|upward", "curvature": "straight|curved|mixed", "rhythm": "regular|segmented|irregular" },
  "symmetry": { "type": "none|bilateral|radial|grid", "order": int, "mirror": true|false },
  "modules": [ "roof", "windows", "courtyard", "balcony", "arcade", "tower", "bridge" ],
  "materials": { "primary": "stone|wood|earth|metal|glass|mixed", "secondary": "stone|wood|earth|metal|glass|mixed", "accent": "stone|wood|earth|metal|glass|mixed", "textureBias": "rough|smooth|polished|aged" },
  "culturalStyle": { "region": "chinese|european|japanese|islamic|modern|industrial|...", "era": "traditional|medieval|19th_century|modern|...", "keywords": ["string"] },
  "constraints": { "maxHeight": int, "respectTerrain": true|false, "insideSelectionOnly": true|false },
  "aiHints": { "priority": ["string"], "avoid": ["string"] }
}

StyleAttributesObject:
{
  "wall_color": "string",
  "roof_color": "string",
  "accent_color": "string",
  "wall_material": "string",
  "roof_material": "string",
  "floor_material": "string",
  "decorative_elements": ["string"]
}

ComponentParamsObject:
{
  "shape": "rectangle|circle|rounded_rect",
  "corner_radius": int,
  "plan_type": "none|cross|cut_corners|l_shape|courtyard",
  "arm_width": int,
  "corner_cut": int,
  "l_corner": "NW|NE|SW|SE",
  "courtyard_ratio": 0.0-1.0,
  "void_ratio": 0.0-1.0,
  "window_ratio": 0.0-1.0,
  "roof_type": "flat|gable|hip|cone|pyramid|dome|double_gable|xuanshan|xieshan",
  "setback_ratio": 0.0-1.0,
  "floor_height": int,
  "floor_count": int,
  "masses": [
    { "offset": { "x": int, "y": int, "z": int }, "dimensions": { "width": int, "depth": int, "height": int }, "shape": "rectangle|circle|rounded_rect" }
  ]
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

        // 6.25. Player Component Library（构件库 v1）
        sb.append(componentLibraryBlock());

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
     * Player Component Library（Prefab/构件库 v1）。
     * <p>
     * 注意：当前 JSON 输出 schema 不新增字段，避免破坏后端解析。
     * 若要“请求使用构件”，请在 components[].features 中写入 feature 字符串：
     * component_request:{"semantic":"main_entrance","category":"DOOR","tags":["Chinese"],"approx_size":{"w":4,"h":6,"d":1},"count":1}
     */
    private static String componentLibraryBlock() {
        String summary;
        try {
            summary = com.formacraft.client.component.ClientComponentCatalogState.getSummary();
        } catch (Throwable t) {
            summary = "(no player components registered)";
        }

        String groupSummary;
        try {
            groupSummary = com.formacraft.common.component.group.ComponentGroupRegistry.summary();
        } catch (Throwable t) {
            groupSummary = "(no component groups registered)";
        }

        // 检查当前加载的构件是否有效（AI 接口保护）
        StringBuilder validationWarning = new StringBuilder();
        try {
            var componentTool = com.formacraft.client.tool.ComponentTool.INSTANCE;
            if (componentTool.getLoadedComponent() != null) {
                if (!componentTool.isLoadedComponentValid()) {
                    var validationResult = componentTool.getState().getValidationResult();
                    if (validationResult != null && validationResult.hasErrors()) {
                        validationWarning.append("\n⚠️ WARNING: Currently loaded component has validation errors and should NOT be used in AI generation:\n");
                        for (var error : validationResult.errors()) {
                            validationWarning.append("  - ").append(error.path).append(": ").append(error.message).append("\n");
                        }
                        validationWarning.append("Please fix the component before using it in AI generation.\n");
                    }
                }
            }
        } catch (Throwable ignored) {
            // 忽略检查错误，不影响 Prompt 生成
        }

        return "PLAYER COMPONENT LIBRARY (Prefab Library):\n" +
                summary + "\n" +
                "\nCOMPONENT GROUPS (Composite Prefabs):\n" +
                groupSummary + "\n" +
                validationWarning +
                "\nRules:\n" +
                "- IMPORTANT: Facing is a low-level detail. Prefer semantic placement via placementSpec (Attachment/Context/FacingPolicy) when deciding where/how to mount.\n" +
                "\nCOMPONENT PLACEMENT CONTRACTS (MUST FOLLOW):\n" +
                "- Many prefabs include a line like: `placement attachment=... context=... facingPolicy=...`.\n" +
                "- Treat that line as a strict placement contract. Do NOT use a prefab if you cannot satisfy its contract.\n" +
                "- Contracts are satisfied by choosing a compatible HOST candidate:\n" +
                "  * HOST=socket on a host component: map socket.type -> attachment: DOOR/WINDOW => WALL_OPENING; WALL/DECORATION/BALCONY => WALL_SURFACE; ROOF_ATTACHMENT => ROOF_EDGE.\n" +
                "  * HOST=outline candidates (ATTACHMENT CANDIDATES): EDGE/CORNER segments/points.\n" +
                "- Contract enforcement rules:\n" +
                "  * attachment=WALL_OPENING: ONLY mount into DOOR/WINDOW sockets. Do NOT mount on WALL_SURFACE sockets.\n" +
                "  * attachment=EDGE: ONLY place/mount when an EDGE candidate exists.\n" +
                "  * attachment=CORNER: ONLY place/mount when a CORNER candidate exists.\n" +
                "  * spatialContext=EXTERIOR: do not place it in interior/courtyard-facing sides.\n" +
                "- FacingPolicy is NOT a legality check; it only affects how facing is derived:\n" +
                "  * NONE: omit facing/mount_facing.\n" +
                "  * DERIVED_FROM_HOST / OUTWARD_NORMAL: omit mount_facing; it will be derived from host.\n" +
                "  * ALONG_EDGE: omit mount_facing, but provide an edge hint when possible (edge endpoints from ATTACHMENT CANDIDATES).\n" +
                "- You MAY request using player components by semantic requirements (category/tags/approx_size).\n" +
                "- Do NOT request exact component id unless necessary.\n" +
                "- You MAY request using component groups when you need stable multi-part structures (tower/gatehouse/wall segment...).\n" +
                "- To use a group, add a feature string to the relevant ComponentObject:\n" +
                "  group_request:{\"group_id\":\"TOWER_BASIC\",\"facing\":\"SOUTH\",\"mirror\":\"NONE\"}\n" +
                "- To mount a group into a host socket, include mount_to (or host_id + socket_id):\n" +
                "  group_request:{\"group_id\":\"MEDIEVAL_GATEHOUSE\",\"mount_to\":\"wall_id.main_gate\",\"carve\":true}\n" +
                "- A group may expose sockets too (see `socket.<id> ...` lines under the group listing). You MAY mount extra components onto group sockets via mounts:\n" +
                "  group_request:{\"group_id\":\"MEDIEVAL_GATEHOUSE\",\"mounts\":[{\"socket_id\":\"wall_left\",\"mount_id\":\"wall_segment\"},{\"socket_id\":\"wall_right\",\"mount_id\":\"wall_segment\"}]}\n" +
                "- mounts can also mount a NESTED GROUP (group-to-group assembly) via mount_group_id:\n" +
                "  group_request:{\"group_id\":\"MEDIEVAL_GATEHOUSE\",\"mounts\":[{\"socket_id\":\"wall_left\",\"mount_group_id\":\"WALL_SEGMENT\"}]}\n" +
                "- mounts supports mount_offset (socket-local coords: x=right, y=up, z=forward along socket facing):\n" +
                "  group_request:{\"group_id\":\"MEDIEVAL_GATEHOUSE\",\"mounts\":[{\"socket_id\":\"wall_left\",\"mount_id\":\"wall_segment\",\"mount_offset\":{\"x\":0,\"y\":0,\"z\":3}}]}\n" +
                "- For chained wall building, mounts supports repeat/count when mounting a group. It will repeatedly mount the same group by following repeat_socket (default: next):\n" +
                "  group_request:{\"group_id\":\"MEDIEVAL_GATEHOUSE\",\"mounts\":[{\"socket_id\":\"wall_left\",\"mount_group_id\":\"WALL_SEGMENT\",\"repeat\":5,\"repeat_socket\":\"next\"}]}\n" +
                "- Components support style-driven semantic re-skinning: component shape is fixed, material is decided by SemanticStyleProfile.\n" +
                "- Available semantic parts are from SemanticPart enum (e.g. WALL, FOUNDATION, PILLAR, BEAM, WINDOW, DOORWAY, RAILING, LIGHT, STAIR_STEP, ROOF...).\n" +
                "- Available semantic style ids currently registered: DEFAULT, MEDIEVAL_CASTLE (and others if present).\n" +
                "- If a component entry lists lines like `socket.<id> type=... facing=... origin=(x,y,z) size=wxhxd`, those are AVAILABLE SOCKETS defined by that host component.\n" +
                "- If a component entry lists a line like `placement attachment=... context=... facingPolicy=...`, treat it as the component's placement contract.\n" +
                "- When choosing sockets for mounting, match placementSpec.attachment with the host socket type: DOOR/WINDOW -> WALL_OPENING; WALL/DECORATION/BALCONY -> WALL_SURFACE; ROOF_ATTACHMENT -> ROOF_EDGE.\n" +
                "- To mount, set host_id to the host component id, and socket_id to the socket id (e.g. `main_door`, not including the `socket.` prefix).\n" +
                "- When using player components, prefer semantic re-skinning (semantic_skin=true) unless you must preserve exact original blocks.\n" +
                "- For DOOR/WINDOW mounts, prefer carving a socket mask (carve=true). Default masks: DOOR=2x3x1, WINDOW=2x2x1. You may override via mask={w,h,d} and mask_origin={x,y,z}.\n" +
                "- If you want to use a player component, add a feature string to the relevant ComponentObject:\n" +
                "  component_request:{\"semantic\":\"...\",\"category\":\"DOOR|WINDOW|COLUMN|...\",\"tags\":[\"...\"],\"approx_size\":{\"w\":-1,\"h\":-1,\"d\":-1},\"facing\":\"NORTH|EAST|SOUTH|WEST\",\"mirror\":\"NONE|X|Z\",\"semantic_style_id\":\"DEFAULT|...\",\"semantic_skin\":true,\"carve\":true,\"mask\":{\"w\":2,\"h\":3,\"d\":1},\"mask_origin\":{\"x\":0,\"y\":0,\"z\":0}}\n" +
                "- To mount a component into a host socket, use ONE component_request with mount fields (do not emit a separate host component):\n" +
                "  component_request:{\"host_id\":\"...\",\"socket_id\":\"socket_1\",\"mount_id\":\"...\",\"facing\":\"SOUTH\",\"mirror\":\"NONE\",\"semantic_skin\":true}\n" +
                "\n" +
                "COMPONENT QUERY SYSTEM (AI-first component selection):\n" +
                "- IMPORTANT: You DO NOT select specific component IDs. Instead, describe what kind of component you need using ComponentQuery.\n" +
                "- The system will automatically find the best matching component from the library based on your query.\n" +
                "- ComponentQuery structure (use this in component_request instead of exact component_id):\n" +
                "  component_query:{\n" +
                "    \"semantic\":{\"role\":\"door|window|column|railing|decoration\",\"tags\":[\"gothic\",\"arched\"],\"importance\":[\"role\",\"placement\"]},\n" +
                "    \"context\":{\"placement\":\"wall|roof|edge|ground|interior\",\"side\":\"interior|exterior|both\",\"height_level\":\"ground|mid|roof\",\"edge_condition\":\"corner|flat|convex\"},\n" +
                "    \"geometry\":{\"opening\":{\"width\":2,\"height\":3,\"tolerance\":1},\"scalable\":true},\n" +
                "    \"style\":{\"style_profile\":\"Medieval_Castle\",\"material_tone\":\"dark_stone\"},\n" +
                "    \"constraints\":{\"forbidden_tags\":[\"glass\",\"modern\"],\"must_have\":[\"structural\"]},\n" +
                "    \"usage_hint\":{\"frequency\":\"primary|secondary|decorative\",\"visibility\":\"high|low\"}\n" +
                "  }\n" +
                "- Example: Instead of \"component_id\":\"gothic_door_A\", use:\n" +
                "  component_query:{\"semantic\":{\"role\":\"door\",\"tags\":[\"gothic\",\"stone\"]},\"context\":{\"placement\":\"wall\",\"side\":\"exterior\"},\"style\":{\"style_profile\":\"Medieval_Castle\"}}\n" +
                "- The system will automatically:\n" +
                "  1. Filter components by hard constraints (role, placement, side, forbidden tags)\n" +
                "  2. Score components by semantic match, context match, geometry fit, style affinity, usage priority\n" +
                "  3. Select the best matching component (or top-N candidates for variant generation)\n" +
                "- You never know which specific component was selected - you only describe requirements.\n" +
                "\n";
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
     * <p>
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
     * <p>
     * 显式告诉 AI 路径骨架信息，让 AI 知道必须使用 PATH_POLYLINE
     */
    private static String skeletonHintBlock(PromptContext ctx) {
        if (ctx == null || ctx.pathSkeleton == null || !ctx.pathSkeleton.isValid()) {
            return "";
        }

        PathSkeleton skeleton = ctx.pathSkeleton;
        return "SKELETON HINT:\n" +
                "- type: PATH_POLYLINE\n" +
                "- intent: " + skeleton.intent.name() + "\n" +
                "- corridor_radius: " + skeleton.corridorRadius + "\n" +
                "- node_count: " + skeleton.getNodeCount() + "\n" +
                "- IMPORTANT: You MUST use PATH_POLYLINE skeleton type\n" +
                "- The path topology is fixed; you can only adjust style and details\n" +
                "\n";
    }

    /**
     * Cluster Layout（建筑群布局提示）
     * <p>
     * K1 新增：告诉 AI 建筑站位已经确定，AI 只需要决定建筑样式
     * K2 扩展：添加街道布局信息（多排、对称等）
     * <p>
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
        if (ctx.zoningProfile != null) {
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
     * <p>
     * K3.1 新增：生成可直接测试大模型的完整 JSON 模板
     * <p>
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
        sb.append("- Respect the component_preset weights and densities when generating components.\n");
        sb.append("- Populate \"genome\" with topology/structure/form/material semantics to drive parameterized generation.\n");
        sb.append("- Use ComponentObject.params to express shape/plan/void/roof/setback/multi-mass intent instead of free-text features.\n");
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
     * <p>
     * K3 新增：告诉 AI 建筑功能分区信息
     * <p>
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
     * <p>
     * K3.1 新增：告诉 AI 每个 Slot 的组件装配清单
     * <p>
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
     * <p>
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

    // ========== RAG 功能：记忆检索与上下文构建 ==========

    /**
     * 从 MemoryManager 检索相关建筑记忆
     * 
     * @param ctx Prompt 上下文
     * @return 记忆上下文，如果没有找到相关记忆则返回 null
     */
    private static MemoryContext retrieveMemory(PromptContext ctx) {
        if (ctx == null) {
            return null;
        }

        // 尝试获取 MemoryManager（仅在服务端可用）
        com.formacraft.server.memory.MemoryManager memoryManager;
        try {
            memoryManager = com.formacraft.server.build.BuildExecutionService.getInstance().getMemoryManager();
        } catch (Exception e) {
            // 客户端或未初始化时返回 null
            return null;
        }

        if (memoryManager == null) {
            return null;
        }

        java.util.Set<com.formacraft.server.memory.ProjectMemory> result = new java.util.LinkedHashSet<>();

        // 1. 空间优先：从 BuildContext 获取玩家位置
        try {
            com.formacraft.common.buildcontext.BuildContext bc = 
                com.formacraft.client.buildcontext.BuildContextResolver.resolve(false);
            if (bc != null && bc.origin != null) {
                result.addAll(memoryManager.findAtPosition(bc.origin));
                
                // 也查找附近的建筑
                com.formacraft.server.memory.ProjectMemory nearest = 
                    memoryManager.findNearest(bc.origin, 32.0);
                if (nearest != null) {
                    result.add(nearest);
                }
            }
        } catch (Exception e) {
            // 忽略客户端访问错误
        }

        // 2. 语义召回：从用户输入中提取关键词
        java.util.Set<String> keywords = extractKeywords(ctx.userMessage);
        if (!keywords.isEmpty()) {
            // 使用 OR 逻辑搜索（更宽松）
            for (String keyword : keywords) {
                if (keyword.length() >= 2) {
                    result.addAll(memoryManager.searchContains(keyword));
                }
            }
        }

        // 3. 限制数量（防止 prompt 爆炸）
        java.util.List<com.formacraft.server.memory.ProjectMemory> top = 
            result.stream().limit(3).toList();

        if (top.isEmpty()) {
            return null;
        }

        return new MemoryContext(top, summarize(top));
    }

    /**
     * 从用户输入中提取关键词（轻量版）
     * 
     * @param input 用户输入文本
     * @return 关键词集合
     */
    private static java.util.Set<String> extractKeywords(String input) {
        java.util.Set<String> keys = new java.util.HashSet<>();
        
        if (input == null || input.trim().isEmpty()) {
            return keys;
        }

        // 极简规则：按非字母数字和中文分词
        String[] tokens = input.toLowerCase().split("[^a-zA-Z0-9\\u4e00-\\u9fa5]+");
        
        for (String t : tokens) {
            if (t.length() >= 2) {
                keys.add(t);
            }
        }
        
        return keys;
    }

    /**
     * 将建筑记忆列表转换为 LLM 可理解的文本摘要
     * 
     * @param buildings 建筑记忆列表
     * @return 文本摘要
     */
    private static String summarize(java.util.List<com.formacraft.server.memory.ProjectMemory> buildings) {
        if (buildings == null || buildings.isEmpty()) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("=== WORLD MEMORY CONTEXT ===\n");
        sb.append("The following buildings already exist in this world:\n\n");

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

            // Gene Summary（简要信息）
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

    /**
     * ComponentQuery System Prompt（构件查询系统 - AI-first 组件选择）
     * <p>
     * 这是 System Prompt（不是 user / assistant）
     * <p>
     * 目标：
     * - LLM 输出 100% 可被 Gson/Jackson 解析
     * - 不掺杂自然语言
     * - 不让 LLM 越权（不选具体构件、不操作方块）
     */
    private static String componentQuerySystemPrompt() {
        return """
            
            ========================================
            COMPONENT QUERY SYSTEM (AI-first component selection)
            ========================================
            
            You are Formacraft Core, a backend reasoning engine for Minecraft architectural generation.
            
            You do NOT place blocks.
            You do NOT select specific components.
            You ONLY describe architectural intent in structured JSON.
            
            Your task:
            When a building requires architectural components (doors, windows, columns, balconies, railings, ornaments, etc),
            you must describe each required component as a ComponentQuery object.
            
            ComponentQuery describes WHAT kind of component is needed,
            NOT which concrete component to use.
            
            -----------------------------------
            OUTPUT RULES (CRITICAL)
            -----------------------------------
            - Output MUST be valid JSON
            - Output MUST NOT contain explanations or comments
            - Output MUST strictly follow the schema below
            - Do NOT invent fields
            - Use null instead of omitting optional fields
            - Use arrays even if only one item exists
            
            -----------------------------------
            ComponentQuery JSON Schema
            -----------------------------------
            
            {
              "semantic": {
                "role": "string",
                "tags": ["string"],
                "importance": ["string"]
              },
              "context": {
                "placement": "wall | roof | edge | ground | interior",
                "side": "exterior | interior | both",
                "heightLevel": "ground | mid | roof | any",
                "edgeCondition": "flat | corner | convex | concave | any"
              },
              "geometry": {
                "requiresOpening": boolean,
                "openingWidth": integer | null,
                "openingHeight": integer | null,
                "tolerance": integer,
                "scalable": boolean
              },
              "style": {
                "styleProfile": "string | null",
                "materialTone": "string | null"
              },
              "constraints": {
                "mustHave": ["string"],
                "forbiddenTags": ["string"]
              },
              "usageHint": {
                "frequency": "primary | secondary | decorative",
                "visibility": "high | medium | low"
              }
            }
            
            -----------------------------------
            SEMANTIC GUIDELINES
            -----------------------------------
            
            - role describes the architectural function:
              examples: door, window, column, balcony, railing, ornament, canopy, bracket
            
            - tags describe shape, feeling, or subtype:
              examples: arched, gothic, heavy, slender, carved, modular
            
            - importance affects ranking weight:
              examples: role, placement, style, geometry
            
            -----------------------------------
            CONSTRAINT GUIDELINES
            -----------------------------------
            
            - Use mustHave only for hard requirements
            - Use forbiddenTags to prevent unsuitable components
            - Do NOT overconstrain unless explicitly required
            
            -----------------------------------
            STYLE GUIDELINES
            -----------------------------------
            
            - styleProfile should match the building's StyleProfile if known
            - materialTone is a hint, not a strict requirement
            
            -----------------------------------
            GEOMETRY GUIDELINES
            -----------------------------------
            
            - requiresOpening = true for doors and windows
            - scalable = false ONLY when shape must remain fixed
            - tolerance defines how much size mismatch is allowed
            
            -----------------------------------
            USAGE GUIDELINES
            -----------------------------------
            
            - primary: main structural or focal component
            - secondary: supporting or repeating component
            - decorative: visual detail
            
            -----------------------------------
            IMPORTANT
            -----------------------------------
            
            You must output a JSON array of ComponentQuery objects.
            
            If no components are needed, output an empty array: []
            
            """;
    }

    /**
     * 生成记忆上下文块（用于拼接到 Prompt 中）
     */
    private static String memoryContextBlock(MemoryContext memoryContext) {
        if (memoryContext == null || memoryContext.isEmpty()) {
            return "";
        }
        return memoryContext.summary;
    }

}
