# Design decision inventory

Hard-coded or inferred **architectural** choices (not pure geometry). Used as Phase 4–5 backlog. Line numbers are approximate — re-verify on edit.

Flat-roof repair preflight reuses target and provenance validation before requesting a proposal (HARD_CONSTRAINT). Missing or ambiguous IDs and fully locked targets preserve the original diagnostic without a model call.

Java decodes flat-roof repair proposals without scalar coercion and rejects duplicate keys or trailing JSON (HARD_CONSTRAINT). Valid proposals retain the model's exact integer rather than letting transport change its value.

Flat-roof repair proposals are model-owned. The backend accepts one horizontal integer edit for one diagnosed flat roof. The Java off-thread preview path independently checks the owner, part, user provenance and edit bounds (HARD_CONSTRAINT), then compiles a candidate once with repair disabled. Width and depth proposals are capped at 256 blocks and position deltas at 256 blocks for this retry. No design defaults are added.

Flat roof coverage diagnostics include the sources assigned to the failing host part (GEOMETRY). This adds evidence for repair without changing plan fields or adding defaults.

Legacy MODULE-to-typology conversion merges registry and reference defaults before authored parameters, matching Java's precedence. Previously reference defaults could replace authored geometry, such as an explicit bridge span of 120 with 180. This change narrows an existing explicit-template compatibility rule without adding defaults or authorizing a new route.

The FBD prompt gate treats an existing user opt-out as authoritative even when the environment enables FBD capability guidance. This is a request constraint on prompt content and introduces no new keyword or geometry default.

Flat roof host coverage remains a Java geometry constraint. `E_FLAT_ROOF_HOST_COVERAGE` now provides the host and part IDs, missing cell count, and first missing position in plan coordinates. The diagnostic does not resize the roof or change the plan.

