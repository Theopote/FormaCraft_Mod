# Design decision inventory

Hard-coded or inferred **architectural** choices (not pure geometry). Used as Phase 4–5 backlog. Line numbers are approximate — re-verify on edit.

## P0 — MASS satellite inference (Java)

| Location | Trigger | Design params | Tag | Migration |
|----------|---------|---------------|-----|-----------|
| `ComponentPlanCompiler.java` ~968–974 | MASS without FACADE_WINDOWS | facade rhythm, window_style | DESIGN_INFER | Gated: `plan_meta.allow_compiler_satellites` or `E_DESIGN_INCOMPLETE` |
| `ComponentPlanCompiler.java` ~976–984 | MASS without ENTRANCE | entrance_type, door width | DESIGN_INFER | Same |
| `ComponentPlanCompiler.java` ~1001–1008 | MASS without ROOF | roof_type, roof height (dims) | DESIGN_INFER | Same; height cap `span/3` max 8 at ~1800–1801 |
| `ComponentPlanCompiler.java` ~1014–1027 | MASS without CROWN | crown_type | DESIGN_INFER | Same + `ComponentCrownDecorator` |
| `ComponentPlanCompiler.java` `makeRoofComponent` | missing roof_type / height | roof_type default; height via `RoofPitchGeometry` | DESIGN_INFER | **Wired:** explicit `roof_height` / `roof_pitch_degrees` preferred; no silent cap 8 when pitch set |
| `ComponentPlanCompiler.java` `resolveDefaultRoofType` | style/heuristic | roof_type | DESIGN_ENRICH | Source-aware `putIfAllowed` |
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
| OMITTED design requirements | `CompilerSatellitePolicyTest` |
| Pipeline sub-decision | `ComponentPipelineRouteResolverTest` |
| Steep roof pitch fidelity | `RoofPitchGeometryTest`, `RoofGeneratorPitchFidelityTest`, fixture `plans/fidelity/gothic_steep_roof.json` |
| Grammar provenance | `DesignProvenanceGrammarTest` |
| Unmatched BuildingType | `GeneratorRouterCapabilityTest` |
| Assembly prompt soft rule | `AssemblyIntentSectionsTest` |
