# Design decision inventory

Hard-coded or inferred **architectural** choices (not pure geometry). Used as Phase 4–5 backlog. Line numbers are approximate — re-verify on edit.

## P0 — MASS satellite inference (Java)

| Location | Trigger | Design params | Tag | Migration |
|----------|---------|---------------|-----|-----------|
| `ComponentPlanCompiler.java` ~968–974 | MASS without FACADE_WINDOWS | facade rhythm, window_style | DESIGN_INFER | Gated: `plan_meta.allow_compiler_satellites` or `E_DESIGN_INCOMPLETE`; **Extended (2026-11-17):** inferred `window_ratio`/`rhythm` stamp `TECHNICAL_DEFAULT`; classical profile fills stamp `COMPILER_INFERRED` |
| `ComponentPlanCompiler.java` ~976–984 | MASS without ENTRANCE | entrance_type, door width | DESIGN_INFER | Same; **Extended (2026-11-17):** inferred `door_width`/`door_height`/`canopy_depth` stamp `TECHNICAL_DEFAULT` (request-honoured sizes → `COMPILER_INFERRED`) |
| `ComponentPlanCompiler.java` ~1001–1008 | MASS without ROOF | roof_type, roof height (dims) | DESIGN_INFER | Same; height cap `span/3` max 8 at ~1800–1801 |
| `ComponentPlanCompiler.java` ~1014–1027 | MASS without CROWN | crown_type | DESIGN_INFER | Same + `ComponentCrownDecorator` |
| `ComponentPlanCompiler.java` `makeRoofComponent` | missing roof_type / height | roof_type default; height via `RoofPitchGeometry` | DESIGN_INFER | **Wired:** explicit `roof_height` / `roof_pitch_degrees` preferred; no silent cap 8 when pitch set; default gable/flat via `putWithSource` (`COMPILER_INFERRED` / `DERIVED_GEOMETRY` / last-resort `TECHNICAL_DEFAULT`) |
| `ComponentPlanCompiler.java` `resolveDefaultRoofType` | style/heuristic | roof_type | DESIGN_ENRICH | Source-aware `putWithSource` |
| `ComponentCrownDecorator.shouldApply` | typology/heuristic crown | CROWN satellite | DESIGN_INFER | **Extended (2026-11-17):** `design_requirements.crown=OMITTED` / OPTIONAL without explicit template blocks inference |
| `RoofGenerator.java` | rise resolution | height from pitch/height params | GEOMETRY | **Wired:** `RoofPitchGeometry.resolveRise` (pitch 58° on span 30 → rise 24, not 8) |
| `HouseGenerator` / `HouseRoofGenerator` | whole-building roof | hardcoded rise caps | AUTHORITY_SUBSTITUTE | Template path only; do not use for AI-planned pitch — prefer LlmPlan ROOF |

## Style / grammar enrichers (Java)

| Class | Params touched | Tag | Migration |
|-------|----------------|-----|-----------|
| `StyleIntentResolver` | plan_type, roof_type, window_ratio, window_style, horse_head | DESIGN_ENRICH | **Wired:** all enrich writes via `putIfAllowed` |
| `RoofGrammarResolver` | roof_type, specialty, dormers | DESIGN_ENRICH | **Wired:** `putIfAllowed` / locked sources |
| `OpeningGrammarResolver` | window_aspect, window_ratio, void_ratio, rhythm | DESIGN_ENRICH | **Wired:** `putIfAllowed` / locked sources |
| `CrownGrammarResolver` | crown_template, profile | DESIGN_ENRICH | **Wired:** `putIfAllowed` / locked sources |
| Typology interpreters | builder-local geometry params | GEOMETRY / TEMPLATE | Explicit typology hint only; not a parallel design planner |
| `DistinguishingFeaturesBridge` | features, distinctive_features, decorative_elements | DESIGN_ENRICH | **Wired:** skips append when sources locked |
| `NonClassicalEnrichmentGuard` | strips classical roof/facade/crown | DESIGN_ENRICH | **Wired:** `putIfAllowed` / `removeIfAllowed`; keeps locked CROWN |

## Structure routing substitutes (Java)

| Location | Trigger | Effect | Tag | Migration |
|----------|---------|--------|-----|-----------|
| `GeneratorRouter.routeDecision` | unmatched type | was silent `house` | AUTHORITY_SUBSTITUTE | **NEEDS_REPLAN** (no flag override) |
| `GeneratorRouter.routeDecision` | null spec/type | was silent `tower` | AUTHORITY_SUBSTITUTE | **UNSUPPORTED** (no flag override) |
| `UnifiedGeneratorRouter` | empty registry + hints | structure adaptor | AUTHORITY_SUBSTITUTE | Explicit landmark/module only |