Roof omission uses the same first nonblank parameter and alias resolution as the roof generator (FORMAT_REPAIR). A blank canonical field can defer to an authored alias, while a nonblank canonical value retains precedence. The omission check adds no default or preset.

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
| wing→pavilion role collapse | `roleHintForNamedKey(wing)=pavilion` bound 西厢房 onto 凉亭 when both present | **Fixed (2026-11-19):** wing stays wing; `resolveNamedOrRoleHostIds` prefers namedKeys; pavilion role no longer scores wing hosts |
| stacked shrink last-remainder reject | proportional round then last=target−sum rejected feasible 20+4+4→12 | **Fixed (2026-11-19):** min bands first + largest-remainder weight distribute; equal envelope seals + authored floor conflict gap; `E_ROOF_ALIAS_CONFLICT` + `StackedEnvelopeIntegrityValidator` |
| global height_blocks integrity | `StackedEnvelopeIntegrityValidator` used one plan height for every sealed shaft | **Fixed (2026-11-19):** per-family `_fc_shaft_envelope_target` / plan_meta `stacked_envelope_targets`; shaft vs building envelope modes |
| namedKeys miss → role fallback | empty west-wing bind fell back to sole east wing | **Fixed (2026-11-19):** side-qualified named miss (西厢房) fail-closed; class aliases (主殿→hall) may role-score when plan ids lack the alias string; unscoped requests still role-match |
| stacked restack deletes gaps | `yCursor += h` erased oct18 inter-storey eaves voids; crown absorbed remainder | **Fixed (2026-11-19):** scale solids+gaps together; hard `floor_height` conflicts gap instead of silent clamp |
| building mode dual equality | integrity required shaftEnv==target AND buildingEnv==target | **Fixed (2026-11-19):** mutually exclusive modes; building reserves crown rise when scaling; meta mass_ids cross-check |
| satellite bottom-offset float | windows kept old bottom offset after host shrink | **Fixed (2026-11-19):** proportional+clamp into host; unfit satellites → capability gap |
| stacked height last-write-wins | duplicate pagoda height cues silently overwrote | **Fixed (2026-11-19):** `registerStackedEnvelope` dedupes equals / `E_HEIGHT_HOST_CONFLICT` on mismatch |
| near-top window top-stick | `abs(offsetFromTop)<=1` treated openings as crowns | **Fixed (2026-11-19):** openings/stairs never top-stick |
| whole-request building mode | `wantsBuildingEnvelope(fullText)` flipped every tower | **Fixed (2026-11-19):** per-binding `HeightBinding.envelopeMode` from local clause |
| forged envelope meta | `trusted=true` / unsigned plan_meta accepted | **Fixed (2026-11-19):** `stacked_envelope_targets_seal` + sealed envelope param keys |
| envelope seal stripped in final pass | `stripUntrustedMarkers` + height-only restamp dropped envelope verified keys | **Fixed (2026-11-19):** capture priorVerified always; `restampPreserved` after height/omit |
| speculative inferred roof rise | budgeted roof even when `allowCompilerSatellites=false`; mid-tier roof zeroed top budget; rise≠net after −1 overlap | **Fixed (2026-11-19):** `estimatedInferredRoofNetRise` via `makeRoofComponent`; top-host only; no satellites → 0 |
| DECOR_DETAIL crown split | injector treated all DECOR as crown; integrity ignored unnamed DECOR | **Fixed (2026-11-19):** shared `classifyCrownAttachment` — facade decor NONE; banner/flagpole TOP_PROTRUSION |
| same-height shaft vs building | auto-upgraded shaft→building | **Fixed (2026-11-19):** both-explicit mode conflict → `E_HEIGHT_HOST_CONFLICT`; default may yield to explicit |
| full-compile building test early-return | empty patches + diagnostic counted as pass | **Fixed (2026-11-19):** legal building-mode must non-empty BlockPatch; exact footprint height; separate infeasible gap test |
| meta seal break rescued by component height | diagnose skipped when component targets non-empty | **Fixed (2026-11-19):** damaged meta seal always gaps (mass_ids only in meta); empty comps + trusted meta gaps |
| meta vs component mode drift | mergePreferMeta silently preferred meta mode | **Fixed (2026-11-19):** sealed shaft/building mismatch → `E_HEIGHT_HOST_CONFLICT` |
| matched shaft skips enclosed roof seat | envelope==shaftTarget sealed without relocate → flush roof left buildingEnv=shaft+H | **Fixed (2026-11-19):** matched path still registers H/Y so rewrite seats enclosed roofs at hostTop-1; budget uses net rise H−1 |
| building-mode shaft expect pre-align H | tests asserted shaft=target−roofH (47) | **Fixed (2026-11-19):** expect shaft=target−(H−1)=48 for enclosed H=3; buildingEnv exact 50 |
| mid-tier eave at shaftTop+net | `measureCrownRiseAboveShaft` added any family roof net onto shaftTop | **Fixed (2026-11-20):** seat each ROOF on its host storey; `max(measured,inferred)` so mid eaves cannot skip top roof budget |
| orphan ROOF.open vs host MASS | `relocateSatelliteY` used satellite open; aligner used mass | **Fixed (2026-11-20):** `roofSeatYOnHost(hostParams)` shared with budget/relocate |
| empty comps + damaged meta seal | early empty return skipped seal diagnose | **Fixed (2026-11-20):** `diagnoseDamagedMetaSeal` before empty short-circuit |
| building-mode authored vs pending roof | integrity required buildingEnv==target before satellite inference | **Fixed (2026-11-20):** authored may accept shaft+estimatedInferredRise; prepared pass still exact |
| inferred xieshan voxel vs Dimensions | `normalizeStairRoofRise` bumped gen height above ROOF `Dimensions` (+2 solids) | **Fixed (2026-11-20):** `makeRoofComponent` mirrors the same bump into Dimensions (core footprint, before overhang); sealed budget ↔ BlockPatch; `StackedTowerFullCompileEnvelopeTest` asserts solid==50 |
| `SiheyuanCourtyardMisrouteRepair` whole-plan STRUCTURE | keyword misroute → `List.of(courtyard_compound)` DESIGN_OVERRIDE | **Fixed (2026-11-20):** open compositions → `E_SIHEYUAN_COMPOSITION_UNSUPPORTED` capability_gap; keep AI masses; **only STRUCTURE executor** skips (request-text typology:/preset: alone does not) |
| `LandmarkMisrouteRepair` fixed eiffel dims | ASSEMBLY/FBD → STRUCTURE 60×27 DESIGN_OVERRIDE | **Fixed (2026-11-20):** `E_LANDMARK_ROUTE_UNSUPPORTED` gap; preserve authored dims; **STRUCTURE+module_id** = routed; feature tags / AI params ≠ executor; misrouted ASSEMBLY still gaps even with module: text |
| `FloorLayoutOverflowRepair` floor_count clamp | sealed height silently reduced storey count | **Fixed (2026-11-20):** never write reduced `floor_count`; soft `floor_height` may clamp; else `E_FLOOR_LAYOUT_CONFLICT` |
| `TempleSideHallMisrouteRepair` crown/window invent | dropped all CROWN; invented wrap windows ignoring omit | **Fixed (2026-11-20):** drop classical cupola/dome only; keep non-classical AI crowns; honor facade/entrance/roof OMITTED + ExplicitDesignPolicy |
| `TempleSideHallMisrouteRepair` cross-host rewrite | ENTRANCE/FACADE rebinding + omit dropped peidian windows; all MASS_MAIN → same id | **Fixed (2026-11-20):** host-scoped sanitize/omit/invent; enrich only selected primary; other MASS_MAIN identity preserved |
| `TempleSideHallMisrouteRepair` roof_type overwrite | enrich forced hip/xieshan over `roof_type=none` | **Fixed (2026-11-20):** `DesignProvenance.putIfAllowed` + roofOmitted skip |
| `TempleSideHallMisrouteRepair` crown drop provenance | ad-hoc design_sources scan | **Fixed (2026-11-20):** `DesignProvenance.canMutateParam` (plan + component locks) |
| `ExplicitDesignConflictRepair` drop any CROWN vs ROOF | silent delete of 宝顶 with pitched roof | **Fixed (2026-11-20):** keep Chinese finial/宝顶; drop classical/ambiguous only; locked classical → `E_CROWN_ROOF_CONFLICT` |
| `TempleSideHallMisrouteRepair` unbound host=main | `host==null` treated as main | **Fixed (2026-11-20):** spatial `resolveUnboundHost` MATCH only |
| `TempleSideHallMisrouteRepair` clearClassicalHints | deleted locked crown_* hints | **Fixed (2026-11-20):** `DesignProvenance.canMutateParam` gate |
| `TempleSideHallMisrouteRepair` sanitize wrap/door | forced wrap + door clamp without provenance | **Fixed (2026-11-20):** putIfAllowed / missing-only; keep non-classical rhythm |
| `TempleSideHallMisrouteRepair` isClassicalDecor cornice | bare cornice dropped CJK eaves | **Fixed (2026-11-20):** eave/bracket exclude; cornice needs western cues |
| Live2252 `230659` E_PATCH_CONFLICT | temple host-scope now compiles 道观 | **Updated (2026-11-20):** accept success + `daoGuan230659CompilesWithFourHallsAndOpenings` integrity |
| `estimateInferredRoofRise` top crown zero | any `countsTowardBuildingEnvelope` on top suppressed inferred roof | **Fixed (2026-11-20):** only top-hosted ROOF suppresses; TOP_PROTRUSION/finial/banner do not |
| `GothicBellTowerFidelityRepair.repairEntrance` | snapped every ENTRANCE onto primary shaft | **Fixed (2026-11-20):** host-scoped; foreign `host_id` preserved |
| `StiltShelterMisrouteRepair` whole-plan rewrite | keyword+suspension_bridge → invented stilts template DESIGN_OVERRIDE | **Fixed (2026-11-20):** `E_STILT_SHELTER_ROUTE_UNSUPPORTED` capability_gap; STRUCTURE+typology only; feature tags ≠ executor; residual bridge beside stilts still gaps |
| `StadiumMisrouteRepair` mansard inject / all-ROOF rewrite | residential demotion may overwrite every ROOF + invent mansard | **Fixed (2026-11-20):** host-scoped mansard on demoted hosts only; foreign annex roofs preserved; STRUCTURE demote only when stadium-hit or primary+style-poisoned |
| `StadiumMisrouteRepair` material_hints → demotedHosts | plain material_hints stripped → annex entered demotedHosts → gable→mansard | **Fixed (2026-11-20):** only stadium-tainted hints strip; `stadiumRouteDemoted` separate from format cleanup |
| `StadiumMisrouteRepair` looksPrimaryMass all STRUCTURE | style-poison demoted every STRUCTURE | **Fixed (2026-11-20):** stable `demotePrimaryId` (stadium host or first primary); foreign bell STRUCTURE kept |
| `StadiumMisrouteRepair` mansard provenance / unbound roof | USER_EXPLICIT roof_type overwritten; unbound assumed primary | **Fixed (2026-11-20):** `putIfAllowed`; unbound via spatial `resolveUnboundHost` MATCH only |
| `StadiumMisrouteRepair` void_ratio as stadium evidence | Python mass default void_ratio/tier_count/elliptical demoted annexes | **Fixed (2026-11-20):** exclusive bowl/mesh keys only as evidence; compat keys cleaned only after true hit + provenance |
| `StadiumMisrouteRepair` mansard specialty on locked hip | roof_type kept but roof_specialty/dormers still injected | **Fixed (2026-11-20):** locked non-mansard skips entire roof bundle |
| `StadiumMisrouteRepair` meshStructure/tierStep exclusive | 土楼 MODULE / 天坛 radial_terrace_hall demoted as stadium | **Fixed (2026-11-20):** only bowl/field keys exclusive; mesh/tierStep are compat-after-hit |
| `StadiumMisrouteRepair` style-only demotePrimary | first STRUCTURE by list order demoted | **Fixed (2026-11-20):** demote only stadium-marker hosts; style rewrite alone |
| `StadiumMisrouteRepair` stadium cue early-return | 「不要体育场」/混合体育场跳过整修 | **Fixed (2026-11-20):** affirmative cue via `isNegatedAt`; mixed preserves non-residential stadium executors |
| `StadiumMisrouteRepair` baroque⇒mansard | style alone invented mansard+dormers | **Fixed (2026-11-20):** explicit mansard/dormer cues only; flat-roof ask blocks |
| `BaroqueMansionStyleRepair` undoes stadium roof lock | looksBaroqueMansion forced every ROOF→mansard; dormers on all roofs | **Fixed (2026-11-20):** provenance putIfAllowed; mansard only if planned/asked; dormers mansard-host only + negation |
| `BaroqueMansionStyleRepair` global chimney cap | keptChimneys>1 dropped symmetric/annex chimneys | **Fixed (2026-11-20):** keep authored chimneys |
| `BaroqueMansionStyleRepair` storey lift | floor_height/height raised without provenance | **Fixed (2026-11-20):** putIfAllowed / canMutateParam gates |
| `StadiumMisrouteRepair` rewriteProportionHints | baroque alone injected mansard specialty | **Fixed (2026-11-20):** sync wantsMansardRoof/dormer affirmative cues |
| `StadiumMisrouteRepair` looksResidentialHost names | mixed arena preserved only via mansion id tags | **Fixed (2026-11-20):** multi stadium-host; preserve only arena role/slot/bowl-scale (`looksDedicatedArenaHost`); MASS_MAIN always demotes |
| `BaroqueMansionStyleRepair` planHasMansard broadcast | any mansard forced every unlocked roof | **Fixed (2026-11-20):** `hostHasMansard` / primary corpus ask only |
| `BaroqueMansionStyleRepair` dormer broadcast | any roof_dormers=true → all mansards | **Fixed (2026-11-20):** `hostWantsDormers` per host_id |
| `StadiumMisrouteRepair` stadiumHosts&lt;2 demote | sole real arena beside MASS_MAIN demoted | **Fixed (2026-11-20):** preserve when `hasSeparateNonStadiumHost`; MODULE counted |
| `BaroqueMansionStyleRepair` skinny balcony drop | BALCONY/TERRACE deleted by aspect ratio | **Fixed (2026-11-20):** keep authored outdoor volumes; only attic DECOR spikes |
| `BaroqueMansionStyleRepair` gate/gothic abort | companion gate/gothic aborted whole repair | **Fixed (2026-11-20):** host-scoped foreign skip; baroque primary still repaired |
| `StadiumMisrouteRepair` strips arena satellites | preserved STRUCTURE kept but ribbon/arch_ribs stripped | **Fixed (2026-11-20):** `preservedStadiumHosts` + `belongsToPreservedStadiumHost` |
| `BaroqueMansionStyleRepair` plate roof_type | floor_plate inherited mansard via host_id | **Fixed (2026-11-20):** plate skip; roof params only on non-plate masses / ROOF |
| `proportionHints` mansard_dormer broadcast | RoofGrammarResolver copied dormers to all mansards | **Fixed (2026-11-20):** no global dormer auth; specialty hint silhouette-only |
| `BaroqueMansionStyleRepair` primaryHostId order | first MASS_MAIN got corpus mansard | **Fixed (2026-11-20):** unique `baroqueTargetHostId` markers; ambiguous → no force |
| `BaroqueMansionStyleRepair` paving/entrance global | plaza/entrance rewritten for every host | **Fixed (2026-11-20):** baroque-target-owned only |
| `hostMassFootprint` primary fallback | decor host→roof fell back to first mass | **Fixed (2026-11-20):** decor→roof→mass chain; null if unresolved |
| `BaroqueMansionStyleRepair` enrich non-target | stadium/wing got plinth/storey enrich | **Fixed (2026-11-20):** enrich/decor drop only `belongsToBaroqueTarget` |
| `RoofGrammarResolver` global mansard type | blank annex roof forced mansard from hints | **Fixed (2026-11-20):** plan hints refine local mansard only; typology non-binding |
| `StadiumMisrouteRepair` preserve via any MASS_MAIN | misrouted STRUCTURE kept when clean MASS_MAIN exists | **Fixed (2026-11-20):** sole stadium+residential OR dedicated arena when multi |
| `belongsToPreservedStadiumHost` 2-hop only | deep satellites stripped | **Fixed (2026-11-20):** recursive host_id walk with cycle guard |
| `massFootprintById` host_id plate match | floor plate dims used as host footprint | **Fixed (2026-11-20):** exact component_id; skip plates |
| `Baroque` overwrites stadium typology hint | proportionHints typology→baroque_mansion | **Fixed (2026-11-20):** skip when stadium executor remains |
| `BaroqueMansionStyleRepair` enrich non-target | stadium/wing got plinth/storey enrich | **Fixed (2026-11-20):** enrich/decor drop only `belongsToBaroqueTarget` |
| `RoofGrammarResolver` global mansard type | blank annex roof forced mansard from hints | **Fixed (2026-11-20):** plan hints refine local mansard only; typology non-binding |
| `StadiumMisrouteRepair` preserve via any MASS_MAIN | misrouted STRUCTURE kept when clean MASS_MAIN exists | **Fixed (2026-11-20):** sole stadium+residential OR dedicated arena when multi |
| `belongsToPreservedStadiumHost` 2-hop only | deep satellites stripped | **Fixed (2026-11-20):** recursive host_id walk with cycle guard |
| `massFootprintById` host_id plate match | floor plate dims used as host footprint | **Fixed (2026-11-20):** exact component_id; skip plates |
| `Baroque` overwrites stadium typology hint | proportionHints typology→baroque_mansion | **Fixed (2026-11-20):** skip when stadium executor remains |
| `StadiumMisrouteRepair` global style rewrite | preserved arena still got residential styleProfile | **Fixed (2026-11-20):** stamp host `style_profile`; emit prefers local |
| `baroqueTargetHostId` via mansard | French mansard annex stole baroque target | **Fixed (2026-11-20):** style markers only; mansard ≠ baroque identity |
| `shouldPreserveStadium` sole+MASS_MAIN | missing arena kept misrouted STRUCTURE | **Fixed (2026-11-20):** dedicated arena identity only; affirmative ask without arena → `E_STADIUM_EXECUTOR_MISSING` |
| `isMisplacedWallOrRoofDecor` pilaster | skinny facade pilaster dropped | **Fixed (2026-11-20):** column/pilaster no longer attic-delete cues |
| `applyTypologyExclusiveFilter` / Python `strip_typology_exclusive_compositionals` plan-wide | stadium STRUCTURE deleted neighbouring mansion MASS/ROOF/FACADE | **Fixed (2026-11-20):** multi-building by `building_id` or spatially separated footprints; `component_id` alone is NOT independent |
| exclusive footprint used center-only AABB | `min_corner` + slot.anchor ignored → false overlap / false sole shell | **Fixed (2026-11-20):** `ComponentFootprintUtil.planSpaceFootprintXz` + shared `GenerationCompileContext.hasIndependentBuildingBesideTypology`; Python mirrors; missing geom → null not 1×1 |
| `looksDedicatedArenaHost` via separate MASS | west_wing made misrouted main look like dedicated arena | **Fixed (2026-11-20):** clearer arena competitor only; residential MASS alone does not preserve |
| `blocksArchetypeFallbackFor` direct host only | nested satellite under wing→arena missed exclusive block | **Fixed (2026-11-20):** walk `host_id` parent map |
| exclusive filter global `multiBuilding` keep | distant hall rescued co-located `pagoda_shell` | **Fixed (2026-11-20):** `ComponentOwnership` per-component; overlap→EXCLUSIVE; AMBIGUOUS strips |
| distinct slots + missing geom → independent | unknown location treated as separated | **Fixed (2026-11-20):** missing geom never proves separation |
| bare sole `stadium_bowl` STRUCTURE preserved | anonymous misroute counted as arena executor | **Fixed (2026-11-20):** companion MASS or clear cues only; else demote + `E_STADIUM_EXECUTOR_MISSING` |
| Python `_authored_slot_id` top-level only | `params.slot_id` missed slot.anchor | **Fixed (2026-11-20):** read params alias; Java `authoredSlotId` parity |
| ownership overlap before `building_id` | cantilever view tower deleted despite distinct bid | **Fixed (2026-11-20):** identity before XZ; overlap only for same-building redundant |
| AMBIGUOUS silent strip | incomplete annex vanished in mixed compounds | **Fixed (2026-11-20):** keep + `E_COMPONENT_OWNERSHIP_AMBIGUOUS` |
| `exclusiveRoots.size()>1` ⇒ multi | same `building_id` dual STRUCTURE flipped soleTypology | **Fixed (2026-11-20):** `exclusiveRootsSpanDistinctBuildings` |
| ownership footprint via MASS min-3 | Java/Python disagree on width-1 adjacency | **Fixed (2026-11-20):** authored dims in `planSpaceFootprintXz` |
| `!multiBuilding` strip-all shortcut | sole pagoda+incomplete annex silent delete | **Fixed (2026-11-20):** always per-component ownership; AMBIGUOUS gap in sole too |
| `normalizeBody` before exclusive filter | prepareComponents widened width-1 before ownership | **Fixed (2026-11-20):** filter then normalizeBody |
| unresolved `host_id` ⇒ independent | `missing_host` bypassed overlap strip | **Fixed (2026-11-20):** host must resolve in known component ids |
| `host_id` exists ⇒ INDEPENDENT | orphan `shell_roof` kept after exclusive shell strip | **Fixed (2026-11-20):** host-tree inheritance; satellites follow host effective ownership |
| spatial sep vs partial exclusive geom | distant mass proven independent while another exclusive root lacks dims | **Fixed (2026-11-20):** all exclusive roots must be comparable |
| `prepareComponents` only lifts `params.slot_id` | `params.slotId` ownership≠emit origin | **Fixed (2026-11-20):** lift `slot_id`/`slotId` onto `Component.slotId` before emit |
| host inherit overrides distinct `building_id` | `platform_B` hosted on exclusive shell silently stripped | **Fixed (2026-11-20):** `OWNERSHIP_CONFLICT` + `E_COMPONENT_OWNERSHIP_CONFLICT` keep |
| unhosted-only STRUCTURE/PATH keep | hosted nested typology STRUCTURE / PAVING orphans | **Fixed (2026-11-20):** hosted peripherals run host-tree ownership |
| `componentById` last-writer | duplicate satellite ids order-dependent ownership | **Fixed (2026-11-20):** unique index + `E_DUPLICATE_COMPONENT_ID` |
| blank `Component.slotId` skip lift | `slotId=""` ownership uses params, emit uses default | **Fixed (2026-11-20):** treat blank as missing; lift params slot |
| reconcile only when child bid outside exclusive set | `temple_A` roof hosted on `mansion_B` inherited independent | **Fixed (2026-11-20):** compare child vs resolved host building_id both ways |
| `HostedPlateDesignValidator` mass-only dup ids | residential duplicate roofs escaped | **Fixed (2026-11-20):** all non-plate `component_id`s unique |
| `EffectiveBuildingStyleResolver` last-writer byId | chimney style order-dependent on shared roof id | **Fixed (2026-11-20):** unique index; ambiguous host → no inherit |
| `HostIdRemapRepair.pick` shortest/Map-order | `courtyard` → east vs west courtyard | **Fixed (2026-11-20):** unique candidate only; else `E_HOST_ID_AMBIGUOUS` |
| `resolveHostBuildingId` 1–2 hops only | deep shell/trim chain missed exclusive root bid | **Fixed (2026-11-20):** full host-chain walk with cycle guard |
| `makeCrownComponent`/`alignCrownToMass` copied host `component_id` | CROWN leaked `body` → `E_DUPLICATE_COMPONENT_ID` | **Fixed (2026-11-20):** crown id = host`#crown` |
| unique fuzzy remap ignored `building_id` | house_A lantern → house_B east_courtyard | **Fixed (2026-11-20):** building_id must match or be absent |
| host-ref index included plates | plate display ids flagged as dup under typology | **Fixed (2026-11-20):** plates excluded from host-ref index |
| host-ref index excluded all plates | pavilion roof on `pavilion_deck` lost host → exclusive strip | **Fixed (2026-11-21):** unreferenced plates stay out; uniquely referenced plates enter host-ref index; ambiguous referenced plates → duplicate |
| `makeCrownComponent`/`alignCrownToMass` `putIfAbsent(host_id)` | annexed tower crown kept upstream `host_id=main` | **Fixed (2026-11-21):** force `host_id` = carrying mass; strip inherited host fields |
| auto crown id always `mass#crown` | two id-less CROWNs on same host → `E_DUPLICATE_COMPONENT_ID` | **Fixed (2026-11-21):** `mass#crown`, then `mass#crown#2`… |
| exact cross-building `host_id` only checked under typology exclusive | house_A lantern → house_B skipped without typology | **Fixed (2026-11-21):** global `E_HOST_BUILDING_IDENTITY_CONFLICT` in DesignValidatorSuite |
| global bid≠hostBid on all components | stupa/pagoda tiers, stilts, courtyards, corridors rejected | **Fixed (2026-11-21):** attachment-satellite only; skip `compiler_inferred_host_id`; nested unit bids allowed |
| `realignSatellitesToMass` host stamp on any aligned≠null | COURTYARD/site channel got slot MASS `host_id` → false conflict | **Fixed (2026-11-21):** satellite-candidate gate; inject host only for geometry satellites |
| multi-CROWN align used `template.relativePosition()` | distinct authored crowns collapsed to mass center | **Fixed (2026-11-21):** preserve LLM XY when >1 crown on same host |
| unhosted TERRACE/PAVING/FOUNDATION/BASE still slot-bound | `mill_channel` got `host_id=mill_mass` | **Fixed (2026-11-21):** authored-host-only for deck types |
| `countCrownsForMass` required existing host_id | two host-less slot crowns still centered | **Fixed (2026-11-21):** count same-slot unresolved crowns with mass |
| aligner read only `params.host_id` | camelCase `hostId` ignored → dual host fields | **Fixed (2026-11-21):** `HostAliasNormalizer` before remap; conflict → `E_HOST_ID_ALIAS_CONFLICT` |
| `isAuthoredHostOnlyDeck` included FOUNDATION/BASE | unhosted FOUNDATION skipped → foundation realign CI fail | **Fixed (2026-11-21):** authored-host-only = TERRACE/PAVING; FOUNDATION/BASE still geometry-align |
| `HostAliasNormalizer` after NarrowTower | camelCase facade host missed → suppress_windows | **Fixed (2026-11-21):** alias normalize right after duplicate-id scrub |
| floating snap `sameSlotSatellite` | distant same-slot terrace dragged with mass Y | **Fixed (2026-11-21):** only `hostedBy` attachments shift |
| floating snap direct-host only | annex moved but roof/chimney left at old Y | **Fixed (2026-11-21):** `HostAttachmentTree.descendantIds` shifts full host subtree |
| `_fetch_remote_models` unbounded `resp.read()` | huge `/models` JSON can OOM worker | **Fixed (2026-11-21):** 1 MiB body + 500 entry caps |
| pool-saturated error skipped ticket gate | cancelled request could still flash queue-full UI | **Fixed (2026-11-21):** `mayDeliverPreview` before saturated error send |
| `BaroqueMansionStyleRepair` ROOF `mayForceMansard` | sibling mansard unified authored hip/gable on same host | **Fixed (2026-11-20):** ROOF mansard fill requires blank `roof_type` |
| `StyleIntentResolver.detectFlavor` plan-only | local Huizhou under plan Modern got flat/glass | **Fixed (2026-11-20):** `EffectiveBuildingStyleResolver`; local/host chain first; entrance `bindDerived` inherits |
| `EffectiveBuildingStyleResolver` local-only | authored FACADE with host_id lacked style inheritance | **Fixed (2026-11-20):** resolve via host_id chain before plan style |
| `detectFlavor` merged plan decorative tokens | local Japanese beaten by plan gothic decor → stained | **Fixed (2026-11-20):** classify local/host identity before plan keywords |
| `typologyExclusivePlan` softens all circulation | stadium companion weakened residential stair hard-fail | **Fixed (2026-11-20):** soften only `soleTypologyShell`; router uses `blocksArchetypeFallbackFor(component)` |
| `looksDedicatedArenaHost` size≥30 | small real stadium demoted; large mansion misroute preserved | **Fixed (2026-11-20):** role/typology identity; residential cues demote; size not decisive |
| `DenseEavesPagodaMisrouteRepair` strip floor_count=3 | putIfAllowed rewrote authored 5-storey louge to 3 | **Fixed (2026-11-20):** preserve existing floors; missing-only default; locked features block STRUCTURE demote |
| `AssemblyCompileDiagnostics` test leakage | gap-emitting misroute tests polluted prepareComponents | **Fixed (2026-11-20):** BeforeEach/AfterEach clear on ExplicitOmission + misroute/conflict tests |
| `DenseEavesPagodaMisrouteRepair` typology inject | house primary → dense_eaves_pagoda STRUCTURE + drop satellites | **Fixed (2026-11-20):** open/multi-mass pagoda misroute → `E_DENSE_EAVES_PAGODA_ROUTE_UNSUPPORTED`; square force host-scoped to dense_eaves/tower only |
| `DenseEavesPagodaMisrouteRepair` storey alias / lanterns / false gap | floor_height bypassed storey_height lock; auto corner_lanterns; hall+pagoda false gap | **Fixed (2026-11-20):** alias registry + putIfAllowed; lanterns only on request/AI; authored pagoda composition skips gap; locked strip → gap |
| `ExplicitDesignConflictRepair`/`Validator` body index | only MASS_MAIN(/ANCILLARY/GATE) → MASS_SECONDARY opt-outs invisible | **Fixed (2026-11-20):** `MassComponentKinds.isHostMass` + GATE/STRUCTURE/TOWER |
| `ExplicitDesignConflictRepair` locked windows drop | host windows=none silently deleted USER_EXPLICIT FACADE_WINDOWS | **Fixed (2026-11-20):** locked openings → `E_EXPLICIT_DESIGN_CONFLICT` (crown parity) |
| `BuildPreviewPipeline` last-write collapse | identity-preserving overlap merge, not semantic ownership | **Deferred:** keep PatchOccupancyIndex / conflict validators; follow-up for ownership-aware overlap |
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
