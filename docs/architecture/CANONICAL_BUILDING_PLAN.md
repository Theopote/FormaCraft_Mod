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

- Authority for storey spacing is [ResolvedComponentGeometry](../../src/main/java/com/formacraft/common/generation/component/util/ResolvedComponentGeometry.java) (`floor_height`, `floor_count`, `floorYs`, `floorLayoutFits`).
- Generators must not silently raise computed storey height with `Math.max(3, height/floorCount)` when the envelope is short. Explicit `floor_count` × `floor_height` exceeding envelope height → `E_FLOOR_LAYOUT_OVERFLOW`.

**MassMain fidelity notes (Round-7 audit):**

| Param / behavior | Preserved? | Notes |
|------------------|------------|-------|
| `dimensions` | yes | envelope respected; plate mode separate |
| `roof_type=none` | yes | clears mass-embedded roof |
| `window_style=none` / `suppress_windows` | yes | blocks default window fill |
| `entrance_type=none` | yes | blocks default door fill |
| explicit `void_ratio` | yes (R7) | size heuristic must not re-hollow/solid override when ratio is set |
| `floor_count` / `floor_height` | partial | shared geometry resolve exists; facade no longer raises min storey; overflow gated |
| setbacks / `ResolvedFacadeLayers` | mostly | still style-influenced when params omit layers |
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
