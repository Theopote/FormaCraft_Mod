# Canonical building plan

External protocol: **`LlmPlan` JSON** (evolving). `BuildingSpec` is a **legacy adapter** input for explicit whole-structure generation, not a parallel design authority.

## Sub-modes (one canonical document)

| Sub-mode | Fields | Authority |
|----------|--------|-----------|
| Component | `components[]` | Primary |
| Program | `plan_program`, `plan_skeleton` | Same plan; geometry program from LLM |
| Patch | `mode=patch`, `patch` | Incremental edits |
| Capability failure | `plan_status`, `capability_gap` | No compile; replan |

## Design authority vs geometry executor

Two layers keep routing contracts precise without rewriting deterministic solvers:

| Layer | Meaning | Examples |
|-------|---------|----------|
| **Design authority** | Who owns the component plan (LLM / typology / assembly payload) | `generation_authority=TYPOLOGY` on a stair keeps typology as the design owner |
| **Geometry executor** | Which deterministic algorithm materializes patches | `STAIR_COMPONENT` runs `StraightStairComponentGenerator`; `COMPONENT_PIPELINE` runs registry → typology → structure stages |

Typology may **reuse** COMPONENTS-family geometry executors (e.g. stairs) when the plan already specifies endpoints, width, and materials. Provenance on [`RoutedGenerationResult`](../../src/main/java/com/formacraft/server/generation/component/adaptor/RoutedGenerationResult.java) records both: `authority=TYPOLOGY`, `executor=STAIR_COMPONENT`.

Invalid `generation_authority` strings are rejected at **Java parse** ([`LlmPlanParser`](../../src/main/java/com/formacraft/common/llm/parser/LlmPlanParser.java)); omitted values default to `COMPONENTS` at resolve time only.

## New metadata (optional, backward compatible)

### `plan_meta`

| Key | Type | Meaning |
|-----|------|---------|
| `generation_mode` | string | e.g. `component`, `program`, `patch`, `structure_adapter` |
| `generation_authority` | string | `COMPONENTS` \| `TYPOLOGY` \| `ASSEMBLY` \| `CAPTURED_LIBRARY` \| `SKELETON` — routing contract (see [GenerationRouteContract](../../src/main/java/com/formacraft/server/generation/component/adaptor/GenerationRouteContract.java)); default `COMPONENTS`. Also on `components[].params`. `STAIR` / `STAIR_SYSTEM` / `STAIRS` use the stair executor only under `COMPONENTS` or `TYPOLOGY`; forbidden under `ASSEMBLY`, `SKELETON`, `CAPTURED_LIBRARY`. |
| `allow_typology_empty_fallback` | boolean | Under `COMPONENTS`, allows typology executor when the component base is empty (component params override plan_meta). |
| `allow_structure_empty_fallback` | boolean | Under `COMPONENTS`, allows structure adaptor when the component base is empty (component params override plan_meta). |
| `allow_compiler_satellites` | boolean | **Transition only:** if true, Java may infer FACADE/ENTRANCE/ROOF/CROWN (tagged COMPILER_INFERRED) |
| `design_requirements` | object | Keys `facade` / `entrance` / `roof` / `crown` → `REQUIRED` \| `OPTIONAL` \| `OMITTED` \| `UNSPECIFIED` |
| `capability_report` | object | See below |

**Fallback policy** ([GenerationFallbackPolicy.java](../../src/main/java/com/formacraft/common/generation/routing/GenerationFallbackPolicy.java), matrix in [GenerationFallbackMatrix.java](../../src/main/java/com/formacraft/common/generation/routing/GenerationFallbackMatrix.java)): authority controls empty-base typology/structure fallback separately from typology *replace*. Default `COMPONENTS`: registry priming only — no typology or structure empty fill unless `allow_typology_empty_fallback` / `allow_structure_empty_fallback`. **Those explicit flags apply only under `COMPONENTS`**; `TYPOLOGY` / `CAPTURED_LIBRARY` / `ASSEMBLY` / `SKELETON` ignore them and follow policy only. Non-empty typology replace under `COMPONENTS` still requires `allow_typology_override` on the component. Dedicated authorities must not silently fall back to another executor; mismatch → `E_GENERATION_AUTHORITY_CONFLICT`.

