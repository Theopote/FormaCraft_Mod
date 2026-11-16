# FormaCraft — AI Design Authority Guardrails (Proposal v1)

## Purpose
The system must accept open-ended architectural language. The finite set of Java/Python executors describes **geometry capabilities**, not an exhaustive catalog of buildings. An AI-planned building can be composed of supported generic primitives even if its name/style was never enumerated.

**Authority principle**: user's explicit request > LLM's valid architectural plan > documented non-binding advice / technical defaults. Independent hard constraints (selection bounds, safety, BlockState validity, collision, geometry capability) must be obeyed and may produce an error or replan rather than a silent substitution.

## Existing authoritative sources
- Root `ARCHITECTURE.md`: one default LlmPlan pipeline; explicit BuildingSpec compat only.
- `docs/architecture/GENERATION_AUTHORITY_MAP.md`: planner/executor ownership.
- `docs/architecture/CANONICAL_BUILDING_PLAN.md`: `design_sources` provenance, `capability_gap`, `allow_compiler_satellites` transition policy.
- `docs/architecture/DESIGN_DECISION_INVENTORY.md`: update whenever changes affect architectural choices.

This document supplements those contracts; it does not override them.

## Decision taxonomy
| Category | Example | Allowed? | Requirement |
|---|---|---|---|
| HARD_CONSTRAINT | protected zone, selection, max build volume, illegal BlockState | Yes | Validate/reject with specific reason |
| GEOMETRY | stair support, roof pitch geometry from planned value, sockets, patches | Yes | Preserve intended form and proportions |
| FORMAT_REPAIR | malformed vector, alias, missing required JSON field | Yes (bounded) | Must not change architectural design |
| NONBINDING_HINT | style RAG, examples, material suggestion, keyword intent guess | Yes with caution | Original user text wins; no route changes; audit bias |
| EXPLICIT_TEMPLATE | user writes `preset:<id>` / `typology:<id>` | Yes | Preserve explicit opt-in, no implicit activation |
| DESIGN_OVERRIDE | change AI `roof_type`, change layout/courtyard, force assembly preset | No | Replan with AI or report capability_gap |

## Failure semantics
1. Missing geometry/unsupported component: produce error with missing capability and request a bounded model replan, or return `plan_status=capability_gap`.
2. Invalid explicit dimensions/roof choices: reject or ask model to repair **while preserving user constraints**; never replace with nearest supported type silently.
3. Non-model environments can expose a clearly labeled manual fallback, but never mislabel it an AI design.
4. Preview/Apply must derive from the same validated final BlockPatch set, not different implicit generation paths.

## Provenance & observability
Track `user_intent`, `raw_llm_plan`, `normalized_plan`, `compiler_decisions`, `effective_patch`, `selected_executor`, `model_id`, `provider`, `schema_version`, `design_sources`, and `capability_gap` for one generation attempt (subject to privacy/logging policy). Store a **design-field diff** for major components; redact prompt secrets. A changed design field requires explicit source/authorization. Suggested metrics: `silent_design_override_count` (target zero), `unrequested_template_route_count` (zero), `capability_gap_rate`, `successful_novel_prompt_rate`, `explicit_constraint_preservation_rate` (target 100%).

