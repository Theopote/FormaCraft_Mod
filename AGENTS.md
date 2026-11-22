# FormaCraft — AI Design Authority (repository-wide agent instructions)

> Applies to Cursor, Codex and other agents that read AGENTS.md. This file is a gate, not a replacement for existing architecture documents.

## Mission
FormaCraft is an **open-ended AI architectural planner**, not a text-to-preset classifier. Players may request novel, mixed, unnamed, or contradictory building types. Do not make generation depend on a finite set of request keywords, known buildings or fixed style templates.

## Read before any planning/generation change
1. `ARCHITECTURE.md` and `docs/GENERATION_PIPELINE.md` (canonical LlmPlan path).
2. `docs/architecture/GENERATION_AUTHORITY_MAP.md` and `docs/architecture/CANONICAL_BUILDING_PLAN.md` (design authority, provenance, capability gaps).
3. `docs/architecture/DESIGN_DECISION_INVENTORY.md` (known authority risks).
4. `docs/architecture/AI_DESIGN_AUTHORITY_GUARDRAILS.md` (the practical review contract in this pack).

## Non-negotiable constraints
1. **User intent + LLM own architecture**: building composition, use, massing, topology, site layout, style, roof type/pitch, facade rhythm, openings and ornamental language. Java owns deterministic geometry, parsing, collision, world constraints, preview and undo.
2. **No silent redesign**: a normalizer, validator, fallback, postprocessor or generator MUST NOT replace `USER_EXPLICIT` or `AI_PLANNED` decisions with a style/keyword default. Use the existing `design_sources` / `DesignProvenance` semantics; emit a validation issue or request replan if execution is impossible.
3. **No hidden default building**: do not silently fall back to HOUSE/TOWER, a whole-building typology, landmark MODULE, or ASSEMBLY preset when an open-ended plan is missing/invalid. Explicit user opt-in `preset:<id>` or `typology:<id>` is allowed. Respect existing routing contracts.
4. **No exhaustive keyword router**: substring matching may extract *non-authoritative hints* or explicit literals, never decide the mandatory building class, geometry template or style. Detect negation and unknown language; keep the original user text as authority.
5. **No schema-as-enumeration trap**: schema enums enumerate *supported geometry operations*, NOT all buildings that players may ask for. Extend composable operators or return `capability_gap` instead of teaching the LLM to pick a vaguely similar hardcoded building.
6. **Valid fixed rules**: Minecraft BlockState rules, coordinate transforms, resource caps, terrain/selection/protected zones, collision, anchor and socket alignment, no gravity blocks in unsupported structures, safety and deterministic geometry are welcome. Distinguish geometry constraints from aesthetic decisions.
7. **Single main path**: default `LlmPlan` → compiler/router → BlockPatch → preview/confirm/undo. BuildingSpec remains explicit compat. Do not resurrect client PromptAssembler.
8. **Plan integrity**: preserve explicit omissions (`roof_type=none`, no windows/doors where appropriate), requested dimensions/materials, mixed styles, and contextual alternatives. Unsupported plans must fail transparently with reason, not fake success.

## Required implementation workflow
- BEFORE changing a relevant file: state the architectural decision being affected, current owner (user/LLM/Java), and why Java-side enforcement is necessary.
- For each `if`/`switch`/regex/list of style or building keywords, classify it: FORMAT_REPAIR / HARD_CONSTRAINT / GEOMETRY / NONBINDING_HINT / DESIGN_OVERRIDE / EXPLICIT_TEMPLATE. `DESIGN_OVERRIDE` is blocked until redesigned.
- Prefer diagnose→`capability_gap`→bounded repair guard→recompile (e.g. FlatRoof) over new regex/hard patches in `RequestIntentInjector` or `building_contract.py` (see `AI_DESIGN_AUTHORITY_GUARDRAILS.md` anti-rebound).
- For each inserted default, declare provenance (`TECHNICAL_DEFAULT`), applicability (only when missing), and an explicit-opt-out test.
- For every new preset/typology, demonstrate how a user can still generate a novel composition WITHOUT invoking it.
- For changes to prompts, normalizers, compilers, routing, fallback or assembly repair: run the Skill `.cursor/skills/formacraft-design-authority-audit/SKILL.md` and supply an acceptance report.
- Add tests for negation, out-of-vocabulary requests, user override, model-planned value preservation, and explicit preset opt-in. Do not "fix" tests by changing expected AI designs to match hardcoded outputs.
- Do not report success without executing relevant checks; state exactly what you could not run.

## Mandatory PR summary
Every planning/generation PR must state: (a) design authority affected; (b) any new keyword/enum/preset/heuristic and justification; (c) before/after plan diffs; (d) positive/negative/out-of-vocabulary test evidence; (e) any `capability_gap` behavior; (f) all changes to `DESIGN_DECISION_INVENTORY.md`.
