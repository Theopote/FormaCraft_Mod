package com.formacraft.ai.prompt;

import com.formacraft.FormacraftMod;
import com.formacraft.common.skeleton.PathSkeleton;
import com.formacraft.common.terrain.TerrainPolicy;

/**
 * Dynamic spatial constraint blocks assembled from tool state and PromptContext.
 */
final class PromptSpatialSections {
    private PromptSpatialSections() {}

    static String spatialConstraints(PromptContext ctx) {
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

        // 7. 区域语义标注（从 ctx.annotations，详细标签信息）
        if (!ctx.annotations.isEmpty()) {
            sb.append("\n=== SEMANTIC LABELS & ANNOTATIONS ===\n");
            for (String annotation : ctx.annotations) {
                sb.append(annotation).append("\n");
            }
        }

        // 8. 其他约束（从 ctx.constraints）
        if (!ctx.constraints.isEmpty()) {
            sb.append("\nADDITIONAL CONSTRAINTS:\n");
            for (String c : ctx.constraints) {
                sb.append("- ").append(c).append("\n");
            }
        }

        // 9. 硬规则（从 ctx.rules）
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
    /**
     * 是否存在可用的玩家构件库（构件 or 构件组）。
     * <p>
     * 用于 Prompt 瘦身：无库时，大段 prefab/socket/ComponentQuery 规则纯属浪费 token，
     * 应整体跳过（见 {@link PromptAssembler} 与 {@link #componentLibraryBlock()}）。
     */
    static boolean hasComponentLibrary() {
        try {
            if (com.formacraft.client.component.ClientComponentCatalogState.hasRegisteredComponents()) {
                return true;
            }
        } catch (Throwable ignored) {
        }
        try {
            if (!com.formacraft.common.component.group.ComponentGroupRegistry.list().isEmpty()) {
                return true;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    static String componentLibraryBlock() {
        // 无构件库：不注入大段 prefab/mount/ComponentQuery 规则（纯浪费 token），
        // 让 LLM 直接用语义组件搭建。
        if (!hasComponentLibrary()) {
            return "PLAYER COMPONENT LIBRARY (Prefab Library):\n" +
                    "(no player components or component groups registered)\n" +
                    "- No prefab components available; compose the building from semantic components only.\n\n";
        }

        String summary;
        try {
            com.formacraft.client.component.ClientComponentCatalogState.hydrateFromDiskIfEmpty();
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
        } catch (Throwable t) {
            FormacraftMod.LOGGER.debug("[PromptAssembler] loaded component validation check skipped", t);
        }

        return "PLAYER COMPONENT LIBRARY (Prefab Library):\n" +
                summary + "\n" +
                "\nCOMPONENT GROUPS (Composite Prefabs):\n" +
                groupSummary + "\n" +
                validationWarning +
                (com.formacraft.client.component.ClientComponentCatalogState.isSyncPending()
                        ? "\nNote: component catalog sync is still in progress; listing may reflect local disk snapshot.\n"
                        : "") +
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
                "- To request a component by intent (not by id), embed a ComponentQuery via component_request.component_query "
                + "(schema described in the COMPONENT QUERY SYSTEM section above).\n" +
                "\n";
    }

    /**
     * 锚点 + 朝向（Anchor / Facing）
     */
    static String anchorBlock(PromptContext ctx) {
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
    static String selectionBlock(PromptContext ctx) {
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
    static String footprintBlock(PromptContext ctx) {
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
    static String noBuildBlock(PromptContext ctx) {
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
    static String symmetryBlock(PromptContext ctx) {
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
     * 
     * 注意：详细的语义标注信息已经通过 SemanticLabelContext.toPromptBlock() 添加到 ctx.annotations 中
     * 这里只提供通用的语义区域说明
     */
    static String semanticBlock(PromptContext ctx) {
        if (!com.formacraft.ai.context.SemanticLabelContext.hasLabels()) {
            return "";
        }

        return """
SEMANTIC REGIONS (CRITICAL):
- Semantic labels bind natural language intent to spatial regions.
- You MUST check if a component falls within a labeled region and generate accordingly.
- Each label has specific architectural meaning - respect it.
- The "range" value indicates how far the label's influence extends.
- Multiple labels can be combined to form complex layouts.
- See detailed label information in the constraints section above.

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
    static String pathBlock(PromptContext ctx) {
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
    static String skeletonHintBlock(PromptContext ctx) {
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
    static String clusterLayoutBlock(PromptContext ctx) {
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
}