## Prompt routing (Java / Python)

| Location | Effect | Migration |
|----------|--------|-----------|
| `PromptAssembler` Assembly block | Forces ASSEMBLY component type | Soft hint by default |
| `planning_request_hints._derive_structured_intent` (`ai_planner` retains import alias) | Structured intent block | Hint only (documented) |
| `plan_architectural_enrichment.infer_heuristic_proportion_card` | Proportion card injection | AI_PLANNED or optional skip |

## Python planner inference

| Symbol | Params | Migration |
|--------|--------|-----------|
| `_infer_scale` / `_infer_terrain` | scale, terrain in context | **Fixed (2026-11-17 P1):** NONBINDING hints; no bare「山」; negation-aware scale |
| `_derive_structured_intent` | purpose/style/scale soft block | **Fixed (2026-11-17 P1):** NONBINDING label; negation; no forced house completeness mandate |
| `llm_plan_system_prompt` category bullets | composition exemplars | **Fixed (2026-11-17 P1):** framed as NON-BINDING EXAMPLES, not routes |
| `_infer_mass_height` | mass height | Protocol repair only |
| `llm_plan_normalization` Chinese roof/courtyard | was DESIGN_OVERRIDE (`xieshan`→`xuanshan`, strip COURTYARD without user keywords) | **Fixed (2026-11-17):** preserve AI-planned values; empty roof only → `xuanshan` TECHNICAL_DEFAULT + `design_sources` |
| `llm_plan_normalization` design_sources stamp | roof_type / plan_type provenance | **Extended (2026-11-17):** TECHNICAL_DEFAULT / AI_PLANNED; user-stated roof → USER_EXPLICIT. **Fixed (scope):** plan-level USER_EXPLICIT only when constraint covers all masses *and* application succeeded; local roofs/windows/entrances stamp component `design_sources` only |
| `llm_plan_normalization._stamp_roof_on_targets` | ROOF rewrite without host_id | **Fixed:** requires `host_id` ∈ target mass ids; unbound ROOFs left intact + `plan_meta.scope_diagnostics` |
| `llm_plan_normalization` allow_overwrite | `all_main_masses` / `plan` | **Fixed (2026-11-17):** explicit whole-compound scopes authorize overwrite of AI roofs (所有建筑都改成歇山顶) |
| `llm_plan_normalization` / Stage-B | `double_eave_xieshan` | **Fixed:** maps to executable `xieshan` + `double_eave`/`eave_tiers` (not a fake RoofTypeCatalog token) |
| `_pick_host_remap` last-resort MASS | unknown host_id | **Fixed:** removed first-MASS fallback; only exact/unique fuzzy/unique courtyard-type remaps |
| `DesignRequirementAccess` priority | plan REQUIRED vs local omit | **Fixed:** mass `roof_type=none` / mass `*_requirement=OMITTED` beat plan_meta REQUIRED |
| `building_contract` specialty roofs | classical 歇山/悬山/硬山/庑殿 | **Extended:** participate in ordinal `building_N` scopes (not only western specialty roofs) |
| `TransitionalSatellitePlans` / migrate whitelist | historical satellite opt-in | **Fixed:** load-time inject only (test sources); migrate script denied for `complex_build_retest` / `llmplan_examples` / eval plans |
| `llm_plan_normalization` explicit omissions | roof/windows/entrance none | **Extended (2026-11-17):** USER_EXPLICIT + design_requirements OMITTED; drop conflicting satellites |
| `ComponentSatelliteInference.prepareComponents` | satellite autofill | **Verified (2026-11-17):** roof_type/window/entrance `none` blocks inference even when allow_compiler_satellites=true |
| `llm_plan_normalization` courtyard literals | plan_type courtyard | **Fixed (2026-11-17):** single-mass or uniquely named courtyard target only; adjacent multi-mass 庭院 no longer forces every MASS |
| `llm_plan_normalization._ensure_compiler_satellite_policy` | `allow_compiler_satellites` explicit stamp | **Fixed (2026-11-17):** never auto-authorizes (`True`); absent → explicit `False`/`ai_owned`; existing scaffold/fixture keys preserved |
| `llm_plan_normalization._apply_user_explicit_design_sources` | roof/courtyard/omissions | **Fixed (2026-11-17):** component-scoped; unscoped multi-mass keywords do not overwrite AI roofs or force courtyard on every MASS |
| `PlanMetaAccess.allowCompilerSatellites` | absent key default | **Fixed (2026-11-17):** opt-in only — absent/null → false (was transitional absent→true) |
| `ComponentSatelliteInference` empty-components recovery | recovered MASS shell | **Extended (2026-11-17):** `recoveredFromEmpty` enables transitional satellite infer for that prepare pass only; recovered `wall_thickness` stamps `TECHNICAL_DEFAULT` |
| `LlmPlanTestFixtures.build` unspecified planMeta | was auto-inject `true` | **Fixed (2026-11-17):** production-aligned absent→false; transitional tests call `allowCompilerSatellites(true)` explicitly (`scripts/add_explicit_compiler_satellites.py`) |
| `ComponentSatelliteInference` wall_thickness=1 fill | hollow MASS geometry fill | **Extended (2026-11-17):** `putWithSource(..., TECHNICAL_DEFAULT)` when absent |
| `scripts/check_design_authority_invariants.py` | CI static gate | **Added (2026-11-17):** ban keyword→preset returns; require Guard files |
| `infer_heuristic_proportion_card` / `proportion_prompt_block` / `ProportionCardRegistry.promptBlockForCard` | proportion typology + aiInstruction | **Fixed (2026-11-17):** NON-BINDING research hint; softened MUST → prefer; negation-aware marker hits |
| `merge_request_intent` / `RequestIntentInjector.applyKeywordAuthority` | keyword `building_class` | **Extended (2026-11-17):** stamp `building_class_source` (`keyword_fill` / `keyword_user_marker` / `llm_retained`); scaffold still requires keyword agreement; strong markers remain USER_EXPLICIT class cues |
| `assembly_plan_validator.resolve_preset_for_intent` / `auto_apply_assembly_presets` | was keyword→preset DESIGN_OVERRIDE | **Fixed (2026-11-17):** explicit `preset:<id>` only; incomplete ASSEMBLY → repair/`capability_gap` |
| `assembly_plan_repair` | assembly graph | Protocol only, not design substitute |
| `typology_plan_repair` | typology fields | Protocol only |