| Authority | Typology empty (base) | Structure empty (base) | `allow_typology_empty_fallback` | `allow_structure_empty_fallback` |
|-----------|----------------------|------------------------|-----------------------------------|----------------------------------|
| COMPONENTS | policy (false) | policy (false) | enables typology empty | enables structure empty |
| TYPOLOGY | policy (true) | policy (false) | ignored | ignored |
| CAPTURED_LIBRARY / ASSEMBLY / SKELETON | false | false | ignored | ignored |

### `capability_report`

| Field | Values |
|-------|--------|
| `status` | `SUPPORTED`, `COMPOSABLE`, `NEEDS_REPLAN`, `UNSUPPORTED` |
| `reason` | machine code |
| `message_zh` | optional player text |

Shared enum: [GenerationCapability.java](../../src/main/java/com/formacraft/common/generation/routing/GenerationCapability.java).

### `design_sources`

Map of param name → source enum (plan-level and/or `components[].params.design_sources`):

- `USER_EXPLICIT` — must not be silently changed
- `AI_PLANNED` — must not be silently changed
- `DERIVED_GEOMETRY` — Java may refine within constraints
- `TECHNICAL_DEFAULT` — deterministic defaults OK

Implemented in [DesignParamSource.java](../../src/main/java/com/formacraft/common/generation/design/DesignParamSource.java), [DesignProvenance.java](../../src/main/java/com/formacraft/common/generation/design/DesignProvenance.java), and alias groups in [DesignParameterRegistry.java](../../src/main/java/com/formacraft/common/generation/design/DesignParameterRegistry.java). **LLM output should use snake_case canonical keys** (e.g. `window_ratio`, not `windowRatio`).

**Roof geometry:**

- `components[].dimensions.height` = component bounding box height (not ridge rise).
- `params.roof_height` / `params.roof_pitch_degrees` = explicit roof rise design params ([RoofPitchGeometry](../../src/main/java/com/formacraft/common/generation/component/util/RoofPitchGeometry.java)). Invalid explicit values (height ≤ 0, pitch ∉ (0, 89), non-numeric) → `E_ROOF_GEOMETRY_INVALID`. When both are set they must agree within ±1 block or compile fails with `E_ROOF_GEOMETRY_CONFLICT`. Generators must not fall back to technical defaults for invalid explicit params.
- Technical `span/3` capped at 8 applies only when neither rise param is set.
- `roof_type=none` is an explicit opt-out ([ExplicitDesignPolicy](../../src/main/java/com/formacraft/common/style/ExplicitDesignPolicy.java)): no roof geometry; hosted ROOF contradicting the host opt-out → `E_EXPLICIT_DESIGN_CONFLICT`.

**Supported `roof_type` tokens** (explicit values only; unknown explicit values → `E_ROOF_TYPE_UNSUPPORTED`, not silent flat):

`flat`, `gable`, `hip`, `double_gable`, `xieshan`, `xuanshan`, `pyramid`, `cone`, `dome`, `mansard`, `none` (omit roof). Omitted `roof_type` may derive from style/genome defaults via [RoofTypeCatalog](../../src/main/java/com/formacraft/common/generation/component/util/RoofTypeCatalog.java).

**Facade / openings:**

- Explicit `window_aspect` must not be overridden by features (`large` → punch) or style inference (`arrow_slit`). Incompatible explicit `window_aspect` + `window_style` → `E_WINDOW_ASPECT_CONFLICT` ([FacadeDesignValidator](../../src/main/java/com/formacraft/server/compiler/FacadeDesignValidator.java)).
- Feature-based aspect inference applies only when `window_aspect` is omitted.

