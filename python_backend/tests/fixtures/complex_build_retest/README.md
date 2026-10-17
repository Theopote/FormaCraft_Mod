# Complex building regression plans

Captured from `run/logs/latest.log` on 2026-10-15, before these repairs:

| File | Request |
| --- | --- |
| `2.json` | Mountain hotel with three offset volumes |
| `3.json` | Gothic library with two towers |
| `4.json` | Four-storey stepped house |
| `5.json` | Factory, office, boiler house and chimney |

`3.normalized.json` is the result of `apply_building_contract(plan, '', finalize=True)`.
`4.normalized.json` and `5.normalized.json` are results of `normalize_circulation_plan(plan)`.
Python tests compare those snapshots with the normalizers; Java tests compile the resulting plans through the production parser and compiler.

The villa/hotel fortress misclassification is covered by routing tests rather than by replaying an incorrect fortress plan.

`latest_1.json` through `latest_6.json` capture the 23:18–23:41 retry session
on the same date: seaside inn, mountain hotel, Gothic library, stepped house,
factory and courtyard compound. Their normalized snapshots apply
`apply_building_contract(plan, original_request, finalize=True)` followed by
`normalize_circulation_plan`. Python tests exercise those transformations from
the raw logged plans; Java tests replay all six snapshots in the real parser
and compiler. These are offline regressions, not in-game visual validation.

`oct16_1` through `oct16_5` are the Oct 16 21:58–22:26 game responses:
Chinese villa, mountain hotel, stepped house, factory and coastal inn.
The two provider timeouts produced no plan to replay. Normalized variants use
the original request with the building contract and circulation normalizers.

`oct17_1.json` and `oct17_2.json` are the Oct 17 18:02–18:03 responses:
a villa with a two-cell-wide covered link and a mountain hotel using the
`rectangular` plan alias. Java replays both raw responses with and without
postprocessing to cover the reported wall-fit and unsupported-plan failures.
