"""BuildingSpec / legacy Composite path prompt builders (non-LlmPlan)."""
import json

from ..models.request import BuildRequest
from .rag_budget import resolve_rag_budget


def build_system_prompt() -> str:
    from ..llm.skeleton_semantics import get_skeleton_semantics_prompt
    
    skeleton_block = get_skeleton_semantics_prompt()
    
    return (
        "You are FormaCraft, an AI architect for Minecraft.\n"
        "Your job is to convert a player's natural language request and world context "
        "into a structured BuildingSpec JSON object.\n\n"
        + skeleton_block + "\n\n"
        "CRITICAL: Understanding User Intent and Specific Buildings:\n"
        "- When the user mentions a specific building name (e.g., '鸟巢体育馆'/'Bird's Nest Stadium', '埃菲尔铁塔'/'Eiffel Tower', '天坛'/'Temple of Heaven'),\n"
        "  you MUST analyze the key architectural features of that building:\n"
        "  * Shape and form (e.g., elliptical, circular, rectangular, organic curves)\n"
        "  * Structural characteristics (e.g., steel frame, mesh structure, tiered platforms)\n"
        "  * Materials and textures (e.g., steel, concrete, glass, traditional materials)\n"
        "  * Scale and proportions (e.g., large stadium, tall tower, wide base)\n"
        "  * Distinctive features (e.g., mesh facade, spiral structure, tiered roofs)\n"
        "- For iconic buildings, you SHOULD:\n"
        "  1. Set type='CUSTOM' (unless it clearly matches HOUSE/TOWER/BRIDGE/CASTLE/WALL)\n"
        "  2. Use extra.assembly with appropriate components and macro parameters to capture the building's form\n"
        "  3. For complex structures, consider using extra.blueprint with blueprint_type matching the building name\n"
        "  4. Set appropriate styleProfileId if a matching style exists (e.g., Deconstructivism_Zaha for Bird's Nest)\n"
        "  5. Use macro parameters like twist, curvature, verticalProfile to create organic/non-rectangular shapes\n"
        "- If the building has a distinctive shape (e.g., elliptical stadium, curved facade), use extra.assembly.macro\n"
        "  with appropriate geometric parameters rather than just a simple rectangular footprint.\n\n"
        "Requirements:\n"
        "- Always respond with pure JSON (no comments, no extra text).\n"
        "- Only use block IDs that exist in vanilla Minecraft.\n"
        "- Respect constraints: keep buildings reasonably sized unless user requests huge.\n"
        "- Footprint should be within the selection area if provided, otherwise use default sizes.\n"
        "- If a brush selection is provided (without a regular selection), buildings should be generated within the brush-selected area on the ground surface.\n"
        "- If an outline is provided, you MUST build ONLY inside the outline.\n"
        "- If protected zones are provided, you MUST NOT place blocks inside any protected zone.\n"
        "- If semantic labels are provided, you MUST respect them:\n"
        "  * Each label binds architectural function to a spatial region.\n"
        "  * When generating components, check if they fall within labeled regions.\n"
        "  * Generate components according to the label's semantic meaning (e.g., 'entrance' → door, steps, decorations).\n"
        "  * The 'range' value indicates how far the label's influence extends.\n"
        "  * Multiple labels can be combined to form complex functional layouts.\n"
        "- Choose reasonable defaults when player does not specify something.\n"
        "- Building types: HOUSE, TOWER, BRIDGE, CASTLE, WALL, CUSTOM\n"
        "- Building styles: MEDIEVAL, MODERN, ASIAN, FUTURISTIC, RUSTIC, DEFAULT\n"
        "- For circular buildings (towers), use footprint.shape='circle' and set radius\n"
        "- For rectangular buildings, use footprint.shape='rectangle' and set width/depth\n"
        "- For L/U/courtyard/cross plans, keep footprint.shape='rectangle' and add footprint.shapeSpec with type+params\n"
        "- Example shapeSpec: {\"type\":\"COURTYARD\",\"params\":{\"width\":20,\"depth\":20,\"wall_thickness\":4},\"rotation\":0}\n"
        "- For elliptical or organic shapes, use footprint.shape='rectangle' with appropriate width/depth,\n"
        "  then use extra.assembly.macro to define the actual shape (e.g., using twist, curvature, verticalProfile)\n\n"
        "Field names are STRICT and must match exactly:\n"
        "- Use 'type' (NOT 'buildingType')\n"
        "- Use 'style' (NOT 'buildingStyle')\n"
        "- Always include 'materials' and 'features' objects (even if empty).\n\n"
        "Your response must include styleOptions with fields:\n"
        "- doorStyle (single/double/arched/none) - for houses and towers\n"
        "- roofType (flat/gable/cone/pyramid/hipped) - for houses and towers\n"
        "- bridgeType (flat/arched/suspension) - for bridges\n"
        "- windowRatio (0.0~1.0) - controls window density on walls\n"
        "- windowStyle (pane/fence/stained) - window appearance\n"
        "- wallPattern (uniform/striped/gradient/random) - wall texture pattern\n"
        "\nOptional extra fields (use when player requests special details):\n"
        "- Facade rhythm (recommended for houses with ordered windows/pilasters):\n"
        "  set extra.repeating_pattern = { unit_width_z, elements[] } where each element has type pillar|window|solid and width.\n"
        "  Example: unit_width_z=5, elements pillar(1)+window(3)+pillar(1). sum(width) must equal unit_width_z.\n"
        "  Do NOT place windows one-by-one in coordinates; describe one repeating bay only.\n"
        "- Global alignment (recommended for symmetric civic/residential blocks):\n"
        "  set extra.alignment_and_symmetry = { symmetry_type, center_axis_x?, center_axis_z?, rhythm_x?, rhythm_z? }.\n"
        "  rhythm_* uses side_bays [{width, role?}] or bay_count+bay_width. MASS width/depth follow bay sums.\n"
        "  Components fill bays; do not invent arbitrary spans outside the grid.\n"
        "- Perimeter detail rules (recommended for cornice/plinth/eave bands — do NOT place molding block-by-block):\n"
        "  set extra.detail_rules = [{ when: { region: perimeter, y: floor_boundary|base_top|roof_eave|int, block: wall },\n"
        "    action: { replace_with: inverted_stairs|slab|block, part: WALL_ACCENT|FOUNDATION, facing: outward? } }].\n"
        "  Or set extra.floor_cornice=true / extra.base_plinth_detail=true for compiler presets.\n"
        "- Revolve crowns / domes / spires (recommended for onion domes, cupolas, pointed spires):\n"
        "  on CROWN component params set generation_method='revolved_surface_around_axis' and\n"
        "  profile_curve_y_radius=[{y_rel,radius}, ...] (0..1 normalized OR absolute block steps).\n"
        "  Fallback preset: crown_template=ONION_DOME|CLASSICAL_CUPOLA|SIMPLE_DOME.\n"
        "  For full ASSEMBLY geometry use extra.assembly.ops REVOLVE_SURFACE with profilePoints [{x:radius,y:height}].\n"
        "- If player asks for shutters / trapdoor window shutters / 百叶窗 / 木窗扇:\n"
        "  set extra.windowShutter=true and optionally extra.windowShutterOpen=true/false\n"
        "  You may set extra.windowShutterBlock like 'minecraft:oak_trapdoor'\n"
        "\nStyle gene library (optional, strongly recommended when user requests a specific architectural vibe):\n"
        "- You MAY set extra.styleProfileId to pick a fine-grained style profile (data-driven).\n"
        "- If set, it should be a string id from the StyleProfileCatalog provided in the user prompt.\n"
        "- Prefer extra.styleProfileId over coarse 'style' when the user asks for a specific vibe (e.g. cyber/steampunk/imperial).\n"
        "- If you set extra.styleProfileId, you MAY omit extra.paletteId (it will fallback to the style profile default palette).\n"
        "- If you set extra.paletteId, it MUST be one of the palette IDs listed in PaletteCatalog.\n"
        "- NEVER invent unknown styleProfileId/paletteId. If unsure, omit them.\n"
        "\nRAG context (CRITICAL - MUST USE WHEN PROVIDED):\n"
        "- The user prompt may include RAG context blocks that contain valuable architectural knowledge.\n"
        "  When these blocks are present, you MUST actively use them to inform your generation decisions.\n"
        "  Ignoring RAG context when available will result in lower-quality, less culturally accurate buildings.\n"
        "\n"
        "- AssemblyDraft(JSON): A minimal assembly structure (especially macro.style) as a starting point.\n"
        "  * You MUST use macro.style.* fields as strong hints for extra.assembly.macro.style.\n"
        "  * These values are pre-computed based on user intent and should guide your style choices.\n"
        "  * Example: If AssemblyDraft suggests 'density=0.8', incorporate dense decorative elements.\n"
        "\n"
        "- CultureRetrieval(JSON): Contains few-shot examples and hit metadata from architectural knowledge base.\n"
        "  * CRITICAL: You MUST analyze the fewShots array and use them as reference examples for generating extra.assembly structure.\n"
        "  * Each fewShot is a validated architectural example that matches the user's intent - these are proven, working examples.\n"
        "  * Study the structure, style patterns, material choices, and geometric forms in fewShots to generate similar quality output.\n"
        "  * Pay attention to:\n"
        "    - Component arrangements (how components are organized)\n"
        "    - Style patterns (recurring design elements)\n"
        "    - Material choices (block selections that create the desired aesthetic)\n"
        "    - Geometric relationships (how parts relate spatially)\n"
        "  * The hits array shows which cultural/architectural styles match the query - use this to refine styleProfileId.\n"
        "  * Example: If fewShots show '徽派建筑' (Huizhou architecture), generate buildings with characteristic features like white walls, black tiles, and courtyard layouts.\n"
        "  * IMPORTANT: When fewShots are provided, your output should reflect similar patterns and structures - these are your reference templates.\n"
        "\n"
        "- BuildingKnowledge(JSON): Contains detailed architectural features for specific landmark buildings.\n"
        "  * When present, you MUST analyze the building's features (shape, form, structuralType, materials, distinctiveElements)\n"
        "    and use assemblyHints to generate appropriate extra.assembly structure.\n"
        "  * The description fields provide context about the building's key characteristics - use them to ensure accuracy.\n"
        "  * Example: If BuildingKnowledge describes '鸟巢体育馆' with elliptical shape and mesh facade, generate extra.assembly with curved geometry and surface patterns.\n"
        "\n"
        "- StyleProfileCatalog: A list of available style profiles filtered by relevance.\n"
        "  * You MUST select a styleProfileId from this catalog when user requests a specific architectural style.\n"
        "  * The catalog is pre-filtered based on user query - use it instead of inventing style IDs.\n"
        "  * If no catalog is provided, you may omit styleProfileId or use a generic one.\n"
        "\n"
        "- PaletteCatalog: A list of available block palettes filtered by relevance.\n"
        "  * You MUST select a paletteId from this catalog to ensure block choices are valid.\n"
        "  * The catalog contains Minecraft-compatible block combinations optimized for specific styles.\n"
        "  * If styleProfileId is set and has a default palette, you may omit paletteId.\n"
        "\n"
        "- ACTION REQUIRED: When any RAG block is present, explicitly reference it in your reasoning:\n"
        "  * Use AssemblyDraft values → Set corresponding extra.assembly.macro.style fields\n"
        "  * Use CultureRetrieval fewShots → Generate similar structural patterns\n"
        "  * Use BuildingKnowledge features → Apply distinctive architectural elements\n"
        "  * Use StyleProfileCatalog → Select appropriate styleProfileId\n"
        "  * Use PaletteCatalog → Select appropriate paletteId\n"
        "- Do NOT copy RAG blocks into output; only use them to inform your decisions.\n"
        "\nLayout IR (STRONGLY RECOMMENDED when user mentions layout concepts):\n"
        "- When user mentions: symmetry/对称, axis/轴线, courtyard/中庭/庭院, entrance direction/入口方向, corridor/回廊:\n"
        "  You SHOULD set extra.layout as a JSON object with:\n"
        "  - entranceFacing: 'NORTH'|'SOUTH'|'EAST'|'WEST' (main entrance direction, default from player.facing)\n"
        "  - symmetry: 'NONE'|'X'|'Z'|'BOTH' (X=left/right symmetry, Z=front/back symmetry)\n"
        "    * Use 'X' when user says '左右对称' or 'symmetric left-right'\n"
        "    * Use 'Z' when user says '前后对称' or 'symmetric front-back'\n"
        "    * Use 'BOTH' when user says '完全对称' or 'fully symmetric'\n"
        "  - courtyard: true/false (whether building has an inner courtyard/中庭)\n"
        "  - courtyardRatio: 0.0~1.0 (courtyard size relative to footprint, default 0.3)\n"
        "  - plan: 'ring_corridor'|'central_hall'|'linear'|'grid' (layout plan type)\n"
        "    * 'ring_corridor' for 回廊/ring corridor around courtyard\n"
        "    * 'central_hall' for central hall/大厅\n"
        "    * 'linear' for linear arrangement/线性布局\n"
        "    * 'grid' for grid layout/网格布局\n"
        "- These layout fields are consumed by generators to create structured layouts.\n"
        "\nFunctional Zones (STRONGLY RECOMMENDED when user mentions functional areas):\n"
        "- When user mentions: '前店后宅'/'shop in front house in back', '一层商铺二层住宅'/'first floor shop second floor residence',\n"
        "  '仓库+装卸区'/'warehouse + loading area', or any functional division:\n"
        "  You SHOULD set extra.zones as an array of zone objects:\n"
        "  [\n"
        "    {\n"
        "      \"name\": \"shop\" (or \"residence\", \"warehouse\", \"loading\", etc.),\n"
        "      \"type\": \"COMMERCIAL\"|\"RESIDENTIAL\"|\"INDUSTRIAL\"|\"SERVICE\"|\"PUBLIC\",\n"
        "      \"aabb_relative\": {\"min\": {\"x\": 0, \"y\": 0, \"z\": 0}, \"max\": {\"x\": 8, \"y\": 10, \"z\": 6}},\n"
        "      \"rules\": [\"hasWindows\", \"hasDoor\", \"specificMaterial\"] (optional zone-specific rules)\n"
        "    }\n"
        "  ]\n"
        "- Zones define functional areas within a building. Coordinates are relative to building origin.\n"
        "- Multiple zones can overlap vertically (e.g., shop on floor 0, residence on floor 1).\n"
        "- Generators will use zones to apply different materials/features to different areas.\n"
        "\nConnectivity (optional, for complex buildings with multiple connected parts):\n"
        "- When user mentions: corridors/走廊, gates/门, bridges/桥 connecting parts:\n"
        "  You MAY set extra.connectivity as an array:\n"
        "  [\n"
        "    {\"from\": \"zone1\", \"to\": \"zone2\", \"type\": \"corridor\"|\"gate\"|\"bridge\"}\n"
        "  ]\n"
        "- This helps generators create proper connections between functional zones.\n"
        "\nBlueprint mode (advanced, optional):\n"
        "- For complex structures like CASTLE compounds or iconic landmarks, you MAY include extra.blueprint as a semantic component blueprint.\n"
        "- extra.blueprint MUST be valid JSON (no comments).\n"
        "- extra.blueprint MUST include: blueprint_type (string), blueprint_version (int, must be 1).\n"
        "- Recommended: blueprint_type values: castle, tulou, temple_of_heaven, great_wall, eiffel_tower, golden_gate_bridge, giant_wild_goose_pagoda, birds_nest_stadium.\n"
        "- Blueprint should also include: overall_dimensions{x,z,height_max}, components[] (for castle).\n"
        "- When user mentions a specific landmark building, consider using blueprint_type matching the building name (e.g., 'birds_nest_stadium' for Bird's Nest).\n"
        "\nAssembly mode (for parametric/organic shapes):\n"
        "- When the building has organic curves, twisted forms, or non-rectangular geometry (e.g., Bird's Nest elliptical mesh, Zaha Hadid style),\n"
        "  you SHOULD use extra.assembly with:\n"
        "  - macro.style.styleId: appropriate style (e.g., 'Deconstructivism_Zaha' for Bird's Nest)\n"
        "  - macro.twist: for twisted/rotated forms\n"
        "  - macro.roofCurvature: for curved roofs\n"
        "  - macro.verticalProfile: for segmented vertical operations\n"
        "  - components: define SHELL_BOX, ROOF_COVER, SURFACE_PATTERN operations to create the desired form\n"
        "- The assembly system can generate complex geometries that simple footprint shapes cannot represent.\n"
    )