**Floor layout:**

- Authority for storey spacing is [ResolvedFloorLayout](../../src/main/java/com/formacraft/common/generation/component/util/ResolvedFloorLayout.java) (and [ResolvedComponentGeometry](../../src/main/java/com/formacraft/common/generation/component/util/ResolvedComponentGeometry.java)): explicit `floor_height` → else `envelope/floor_count` (≥1) → technical default 3.
- Generators must not silently raise band height with `Math.max(3, floorHeight)`. Explicit `floor_count` × `floor_height` exceeding envelope height → `E_FLOOR_LAYOUT_OVERFLOW`.

**Mass / roof / plate alignment (Round-8/9/10/11/12/13/14/15/16/17/18/19/20):**

- Plan occupancy authority is [ResolvedFootprint](../../src/main/java/com/formacraft/common/generation/component/util/ResolvedFootprint.java). MassMain and [ComponentFootprintMask](../../src/main/java/com/formacraft/common/generation/component/util/ComponentFootprintMask.java) (roofs/seams) share one courtyard hole: `hole = min(round(span*ratio), span - 2*wall_thickness)`, centered.
- Shared read-only aggregation: [ResolvedBuildingGeometry](../../src/main/java/com/formacraft/common/generation/component/util/ResolvedBuildingGeometry.java) = footprint + [ResolvedFloorLayout](../../src/main/java/com/formacraft/common/generation/component/util/ResolvedFloorLayout.java) + immutable facade layers (MassMain / FacadeWindows / hosted plates).
- Compile-scoped cache: [BuildingGeometryContext](../../src/main/java/com/formacraft/common/generation/component/util/BuildingGeometryContext.java) (`begin` / `putMass` / `end` in [ComponentPlanCompiler](../../src/main/java/com/formacraft/server/compiler/ComponentPlanCompiler.java)) so mass, facade, and plates share one host geometry instance.
- Hosted interior plates (`extrude_mode=plate` without terrace/bridge role): occupancy clip via [HostedPlateOccupancy](../../src/main/java/com/formacraft/common/generation/component/util/HostedPlateOccupancy.java) against host footprint (+ stepped layers). Prepare AABB-insets by `wall_thickness` **only for INTERIOR** (Round-13); irregular voids clipped at emit time.
- Plate Y (Round-12/14/15): storey snap only for **INTERIOR** + inferred. TERRACE/BRIDGE never use interior `floor_count` snap. Bridge `connection_level` sets Y only for inferred plates; explicit Y must match `hostOrigin + level * floor_height` or → `E_BRIDGE_LEVEL_CONFLICT` (never silently overwrite). Role envelopes: INTERIOR `[0..height]`, TERRACE `[0..height+1]`, BRIDGE no single-host height gate.
- Bridge dual-host (Round-14–20): explicit bridge requires `target_host_id` (≠ `host_id`); XZ AABB must touch both footprints (`E_BRIDGE_ENDPOINT_NO_CONTACT`); plate Y must sit in each mass vertical envelope (`E_BRIDGE_ENDPOINT_LEVEL_MISMATCH`). Portals carve depth = `wall_thickness` at clear heights `plateY+1/+2`, shifted by the same `slotAnchor` as plate patches. Post-compile walkability is **plan-geometry** with precision tag `FAST` (string heuristics) or `BLOCKSTATE` (`preferRegistry` refine) — neither is a live-world VoxelShape / player-AABB certificate. [PlannedOccupancy](../../src/main/java/com/formacraft/common/generation/component/util/PlannedOccupancy.java) `CollisionProfile` + `supportTopEighths()` (FULL/STAIR/SLAB_TOP=8, SLAB_BOTTOM=4, CARPET=1); unknown non-air → `FULL`. Do not grow string heuristics for doors/gates — that belongs in a future VoxelShape layer. [WalkabilityGraph](../../src/main/java/com/formacraft/common/generation/component/util/WalkabilityGraph.java): `StandingPose` + `|Δsurface|≤1` block; `SearchBudget(maxDepth,maxVisits)`; **hard `maxVisits`**: stop after dequeue when `visits > maxVisits` (goal must be dequeued to count as `REACHABLE`; queued-only goal → `SEARCH_LIMIT`); enqueue also refuses when `seen.size() >= maxVisits`. Depth deferred-frontier semantics unchanged (R19). Results: `WalkabilityOutcome(result, precision)`; bridge `evaluateBridgeDetailed` → `BridgeWalkOutcome`; compiler diagnostics include `precision=`. Building-scale circulation tests: floor→stair→corridor→bridge ([BuildingCirculationWalkTest](../../src/test/java/com/formacraft/common/generation/component/util/BuildingCirculationWalkTest.java)). Exhausted depth with no frontier → `UNREACHABLE` / `E_BRIDGE_PATH_BLOCKED`; true early stop → `E_WALKABILITY_SEARCH_LIMIT`. See [HostedPlateDesignValidator](../../src/main/java/com/formacraft/server/compiler/HostedPlateDesignValidator.java) / [BridgeWalkability](../../src/main/java/com/formacraft/common/generation/component/util/BridgeWalkability.java).
- Host binding (Round-12/13): unknown `host_id` → `E_HOST_ID_UNRESOLVED`; missing `target_host_id` on bridge → `E_TARGET_HOST_ID_REQUIRED`; interior missing host geometry → `E_HOST_GEOMETRY_MISSING`; duplicate mass `component_id` → `E_DUPLICATE_COMPONENT_ID`.
- Explicit `plate_role` catalog: [PlateRoleCatalog](../../src/main/java/com/formacraft/common/generation/component/util/PlateRoleCatalog.java). Unknown explicit token → `E_PLATE_ROLE_UNSUPPORTED`. Omit role → INTERIOR. Terrace/bridge skip footprint clip **and** wall inset (Round-13).
- Explicit `plan_type` catalog: [PlanPatternCatalog](../../src/main/java/com/formacraft/common/generation/component/util/PlanPatternCatalog.java). Unknown explicit token → `E_PLAN_PATTERN_UNSUPPORTED` (never silently becomes rectangle).
- Explicit `shape` / `footprint_shape` catalog: [FootprintShapeCatalog](../../src/main/java/com/formacraft/common/generation/component/util/FootprintShapeCatalog.java). Unknown explicit token → `E_FOOTPRINT_SHAPE_UNSUPPORTED`.
- Explicit `shape=circle` + explicit non-courtyard `plan_type` (cross / l_shape / cut_corners) → `E_FOOTPRINT_COMBINATION_UNSUPPORTED`.
- Style / features / genome must **not** invent mass topology (`plan_type`). [StyleIntentResolver](../../src/main/java/com/formacraft/common/style/StyleIntentResolver.java) only disambiguates explicit rectangle shape → `plan_type=none`; Gothic/Chinese labels alone do not force cross/courtyard/cut_corners.
- `alignRoofToMass` copies host `wall_thickness` / plan pattern params onto roofs so mask matches the mass shell.
- Explicit `courtyard_ratio` is not clamped to 0.2–0.6. Key present but non-numeric / `<=0` / `>=1` → `E_COURTYARD_RATIO_INVALID`; legal but unbuildable → `E_COURTYARD_GEOMETRY_CONFLICT` ([MassDesignValidator](../../src/main/java/com/formacraft/server/compiler/MassDesignValidator.java)).
- Explicit `wall_thickness` has no fixed cap of 4. Key present but non-numeric / `<=0` → `E_WALL_THICKNESS_INVALID`; hollow masses require `2 * thickness < min(width,depth)` else `E_WALL_THICKNESS_UNFIT`.
- `alignRoofToMass` must not raise a locked/explicit `overhang` from roof bbox; mismatch → `E_ROOF_OVERHANG_CONFLICT`.
- Explicit `arm_width` / `l_cut` / `corner_cut` exceeding footprint−2 → `E_PLAN_PATTERN_UNFIT` (no silent truncate of explicit sizes in resolve).

