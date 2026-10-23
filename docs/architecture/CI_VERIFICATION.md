# CI verification contracts

## Retrieval formats

`server.rag.KeywordCultureRetriever` produces the legacy Assembly DSL: its
few-shots come from `assembly_examples/`, referenced by `exampleRefs` or an
archetype's `exampleRef`. The Python LlmPlan planner consumes `llmPlanExampleRefs`
from `llmplan_examples/` instead.

Cards declaring only LlmPlan examples remain loaded in the Java repository and
available to the Python planner, but are excluded from Assembly retrieval. Mixed
cards with Assembly examples, and knowledge cards without format-specific
examples, remain eligible. This prevents a high-weight style preset from
displacing compatible Assembly examples without changing either card's weights
or the existing evaluation expectations.

The regression tests cover the two Japanese prompts, the modern negative-keyword
prompt, nonempty loaded Assembly few-shots, retained LlmPlan cards, and mixed
format cards. All 18 original Assembly evaluation cases keep their assertions.

## Layer boundaries

`scripts/check_architecture.py` scans every Java file, including root packages,
`config`, and `tools`. Only `client/` and the exact mixin classes declared in the
`client` list of `formacraft.mixins.json` may reference client implementation or
Minecraft/Fabric client APIs. World-write checks also apply to these files.

Client prompt and tool-state code lives in `client.ai`. The shared tool item lives
in `common.item`; its UI interaction is installed by `ClientInitializer` through
`UseItemCallback`. No client implementation is referenced by the common entrypoint.

## Python regression coverage

CI installs `requirements-test.lock`, which includes the production lock and a
pinned pytest toolchain, then runs the entire `python_backend/tests` directory.
The subsequent `eval.ci_gate --quick` retains scenario, diversity, negative,
constraint-parity, and schema gates while avoiding a second curated unittest run.
`gradlew build` already runs all Java unit tests, so no filtered rerun is needed.

When enabling full discovery, 96 existing replay snapshots lacked two reports
introduced by declarative ornament contracts: `proportion_hints.emissive_contract`
and `proportion_hints.huizhou_gable_contract`. Their migration adds only empty
`requirements` reports with `verification_level=plan_parameters_only`; every
pre-existing JSON value, including component geometry and diagnostics, was checked
for exact equality before writing. All replay assertions remain in place.

Two older tests were aligned with their authoritative catalogs: Jiangnan is now a
generic regional hint rather than a forced MODULE, and the capability inventory
includes both native-interpreter and compositional types. Inventory tests compare
the full ID sets and validate the evidence required by each routing policy.

These checks do not establish dedicated-server startup or in-game
generation/confirmation/undo acceptance.