## Current repo candidate reviews
1. `python_backend/app/services/llm_plan_normalization.py`: **Fixed (2026-11-17 Phase B)** — AI-planned `roof_type` / courtyard layout are preserved; empty Chinese roof may receive `xuanshan` as TECHNICAL_DEFAULT only. Covered by `test_ai_design_preservation.py` and `test_design_authority_regressions.py`.
2. `python_backend/app/services/assembly_plan_validator.py`: **Fixed (2026-11-17 Phase B)** — `resolve_preset_for_intent` / `auto_apply_assembly_presets` require explicit `preset:<id>`; incomplete ASSEMBLY goes repair → `capability_gap`.
3. `python_backend/app/services/planning_request_hints.py`: **Fixed (2026-11-17 P1)** — bare 「山」removed; roof tokens (歇山/悬山) stripped before terrain inference; scale/intent keywords respect negation (`不要大型`).
4. `python_backend/app/services/llm_plan_system_prompt.py`: **Fixed (2026-11-17 P1)** — category composition bullets framed as NON-BINDING EXAMPLES; residential kit is not a universal default.
5. `llm_plan_normalization` `design_sources`: **Extended (2026-11-17)** — stamps `TECHNICAL_DEFAULT` / `AI_PLANNED`, and upgrades player-stated roofs to `USER_EXPLICIT` (component + plan-level) so Java `DesignProvenance.putIfAllowed` / `StyleIntentResolver` cannot silently rewrite them.
6. Explicit omissions (`无屋顶` / `不要窗` / `不要门`): **Extended (2026-11-17)** — normalization stamps `none` + `USER_EXPLICIT`, sets `design_requirements=OMITTED`, drops conflicting ROOF/FACADE/ENTRANCE satellites; Java `prepareComponents` respects `ExplicitDesignPolicy` even with `allow_compiler_satellites=true` (covered by `ExplicitOmissionSatelliteTest`).
7. Explicit courtyard language (`庭院` / `四合院`): **Extended (2026-11-17)** — locks `plan_type=courtyard` as `USER_EXPLICIT`; StyleIntentResolver must not rewrite to `cut_corners`.
8. Open-world routing regressions + CI invariant script (`scripts/check_design_authority_invariants.py`) guard against keyword→preset resurrection and missing governance files.
9. Proportion ontology prompts (`proportion_prompt_block` / `ProportionCardRegistry` / heuristic cards): **Fixed (2026-11-17)** — NON-BINDING research hint; no MUST composition/template language; negated markers do not select packs.
10. Request-intent `building_class`: **Extended (2026-11-17)** — provenance stamps (`keyword_fill` / `keyword_user_marker` / `llm_retained`); scaffold templates still require keyword agreement.
11. Compiler satellite edges: **Extended (2026-11-17)** — default gable/flat roofs stamp provenance via `DesignProvenance.putWithSource`; `design_requirements.crown=OMITTED` blocks typology-triggered CROWN inference (`ExplicitOmissionSatelliteTest`).
12. Facade/entrance inferred defaults: **Extended (2026-11-17)** — `makeFacadeComponent` / `makeEntranceComponent` stamp `TECHNICAL_DEFAULT` / `COMPILER_INFERRED` via `putWithSource` (`ExplicitOmissionSatelliteTest`).
13. `allow_compiler_satellites` migration: **Fixed (2026-11-17)** — Python `_ensure_compiler_satellite_policy` stamps explicit true/false on normalized plans; Java `PlanMetaAccess.allowCompilerSatellites` is now **opt-in only** (absent/null → false). Empty-components MASS recovery still enables transitional infer for that prepare pass. wall_thickness fill stamps `TECHNICAL_DEFAULT`.

## Required metamorphic acceptance suite
Run original request and related variants; test both plan-level and compiled Patch-level properties.

| Family | Input transformation | Must stay invariant | Allowed to vary |
|---|---|---|---|
| Synonyms | '玻璃屋顶的公共大厅' vs paraphrase | glass roof, public use | geometry composition |
| Negation | '大型建筑' → '不要大型建筑' | must not preserve LARGE bias | size choice within constraints |
| Cultural blend | '中式立面+现代悬挑结构' | both styles coexist | facade motifs |
| Unseen type | coined fictional building with spatial requirements | dimensions, relationships, functions | shape style |
| Design integrity | model gives `roof_type=xieshan` vs omitted roof type | explicit planned xieshan preserved | technical default only if omitted |
| Omission | '不需要窗户/入口' | no injected windows/entrance | valid access may require warning |
| Explicit preset | `preset:...` vs ordinary description | exact routing only on opt-in | execution implementation |
| Assembly failure | missing graph/ops | no injected unrelated preset | replan or capability_gap |
| Environment | same plan, different allowed placement | design is not renamed | terrain/support geometry |

Do not require different model providers to produce unique designs; do require every valid design to meet explicit constraints and be compiled without changing its meaning.

## Code review checklist
- Was any new keyword, type or style list added? Is it *advisory* or *authoritative*?
- Did the repair/default branch mutate an existing planned field? Before/after example?
- Does it change routing to MODULE/STRUCTURE/typology or a fixed ASSEMBLY preset without opt-in?
- Could a completely new architecture be assembled from existing composable operations?
- Does the failure reveal a capability gap instead of producing an unrelated house?
- Are negative/unknown/contradictory prompts covered in tests?
- Did the author update `DESIGN_DECISION_INVENTORY.md` and report actual test outcomes?

## Recommended CI strategy
- **Hard gate (deterministic)**: tests for provenance-preserving normalization, explicit preset-only routing, selection/protected constraints, no silent fallback, and JSON/geometry validity.
- **Review gate**: diff scan in critical planner/prompt/compiler/repair paths; mark keyword/style/typology changes for mandatory Design Authority review (not a naive blanket ban on `if` or enum).
- **Quality gate (non-deterministic)**: benchmark set of novel requests and model providers, with reproducible recorded raw plans and deterministic compiler tests. Report explicit-constraint preservation and undesired plan similarity trends; avoid brittle exact-geometry snapshots across different LLMs.

## Recommended workflow
1. Fix verified plan mutation/implicit preset overwrite paths before adding new presets.
2. Add plan provenance regression fixtures and opt-out tests.
3. Tie Design Authority review to every generation PR via `AGENTS.md`, Cursor rule, Skill, and PR checklist.
4. Add CI hard gates in existing `python_backend/tests`, Java tests and `.github/workflows` after confirming test commands on the target branch.
5. Expand FBD/ASSEMBLY/parameterized operators when prompts reveal genuine geometry limits, instead of adding more building-name triggers.