**MassMain fidelity notes (Round-7/8/9/10/11/12/13 audit):**

| Param / behavior | Preserved? | Notes |
|------------------|------------|-------|
| `dimensions` | yes | envelope respected; plate mode separate |
| `roof_type=none` | yes | clears mass-embedded roof |
| `window_style=none` / `suppress_windows` | yes | blocks default window fill |
| `entrance_type=none` | yes | blocks default door fill |
| explicit `void_ratio` | yes (R7) | size heuristic must not re-hollow/solid override when ratio is set |
| `courtyard_ratio` | yes (R8/R9) | no aesthetic clamp; INVALID vs CONFLICT gates |
| `wall_thickness` | yes (R8/R9) | no cap at 4; INVALID vs UNFIT; shared via ResolvedFootprint |
| footprint / courtyard hole | yes (R9) | `ResolvedFootprint` sole occupancy authority |
| explicit `plan_type` / `shape` | yes (R10) | catalog gate; UNSUPPORTED not silent rectangle |
| circle + cross (etc.) | yes (R10) | `E_FOOTPRINT_COMBINATION_UNSUPPORTED` |
| style → plan topology | no (R10) | style must not invent `plan_type` |
| hosted interior plate clip | yes (R11) | host footprint+layers; not AABB-only |
| `plate_role` terrace/bridge | yes (R11–R15) | skip clip+inset; role Y envelopes |
| explicit plate Y | yes (R12/R14/R15) | snap only INTERIOR+inferred; bridge level conflict |
| plate `host_id` | yes (R12) | unresolved / missing geometry → `E_*` |
| bridge `target_host_id` | yes (R14–R20) | required; endpoints + portals + hard visit budget + precision-tagged walkability |
| unique `component_id` | yes (R13) | `E_DUPLICATE_COMPONENT_ID` |
| `overhang` vs roof bbox | yes (R8) | conflict, not silent max |
| `arm_width` / `l_cut` / `corner_cut` | yes (R8) | unfit when explicit > footprint−2 |
| `floor_count` / `floor_height` | yes (R8) | `ResolvedFloorLayout` shared by Mass+Facade |
| `setback_ratio` | yes | clamped only to [0,1]; not rewritten |
| default doors/windows without features | technical | only when not explicitly disabled |

