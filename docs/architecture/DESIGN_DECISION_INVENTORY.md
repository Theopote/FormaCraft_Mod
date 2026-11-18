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
| `llm_plan_normalization` allow_overwrite | `all_main_masses` / `plan` | **Fixed (2026-11-17):** only `scope_source=EXPLICIT_QUANTIFIER` (or EXPLICIT) authorizes whole-compound overwrite; INFERRED unresolved names do not |
| `building_contract._refine_roof_scope` | default `all_main_masses` | **Fixed (2026-11-17):** missing building name → `scope=unresolved` + `scope_source=INFERRED`; explicit quantifiers only → `all_main_masses` |
| `llm_plan_normalization` / Stage-B | `double_eave_xieshan` | **Fixed:** maps to executable `xieshan` + `double_eave`/`eave_tiers` (not a fake RoofTypeCatalog token) |
| `apply_stage_b_capability_repair` locale roof | unsupported → xieshan | **Removed (2026-11-17):** no 日式/庭院 keyword → xieshan DESIGN_OVERRIDE; unknown roofs stay for capability_gap |
| `_pick_host_remap` | unknown host_id | **Fixed:** exact / case-insensitive id only; fuzzy substring and courtyard-type guesses removed |
| `_stamp_roof_on_targets` applied_all | MASS-only check | **Fixed:** also requires hosted ROOF/ROOF_STRUCTURE consistency; unbound roofs block plan-level USER_EXPLICIT lock |
| `DesignRequirementAccess` priority | plan REQUIRED vs local omit | **Fixed:** only USER_EXPLICIT `none`/`OMITTED` beats plan REQUIRED; AI_PLANNED none does not |
| Strict-mode prepare/compile | AI none vs REQUIRED; multi-mass roofs | **Added (2026-11-17):** `StrictModeDesignScopeIntegrityTest` (+ registry bootstrap) — AI none → `E_DESIGN_INCOMPLETE`; sealed USER_EXPLICIT none allowed; flat+xieshan multi-mass survives prepare→BlockPatch; enclosed `garden_pavilion` keeps openings |
| `_apply_user_explicit_design_sources` intent forbids / fallback none | was `scope=plan` without quantifier | **Fixed (2026-11-18):** multi-mass unresolved omits → `E_OMIT_SCOPE_AMBIGUOUS` (blocking); never escalate keyword forbids to every MASS |
| `_user_reqs` / named roof bind | unresolved scopes dropped | **Fixed (2026-11-18):** keep unresolved scopes; unique pavilion/hall name bind may overwrite that host only; else `E_ROOF_HOST_UNRESOLVED` |
| `_strip_forged_user_explicit` | LLM `design_sources=USER_EXPLICIT` | **Added (2026-11-18):** demote forged USER_EXPLICIT + strip Java seal markers before server re-stamp |
| `DesignRequirementAccess.resolveOmitAware` | mass REQUIRED/OPTIONAL before plan | **Fixed (2026-11-18):** only `UserConstraintContract` sealed keys beat plan_meta; forged JSON USER_EXPLICIT ignored |
| `PavilionOpenFrameRepair.applyOpenFrame` | id/role pavilion → force open | **Fixed (2026-11-18):** open-frame only when already open / open-pavilion features; `looksAuthoredEnclosed` preserves walls/windows/doors |
| `RequestIntentInjector.applyServerConstraintsToComponents` | height seals only | **Extended (2026-11-18):** FormaRequest text mints roof/window/entrance OMITTED seals; prior seals restamped after strip. **Fixed (2026-11-18 host consistency):** `ensure(null)` must not rewrite height from untrusted echo; omit host scoring excludes `componentType`/`MASS_MAIN`/`main`; unique max score only; Python `target_components` must agree or `E_DESIGN_OMIT_HOST_SCOPE` |
| `RequestIntentInjector.heightRoleScore` / `resolveDesignOmitBindings` | MASS_MAIN→hall false positive; first-in-list on ties; domain-aggregated Py match; Python rescue of ties | **Fixed (2026-11-18):** identity fields only; ties/unscoped → blocking `capability_gap`; per-declaration Py match (span/source/scope); **no** plan `target_components` rescue of Java ambiguity |
| `RequestIntentInjector.resolveHeightBindings` | single height cue → primary even with pavilion role | **Fixed (2026-11-18):** multi-mass heights require unique role/ordinal host; ties/unscoped → `E_DESIGN_HEIGHT_HOST_SCOPE`; removed largest-secondary area fallback |
| `UserConstraintContract.inferHeightRoleHint` | hall-before-pavilion in same clause | **Fixed (2026-11-18):** shared nearest-role rule with omit (`主殿旁边的亭子高度6格` → pavilion) |
| `UserConstraintContract.DesignOmitBinding` / `inferOmitScope` | single roleHint only | **Extended (2026-11-18):** `all_main_masses` + `multi_named`(+namedKeys); Java seals verified target **sets** |
| `building_contract.extract_requirements` ordinal fragments | fragment-relative `match_span` | **Fixed (2026-11-18):** `_shift_match_span` restores absolute player-text coordinates |
| `BuildingHostResolver.isHostMass` vs design seals | STRUCTURE/GATE_STRUCTURE counted as omit/height hosts | **Fixed (2026-11-18):** `isDesignConstraintHost` aligns with MassComponentKinds + GATE; STRUCTURE stairs excluded from all_main_masses seals |
| `BuildingHostResolver.isDesignConstraintHost` plate / bare STRUCTURE | `MASS_SECONDARY` plates and bare columns counted as building hosts | **Fixed (2026-11-18):** exclude `extrude_mode=plate` and `host_id` attachments; STRUCTURE requires verifiable `typology:` / typology_id (HARD_CONSTRAINT host inventory, not geometry reclass) |
| `llm_plan_normalization._mass_like_components` | all MASS* including plates | **Fixed (2026-11-18):** `_is_design_constraint_host` parity with Java (plate/host_id/bare STRUCTURE excluded) |
| `building_contract.apply_building_contract` masses | `all_main_masses` used only `MASS_MAIN` → Py/Java omit mismatch on TOWER/GATE/HALL/typology STRUCTURE | **Fixed (2026-11-19):** shared `is_design_constraint_host` / `design_constraint_hosts`; contract targets use design-host inventory (HARD_CONSTRAINT identity, not geometry reclass) |
| `building_contract` `_DESIGN_HOST_PROPERTIES` / empty targets | expanding all_main_masses to every design host broke dim/material MASS_MAIN semantics; typology STRUCTURE positive roofs double-bound | **Fixed (2026-11-19):** design-host pool only for `roof_type`/`roof_block`/`window_style`/`entrance_type`; dims/materials stay MASS_MAIN; only `tailiang_timber_hall` defers positive roof to chinese_roof_contract; other typologies stay in targets (gap if unsupported); empty design-host pool → `E_DESIGN_HOST_MISSING` |
| `building_contract` `requirement_masses` + scope_bindings | TOWER/GATE/HALL/typology STRUCTURE added for named design scopes; scope+identity double-appended same host | **Fixed (2026-11-19):** append only TOWER/GATE/HALL/typology STRUCTURE (not every MASS_SECONDARY); dedupe binding membership by object identity; goldens updated for deduped named_* + typology `building_id` + tower `requirement_scope` surfacing |
| `building_contract` named design bindings | independent MASS_SECONDARY (e.g. garden_pavilion) in design_hosts but absent from named_* bindings → Py/Java omit target mismatch | **Fixed (2026-11-19):** `binding_hosts = requirement_masses ∪ design_hosts`; design-named scopes sourced from design hosts (HARD_CONSTRAINT), dims stay historical mass pool |
| `building_contract` / Java named identity | Python matched `component_id` only; missing `pagoda`; no `building_role` | **Fixed (2026-11-19):** `NAMED_BUILDINGS.tower` += `pagoda`; `_host_identity_blob` = `component_id`+`building_role`/`buildingRole`/`part`/`label` (HARD_CONSTRAINT bind corpus). Excludes bare `role`/`slot_id`/`building_id`/`features` — those caused false `named_*` joins; Java still scans wider but fails closed on multi-hit |
| `_all_mass_design_targets` positive roof | excluded all typology STRUCTURE → dense_eaves_pagoda silently skipped | **Fixed (2026-11-19):** defer only `tailiang_timber_hall` (chinese_roof_contract); other typologies stay in targets; `E_TYPOLOGY_ROOF_UNSUPPORTED` when unsupported |
| `BuildingLandingPlanner.sites` | only MASS_MAIN/MAIN_MASS (+few typologies); independent TOWER omitted → rigid Y risk | **Fixed (2026-11-19):** `isIndependentLandingHost` includes independent MASS*/TOWER/GATE/HALL/typology STRUCTURE; plates/`host_id` excluded |
| `DenseEavesPagodaBuilder` / contract | `roof_type=none`/`xieshan` stamped on params while builder always emits eaves+finial | **Fixed (2026-11-19):** `TypologyRoofCapability` + Java `TypologyRoofCapabilityValidator` + Python `typology_supports_roof_type` — capability_gap; no silent eave deletion |
| `TailiangTimberHallBuilder` roof omit | always emitted ChineseHallRoofGenerator surfaces | **Fixed (2026-11-19):** `roof_type`/`roofType=none` skips roof tiles/inner roof; timber frame retained (GEOMETRY omit, capability-supported) |
| `BuildingLandingPlanner.sites` special typology | courtyard/tailiang/gothic bypassed `host_id`/plate gate | **Fixed (2026-11-19):** plate/`host_id` exclusion before all special envelope branches |
| `MASS_ANCILLARY` design host | type alone → design host; named bindings skipped → dual standard | **Fixed (2026-11-19):** Py/Java require building-identity evidence (`building_role`/named scope/aliases) |
| stacked `pagoda_tier_*` named_tower | multi-hit treated as ambiguous multi-building | **Fixed (2026-11-19):** stacked family key collapses storeys to one building entity for named bind/omit |
| `_unique_named_hosts` / `namedKeyHostIds` plural wing | fail-closed on any multi-hit broke `named_wing` west+east compounds | **Fixed (2026-11-19):** singular aliases still fail closed; `wing` + stacked storeys remain coherent multi-targets |
| stacked height stamp | `resolveHeightBindings` copied whole-building height onto every `pagoda_tier_*` | **Fixed (2026-11-19):** envelope binding + proportional Y/height rescale; `E_STACKED_HEIGHT_UNSUPPORTED` when impossible; stacked storeys count as one building for unscoped height |
| stacked height equal-envelope | `envelope==target` wrote whole height onto base storey dims | **Fixed (2026-11-19):** equal envelope is a no-op on all storey H/Y; feasibility uses floor_count×minBand before scale; sealed height blocks `FloorLayoutOverflowRepair` re-grow |
| height wing side null clause | `resolveHeightBindings` passed `null` clause → all wings got west height | **Fixed (2026-11-19):** `HeightBinding.sourceText`/`namedKeys`; height shares wing side resolution with omit |
| wing side first-only / omit comma | `_wing_side_from_text` / `wingSideHint` kept first side; omit clause missed `，` | **Fixed (2026-11-19):** `wingSidesFromText` / `_wing_sides_from_text`; comma/顿号 clause bounds; joint 西+东都 binds both |
| roof alias equal plan rank | `promote` let LLM `roof_type` overwrite USER_EXPLICIT `roofType` | **Fixed (2026-11-19):** `sourceAuthorityRank`; dual USER_EXPLICIT conflict keeps canonical + marks alias conflict |
| `_named_scope_mass_hits` omitted `named_key` | plural wing collapsed to None → `E_OMIT_SCOPE_AMBIGUOUS` after contract bound both wings | **Fixed (2026-11-19):** pass `named_key=wing`; clause-window side filter for 西/东厢房 |
| `double_eave_xieshan` capability vs generator | capability allowed token; `ChineseHallRoofGenerator.normalize` rejected it | **Fixed (2026-11-19):** `TypologyParamResolver.syncRoofAliases` decomposes to `xie_shan`+`doubleEave`; plan `roof_type` beats typology soft `roofType` |
| `llm_plan_normalization` omit/roof stamp path | matched typology STRUCTURE but `_is_mass_like` skipped writes | **Fixed (2026-11-19):** write roof/window/entrance onto `_is_design_constraint_host` targets only (not all STRUCTURE) |
| `RequestIntentInjector` empty hosts / id collision | empty host set silently skipped; colliding accessory id inherited seals | **Fixed (2026-11-19):** `E_DESIGN_HOST_MISSING` gap; seal only when `isDesignConstraintHost` |
| `building_contract._roof_clause_window` | no newline bounds → cross-line multi_named | **Fixed (2026-11-18):** `\n`/`\r` are clause boundaries; joint `都不要` stays line-local |
| `UserConstraintContract.inferOmitScope` / `inferHeightRoleHint` multi namedKey | fell back to hall/pavilion/tower-only nearestRoleHint | **Fixed (2026-11-18):** nearest full namedKey (office/boiler/workshop); equidistant distinct keys → unresolved gap |
| `BuildingHostResolver.primaryMassId` missing ID | returned unmatched `primary_building_id` phantom | **Fixed (2026-11-18):** fall through to role/volume among real hosts; design seals rank primary only among `isDesignConstraintHost`; `E_PRIMARY_HOST_MISSING` diagnostic; targets must be in host set |
| `resolveHeightBindings` / `resolveDesignOmitBindings` sole-host shortcut | named pavilion cue applied to lone hall | **Fixed (2026-11-18):** unscoped-only sole-host bind; named/ordinal/multi always verify identity (missing pavilion → gap) |
| `_normalize_request_text` | deleted newlines between CJK → span/clause drift vs Java | **Fixed (2026-11-18):** preserve newlines; remap match_span onto original text via span map |
| `UserConstraintContract.namedKeysInWindow` / `uniqueNamedKeyHostId` | only hall/pavilion/tower; office→hall | **Fixed (2026-11-18):** shared NAMED_BUILDINGS identity table; named-key unique bind without hall fallback |
| `UserConstraintContract.inferDesignOmitRoleHint` / `omitCueAuthorized` | reused hall-first height hint; bare 无窗 | **Fixed (2026-11-18):** nearest clause role for omit (主殿旁的亭子→pavilion); reject 不是无窗 / 并非无屋顶 double negation |
| `UserConstraintContract.DesignOmitBinding` | domain+role only | **Extended (2026-11-18):** `sourceText` + `matchStart`/`matchEnd` for per-declaration Py↔Java identity |
| `building_contract._extract_global_requirements` specialty roofs | collapsed to `hits[0]` | **Fixed (2026-11-18):** one requirement per declaration span; `multi_named` + `EXPLICIT_MULTI` for 都/均 joint subjects |
| `_targets_for_req` / `apply_building_contract` multi_named | first hit / partial named_keys | **Fixed (2026-11-18):** every listed subject must uniquely bind; incomplete → empty targets / `unresolved_binding` + `E_REQUIREMENT_SCOPE`; no soft single-host fallback for `EXPLICIT_MULTI` |
| `oct20_access_2` exhibition_hall envelope | height 8 under coplanar glass pyramid | **Restored (2026-11-18):** immutable raw capture stays height 8 + original sha; raised envelope lives in `oct20_access_2_repaired(.normalized).json`; historical compile expects `E_FLAT_ROOF_COVERAGE` |
| `UserConstraintContract.parseDesignOmitBindings` | — | **Added (2026-11-18):** HARD_CONSTRAINT clause/ordinal omit parsing; Java re-verifies before `stampVerified` |
| `_infer_unique_named_roof_host` / `_roof_req_host_cue` | whole `user_text` as cue | **Fixed (2026-11-18):** clause-local source_text evidence only; dual hall+pavilion cues in one clause → no bind; `named_*` via NAMED_BUILDINGS (主殿/亭子) |
| `_roof_clause_subject` / roof conflict collapse | sentence-wide subject; unresolved multi-value wipe | **Fixed (2026-11-18):** comma-bounded nearest named host; do not null distinct unresolved roofs |
| `NAMED_BUILDINGS` | missing 主殿/pavilion | **Extended (2026-11-18):** `main_hall`+=主殿/正殿/大殿; add `pavilion` aliases for named scope resolution |
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
