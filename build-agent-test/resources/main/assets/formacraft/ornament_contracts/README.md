# Declarative ornament contracts

Each `*.json` file declares `formacraft.ornament_contract.v1`, with an `id`
matching its filename. Restart the backend after changing assets: definitions
are validated and cached when first loaded.

Use an existing executor parameter on an existing typology. A contract does
not implement new geometry or claim voxel verification. Select the host by
`target.component_type` and `target.typology_id`; the engine recognizes both
`params.typology_id` and the `typology:<id>` feature tag.

`application: standalone` files are automatically applied by the building
contract pipeline. Each must have a unique `report.key` and a `report.schema`.
The report's verification level is `plan_parameters_only`.

- `toggles` contain a `property`, a `negative` regex and/or a `positive` regex.
  All matches are case insensitive. Negative matches take precedence. Positive
  toggles require exactly one matching host and no unresolved scope marker.
- `unresolved_scope_pattern` marks named or positional scope the contract cannot
  resolve. The engine never selects the first of multiple candidate hosts.
- `choices` form an ordered first-match list. Each contains `pattern`, optional
  `unless`, and `params`. They execute only with a unique, unambiguous host.
  `report_overrides` explicitly declares compatibility overrides such as
  replacing disabled twin towers with a requested single tower.
- Optional `building_requirement_bindings` maps building-contract properties to
  native parameters. Only exact values already set on the unique host can be
  acknowledged, at `native_parameters_only` verification.

`gothic.json` preserves the existing Gothic report and negative-first behavior.
The route-repair code remains separate: ornament contracts cannot change routes.

`application: collect` files only supply match occurrences to an existing atomic
owner. `chinese_timber.json` is consumed by the material contract so that a
conflict between paint/bracket/eave settings or materials prevents all related
updates. Overlapping positive text within a negative match is ignored. These
files are deliberately skipped by standalone dispatch.

Before adding a contract, add positive, negative, ambiguous-host and repeat-run
cases to `tests.test_ornament_contract` or a focused style test, and run the
backend CI gate. The migration baseline fixture records 64 complete outputs
from the implementations before this refactor.