**Parse boundary:** both [`LlmPlanParser.parse`](../../src/main/java/com/formacraft/common/llm/parser/LlmPlanParser.java) and `parseAndValidate` reject invalid `generation_authority`; [`ComponentPlanCompiler`](../../src/main/java/com/formacraft/server/compiler/ComponentPlanCompiler.java) also rejects via `E_INVALID_GENERATION_AUTHORITY` if a plan bypasses parse.

## BuildingSpec mapping (legacy adapter)

| BuildingSpec use | Canonical direction |
|------------------|---------------------|
| `outputFormat=buildingspec` | Adapter until converted to LlmPlan or explicit module |
| `extra.landmark` / `template` / `assembly` | Explicit TemplateExecutor — OK |
| Unmatched `BuildingType` | **NEEDS_REPLAN**, not silent `house` |
| `extra.allow_legacy_fallback=true` | Dev/test only: old house/tower fallback |

## Python schema

Optional fields added in [llm_plan_json_schema.py](../../python_backend/app/models/llm_plan_json_schema.py) (`strict=False` path).

## Gradual merge milestones

1. All ChatPanel BUILD traffic → LlmPlan with complete satellites (no compiler infer).
2. BuildingSpec only for city/composite/explicit structure modules.
3. Structure generators registered as geometry/template plugins keyed by explicit plan references.

See updated [MIGRATION_LLMPLAN_VS_BUILDINGSPEC.md](../MIGRATION_LLMPLAN_VS_BUILDINGSPEC.md).
