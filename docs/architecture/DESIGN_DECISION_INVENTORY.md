# Design decision inventory

Hard-coded or inferred **architectural** choices (not pure geometry). Used as Phase 4–5 backlog. Line numbers are approximate — re-verify on edit.

## P0 — MASS satellite inference (Java)

| Location | Trigger | Design params | Tag | Migration |
|----------|---------|---------------|-----|-----------|
| `ComponentPlanCompiler.java` ~968–974 | MASS without FACADE_WINDOWS | facade rhythm, window_style | DESIGN_INFER | Gated: `plan_meta.allow_compiler_satellites` or `E_DESIGN_INCOMPLETE` |
| `ComponentPlanCompiler.java` ~976–984 | MASS without ENTRANCE | entrance_type, door width | DESIGN_INFER | Same |
| `ComponentPlanCompiler.java` ~1001–1008 | MASS without ROOF | roof_type, roof height (dims) | DESIGN_INFER | Same; height cap `span/3` max 8 at ~1800–1801 |
| `ComponentPlanCompiler.java` ~1014–1027 | MASS without CROWN | crown_type | DESIGN_INFER | Same + `ComponentCrownDecorator` |
| `ComponentPlanCompiler.java` `makeRoofComponent` | missing roof_type | roof_type default | DESIGN_INFER | LLM must specify or replan |
| `ComponentPlanCompiler.java` `resolveDefaultRoofType` | style/heuristic | roof_type | DESIGN_ENRICH | Source-aware StyleIntentResolver |

## Style / grammar enrichers (Java)

| Class | Params touched | Tag | Migration |
|-------|----------------|-----|-----------|
| `StyleIntentResolver` | plan_type, roof_type, features | DESIGN_ENRICH | Skip keys with `design_sources` = USER_EXPLICIT / AI_PLANNED |
| `RoofGrammarResolver` | roof pitch, specialty | DESIGN_ENRICH / GEOMETRY | Same |
| `OpeningGrammarResolver` | window rhythm | DESIGN_ENRICH | Same |
| `CrownGrammarResolver` | crown template | DESIGN_ENRICH | Same |

## Structure routing substitutes (Java)

| Location | Trigger | Effect | Tag | Migration |
|----------|---------|--------|-----|-----------|
| `GeneratorRouter.routeByBuildingType` | unmatched type | was `house` | AUTHORITY_SUBSTITUTE | `GenerationCapabilityResolver` → NEEDS_REPLAN |
| `GeneratorRouter.route` | null spec/type | was `tower` | AUTHORITY_SUBSTITUTE | UNSUPPORTED unless `allow_legacy_fallback` |
| `UnifiedGeneratorRouter` | empty registry + hints | structure adaptor | AUTHORITY_SUBSTITUTE | Explicit landmark/module only |

## Prompt routing (Java / Python)

| Location | Effect | Migration |
|----------|--------|-----------|
| `PromptAssembler` Assembly block | Forces ASSEMBLY component type | Soft hint by default |
| `ai_planner._derive_structured_intent` | Structured intent block | Hint only (documented) |
| `plan_architectural_enrichment.infer_heuristic_proportion_card` | Proportion card injection | AI_PLANNED or optional skip |

## Python planner inference

| Symbol | Params | Migration |
|--------|--------|-----------|
| `_infer_scale` / `_infer_terrain` | scale, terrain in context | TECHNICAL_DEFAULT hints |
| `_infer_mass_height` | mass height | Protocol repair only |
| `assembly_plan_repair` | assembly graph | Protocol only, not design substitute |
| `typology_plan_repair` | typology fields | Protocol only |

## Generator registry (appendix)

| Registry | Role | Class |
|----------|------|-------|
| Component | GeometryExecutor when plan complete | `ComponentGeneratorRegistry` |
| Structure | TemplateExecutor when explicit | `StructureGeneratorRegistry` |
| Legacy fallback keys `house`, `tower` | LegacyFallback | Only with `allow_legacy_fallback` |

## Test / CI coverage

| Item | Test |
|------|------|
| MASS-only no satellites | `DesignFidelityTest.massOnlyWithoutSatellitesFails` |
| Unmatched BuildingType | `GenerationCapabilityResolverTest.unmatchedTypeNeedsReplan` |
| Assembly prompt soft rule | `PromptRoutingAuthorityTest` + Java `PromptAssemblerAssemblyTest` |
