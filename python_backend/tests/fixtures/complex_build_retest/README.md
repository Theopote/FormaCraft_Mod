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

`oct17_retry_1` through `oct17_retry_7` capture the 18:30–18:41 retry session:
villa, mountain hotel, Gothic library, stepped house, factory, courtyard and
coastal inn. The factory and coastal inn normalized snapshots run the production
building contract and circulation normalizers. They cover missing requested
office stairs and the length/width axis interpretation respectively. Older
snapshots now also include the explicitly requested length requirement.

`oct17_evening_1` through `oct17_evening_7` capture the 19:07–19:19 session.
The hotel and factory have normalized snapshots, covering detached volumes
mistaken for stacked tiers, incorrect bridge hosts/height units, a short bridge
and canopy, and its small interior landing. The library covers attached tower
joins; the courtyard covers the `hip_gable` roof alias. All seven are replayed
with and without postprocessing alongside the previous 24 plans.

`oct17_late_1` through `oct17_late_5` capture the 19:44–19:58 session:
mountain hotel, Gothic library, stepped house, factory and courtyard.
Normalized variants 1, 2 and 4 cover bridge level datum, tower floor hosts,
and explicit workshop clear height with a small bridge landing. All five
are replayed with and without postprocessing; the library and factory also
exercise oversized sill offsets and clearance across hosted railings.

`oct17_new_1` through `oct17_new_6` capture the 20:23–20:40 session:
mill, caravanserai, observatory, town hall, railway workshop and monastery.
Variants 1, 3, 4, 5 and 6 run the production contract and circulation repairs
and are replayed by Java, bringing the real-plan total to 41. They cover
part-vs-building requirement scopes, ground bridge datum, floor-bound legacy
stairs, neighboring foundations, partial platforms, shed roofs and tall hall
windows. Older normalized snapshots update contract metadata only.

Plan 2 is the already degraded generic profile fallback: the original compound
was lost after an invalid optional slot facing. It cannot be faithfully restored
from this response. A separate schema-repair regression inserts `facing=NONE`
into a multi-slot logged compound and verifies that all slots and component IDs
survive normalization without invoking the generic profile fallback.

`oct17_retest_1` through `oct17_retest_6` capture the 21:13–21:24 retry of
those six prompts. All six normalized plans join the Java replay set (47 total).
They cover independent quay plate endpoints, hosted gallery and cross-building
terrace access, ground paving under stair approaches, `extrude_mode=stair`,
self-host exterior clearance, and locally authored tall window patches/rows.
The older `oct17_retry_5` snapshot now also clears the stair's own host shell.

`oct18_1` through `oct18_6` capture the 00:09–00:18 session (four failures,
two completed buildings with roof defects). Their normalized variants bring
the Java replay set to 53 plans, each tested with and without postprocessing.
They exercise one-based bridge storeys and landing surface elevation, reversed
gallery approaches, raised doorway ownership, integer stair endpoints,
explicit ancillary-wing requirement scopes, and legacy courtyard inheritance.
The original responses remain unchanged. Older normalized snapshots are
regenerated only through the same contract/circulation entry points as their
tests; some gain corrected balcony anchors or improved stair clearance.

Separate Java assertions check final upper-door headroom, receiving platforms,
the chapel ridge/riser/gable, a hollow attic and an open shared courtyard.
Roof continuity tests deliberately remove joining risers and north-light roof
columns. These are offline geometry regressions, not in-game visual approval.

`oct18_retry_1` through `oct18_retry_6` capture the 00:56–01:05 retry
(three failures and three completed builds), bringing the replay set to 59.
They cover bounded gallery widening plus courtyard-column clearance,
polyhedral dome names and facet counts, zero overhang on a retreated host,
token-boundary wing aliases, `shape=stair` conversion with duplicate inferred
flight removal, square tower footprints, and height-only window hints that
otherwise produce empty facade geometry on short storeys. Explicit rectangle
sizes and provenance-locked window dimensions retain their authority.

`oct18_evening_1` through `oct18_evening_6` capture the 18:36–18:45 run
(six failures), bringing the replay set to 65. Raw responses remain unchanged.
Coverage includes independent footbridges over channels, bounded stair landing
lengths and railing openings, source and destination portals across real slots,
legacy interior stair roles and nested features, omitted layout references,
counted tall local windows, corner-coordinate floor evidence and inferred stairs
that must yield to an existing bridge. Missing or ambiguous slot identities are
not assigned by component order, and existing explicit slot references survive.

`oct18_night_1` through `oct18_night_6` capture the 19:17–19:27 run
(four failures, two successful builds), bringing the replay set to 71.
The bank landing stays a narrow receiving deck, stair-bound doors infer their
wall axis when facing is omitted, explicit facades can replace only their own
host's intrinsic glass, and tall hall windows retain their requested dimensions
when no storey count or raised floor is authored. Dedicated tests keep explicit
multi-storey floor counts and independently authored window ownership protected.