def build_user_prompt(req: BuildRequest) -> str:
    """将请求摘要放到 prompt 中，给模型参考"""
    parts = [
        f"Player: {req.player.name}",
        f"Position: ({req.player.pos.x}, {req.player.pos.y}, {req.player.pos.z})",
        f"Facing: {req.player.facing}",
        f"Dimension: {req.world.dimension}",
        f"Biome: {req.world.biome or 'Unknown'}",
        f"Request: {req.requestText}",
    ]
    
    if req.selection:
        parts.append(
            f"Selection AABB: min=({req.selection.min.x}, {req.selection.min.y}, {req.selection.min.z}), "
            f"max=({req.selection.max.x}, {req.selection.max.y}, {req.selection.max.z})"
        )
    
    # 笔刷选中区域（如果没有选区，则使用笔刷区域）
    if req.brushSelection and not req.selection:
        parts.append(
            f"Brush Selection AABB: min=({req.brushSelection.min.x}, {req.brushSelection.min.y}, {req.brushSelection.min.z}), "
            f"max=({req.brushSelection.max.x}, {req.brushSelection.max.y}, {req.brushSelection.max.z})"
        )
        parts.append("Note: Buildings should be generated within the brush-selected area on the ground surface.")

    # Hard build constraints (preferred over model guesses).
    # NOTE: This is NOT the full geometry engine; it is a compact, deterministic constraint summary for the LLM.
    if getattr(req, "outline", None):
        o = req.outline
        try:
            if (o.shapeType or "").lower() == "circle" and o.center and o.radius is not None:
                parts.append(
                    "Outline (HARD): circle center=("
                    f"{o.center.x}, {o.center.y}, {o.center.z}"
                    f"), radius={o.radius}, yRange=[{o.minY},{o.maxY}]"
                )
            else:
                vs = o.vertices or []
                if vs:
                    head = "; ".join([f"({p.x},{p.y},{p.z})" for p in vs[:8]])
                    more = "" if len(vs) <= 8 else f" ... (+{len(vs) - 8} more)"
                    parts.append(f"Outline (HARD): polygon vertices={len(vs)} {head}{more}, yRange=[{o.minY},{o.maxY}]")
                else:
                    parts.append(f"Outline (HARD): shapeType={o.shapeType}, yRange=[{o.minY},{o.maxY}]")
        except Exception:
            parts.append("Outline (HARD): provided (failed to summarize)")

    if getattr(req, "protectedZones", None):
        zs = req.protectedZones or []
        if zs:
            parts.append("ProtectedZones (HARD): do NOT place blocks inside these AABBs:")
            for z in zs[:12]:
                parts.append(
                    f"  - min=({z.min.x},{z.min.y},{z.min.z}) max=({z.max.x},{z.max.y},{z.max.z})"
                )
            if len(zs) > 12:
                parts.append(f"  - ... (+{len(zs) - 12} more)")
    
    if req.chatHistory:
        parts.append("\nChat History:")
        for msg in req.chatHistory:
            parts.append(f"  {msg}")

    # Keyword-based cultural retrieval (P0 RAG): culture_cards -> few-shot + assemblyDraft (macro.style)
    try:
        from app.services.keyword_culture_retriever import retrieve_budgeted as _culture_retrieve, retrieve_building_knowledge as _building_knowledge_retrieve
        qtext = (req.requestText or "") + "\n" + (getattr(req, "userMessage", None) or "")
        rag = _culture_retrieve(qtext, **resolve_rag_budget(req))
        if rag:
            # Extract assemblyDraft separately for better visibility
            assembly_draft = rag.get("assemblyDraft")
            if assembly_draft:
                parts.append("\nAssemblyDraft(JSON):")
                parts.append(json.dumps(assembly_draft, ensure_ascii=False, indent=2))
            
            # Include fewShots and hits in CultureRetrieval block
            if rag.get("fewShots") or rag.get("hits"):
                culture_retrieval = {
                    "hits": rag.get("hits", []),
                    "fewShots": rag.get("fewShots", []),
                }
                parts.append("\nCultureRetrieval(JSON):")
                parts.append(json.dumps(culture_retrieval, ensure_ascii=False, indent=2))
    except Exception:
        pass
    
    # Building knowledge retrieval: specific building features -> detailed architectural information
    try:
        from app.services.keyword_culture_retriever import retrieve_building_knowledge as _building_knowledge_retrieve
        qtext = (req.requestText or "") + "\n" + (getattr(req, "userMessage", None) or "")
        building_kb = _building_knowledge_retrieve(qtext, topK=1)
        if building_kb:
            # Extract relevant information for LLM
            building_info = {
                "name": building_kb.get("name"),
                "nameEn": building_kb.get("nameEn"),
                "description": building_kb.get("description"),
                "descriptionZh": building_kb.get("descriptionZh"),
                "features": building_kb.get("features"),
                "dimensions": building_kb.get("dimensions"),
                "assemblyHints": building_kb.get("assemblyHints"),
            }
            parts.append("\nBuildingKnowledge(JSON):")
            parts.append(json.dumps(building_info, ensure_ascii=False, indent=2))
    except Exception:
        pass

    # Provide data-driven style profile candidates (multiple-choice), so the model can set extra.styleProfileId deterministically.
    try:
        from app.services.style_profile_registry import catalog_prompt_block, palette_prompt_block
        qtext = (req.requestText or "") + "\n" + (getattr(req, "userMessage", None) or "")
        block = catalog_prompt_block(max_items=30, query_text=qtext)
        if block:
            parts.append("\n" + block)
        pblock = palette_prompt_block(max_items=30, query_text=qtext)
        if pblock:
            parts.append("\n" + pblock)
    except Exception:
        pass
    
    return "\n".join(parts)
