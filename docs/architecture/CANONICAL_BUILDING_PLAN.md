# Canonical building plan

External protocol: **`LlmPlan` JSON** (evolving). `BuildingSpec` is a **legacy adapter** input for explicit whole-structure generation, not a parallel design authority.

## Sub-modes (one canonical document)

| Sub-mode | Fields | Authority |
|----------|--------|-----------|
| Component | `components[]` | Primary |
| Program | `plan_program`, `plan_skeleton` | Same plan; geometry program from LLM |
| Patch | `mode=patch`, `patch` | Incremental edits |
| Capability failure | `plan_status`, `capability_gap` | No compile; replan |

## New metadata (optional, backward compatible)

### `plan_meta`

| Key | Type | Meaning |
|-----|------|---------|
| `generation_mode` | string | e.g. `component`, `program`, `patch`, `structure_adapter` |
| `allow_compiler_satellites` | boolean | **Transition only:** if true, Java may infer FACADE/ENTRANCE/ROOF/CROWN (tagged COMPILER_INFERRED) |
| `design_requirements` | object | Keys `facade` / `entrance` / `roof` / `crown` → `REQUIRED` \| `OPTIONAL` \| `OMITTED` \| `UNSPECIFIED` |
| `capability_report` | object | See below |

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

Implemented in [DesignParamSource.java](../../src/main/java/com/formacraft/common/generation/design/DesignParamSource.java) and [DesignProvenance.java](../../src/main/java/com/formacraft/common/generation/design/DesignProvenance.java).

**Roof rise:** prefer `params.roof_height` or `params.roof_pitch_degrees` via [RoofPitchGeometry](../../src/main/java/com/formacraft/common/generation/component/util/RoofPitchGeometry.java). Technical `span/3` capped at 8 applies only when neither is set.

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