## Generator registry (appendix)

| Registry | Role | Class |
|----------|------|-------|
| Component | GeometryExecutor when plan complete | `ComponentGeneratorRegistry` |
| Structure | TemplateExecutor when explicit | `StructureGeneratorRegistry` |
| Catalog keys `house`, `tower`, … | Structure | Only via `StructureRouteCatalog` / explicit route — silent fallback removed |

## Test / CI coverage

| Item | Test |
|------|------|
| MASS-only no satellites | `DesignFidelityTest.massOnlyWithoutSatellitesFails` |
| OMITTED design requirements | `CompilerSatellitePolicyTest` |
| Pipeline sub-decision | `ComponentPipelineRouteResolverTest` |
| Steep roof pitch fidelity | `RoofPitchGeometryTest`, `RoofGeneratorPitchFidelityTest`, fixture `plans/fidelity/gothic_steep_roof.json` |
| Grammar provenance | `DesignProvenanceGrammarTest` |
| Unmatched BuildingType | `GeneratorRouterCapabilityTest` |
| Assembly prompt soft rule | `AssemblyIntentSectionsTest` |
| Proportion prompt non-binding | `ProportionCardRegistryTest`, `test_plan_architectural_enrichment.py` |
| Keyword class provenance | `PlanIntentFidelityRepairTest.matchingEchoUserMarkersRepairWrongLlmClassWithProvenance`, `test_intent_baseline_and_negation.py` |
| Crown OMITTED + gable provenance | `ExplicitOmissionSatelliteTest` |
| Compiler satellite policy stamp | `test_ai_design_preservation.test_incomplete_mass_shell_does_not_auto_authorize_satellites` |
| Scoped user roof/courtyard | `test_ai_design_preservation.test_multi_mass_roof_and_adjacent_courtyard_stay_scoped` |
| Transitional fixture migration | `scripts/migrate_transitional_satellite_fixtures.py` |
| wall_thickness TECHNICAL_DEFAULT | `ExplicitOmissionSatelliteTest.missingWallThicknessFillIsTaggedTechnicalDefault`, `recoveredEmptyComponentsWallThicknessIsTaggedTechnicalDefault` |
| Opt-in allow_compiler_satellites | `PlanMetaAccessTest`, `HostedStructuralJoinTest.defaultCompletionIsOptInOnly` |
