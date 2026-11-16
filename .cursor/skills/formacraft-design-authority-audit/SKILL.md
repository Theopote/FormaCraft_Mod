---
name: formacraft-design-authority-audit
description: Audit and implement FormaCraft AI planning, prompting, routing, normalizer, generator, Assembly, fallback, and compatibility changes without replacing user/LLM architectural decisions with hardcoded choices. Use whenever modifying or reviewing generation code, prompt rules, typologies, presets or style heuristics.
---

# FormaCraft Design Authority Audit Skill

## Objective
Protect the separation **User/LLM = design authority**; **Java = deterministic geometry/safety execution**. Catch functional narrowing disguised as a harmless fallback, a style improvement, or a parser fix.

## Required sources (read from repository, not memory)
- `ARCHITECTURE.md`
- `docs/GENERATION_PIPELINE.md`
- `docs/architecture/GENERATION_AUTHORITY_MAP.md`
- `docs/architecture/CANONICAL_BUILDING_PLAN.md`
- `docs/architecture/DESIGN_DECISION_INVENTORY.md`
- `docs/architecture/AI_DESIGN_AUTHORITY_GUARDRAILS.md`

These current documents outrank archived local copies and obsolete generated reports.

## Procedure
1. **Scope and trace**: identify changed entrypoints and trace `userMessage` / structured constraints → `prompt_assembly.py` / `llm_plan_system_prompt.py` / `ai_planner.py` → `llm_plan_normalization.py` and plan repairs → Java `ComponentPlanCompiler` / `UnifiedGeneratorRouter` → `BlockPatch` and quality gate. Record default vs explicit compat.
2. **Decision inventory**: list every branching condition or default that depends on building name, building class, style, cultural region, prompt substring, special example, enum, template, `preset_id`, fallback or dimensional magic number. Include prompts as executable behavior.
3. **Classify** each rule as `HARD_CONSTRAINT`, `GEOMETRY`, `FORMAT_REPAIR`, `NONBINDING_HINT`, `EXPLICIT_TEMPLATE`, `DESIGN_OVERRIDE`. The last classification blocks acceptance; do not obscure it by renaming the rule.
4. **Provenance check**: for each affected plan field compare original model/user value and normalized/compiled value. Must preserve `USER_EXPLICIT` and `AI_PLANNED`; `TECHNICAL_DEFAULT` only if truly absent, never overwrite explicit null/none without contract. Check `DesignProvenance` and `putIfAllowed` where applicable.
5. **Capability behavior**: unsupported operation or missing geometry → bounded replan or explicit `capability_gap`, NOT HOUSE/TOWER, another roof, an unrequested generic template or a nearest-name substitution. If user explicitly typed `preset:<id>`/`typology:<id>`, the requested fixed executor is permitted.
6. **Metamorphic tests** (build/update tests at the appropriate layer):
   - Exact user instruction vs inferred style (e.g. AI-designed `xieshan` roof must not silently become `xuanshan`).
   - Negation ("不要大型", "无屋顶", "不要塔楼") must beat substring matching.
   - Ambiguity of substrings ("歇山屋顶" must not mean mountainous terrain).
   - Novel/unnamed building and blended style; should reach LlmPlan without known-name matching.
   - Same dimensions with diverse architectural interpretations; ensure no forced preset/house fallback.
   - Explicit `preset:` selection and invalid preset behavior.
   - Partial/incomplete ASSEMBLY graph should get model repair or gap, not injected aesthetic preset.
   - Preview and apply use the same effective Patch; protected/selection constraints remain enforced.
7. **Evidence**: run focused unit tests and relevant Gradle/Python tests as available. Do not assert live end-to-end quality from static inspection only. Compare `LlmPlan` before/after normalization and inspect active generator route. Where multi-model tests are unavailable, use multiple handcrafted valid plans and deterministic compilation checks.
8. **Decision**: BLOCK if design substitution, implicit template fallback, silent parameter overwrite or missing required tests. WARN for a non-binding heuristic likely to bias model prompts; suggest softening and adding negative cases.

## Output format

| Finding | File:line | Rule/branch | Owner | Classification | User impact | Remedy | Test |
|---|---|---|---|---|---|---|---|

Then give: `PASS / WARN / BLOCK`, a short change plan in dependency order, relevant commands executed and test results, unknowns, and new/deleted entries for `DESIGN_DECISION_INVENTORY.md`.

## Do not
- Add a larger keyword list in response to one failed building prompt.
- Add a fallback that pretends success.
- Label a design-changing mutation as normalization or protocol repair.
- Enforce architectural uniformity across all building categories via a housing-oriented prompt example.
- Require models to emit different plans every run; diversity is a diagnostic, not a mandatory randomization target.
