# Style expansion delivery status

This document tracks phase completion separately from passing incremental CI.

The six-axis product acceptance checklist and per-profile asset inventory are in
[`style-capability-matrix.md`](style-capability-matrix.md). Offline phase completion
does not certify live preview/build, visual identity or style-preserving local edits.
The northern siheyuan identity now has its own profile and culture-card binding;
Huizhou retains a separate culture card and now has two compiled dwelling fixtures
with stepped gables, explicit gable opt-out and Patch/Build clipping checks.
Live visual comparison and a complete tianjing compound remain pending.

Raised timber examples now include authored steps or a bank walkway and use
PRESERVE elevations. Fixed-site support, clearance and underfloor write bounds
are covered separately in [`style-raised-timber-acceptance.md`](style-raised-timber-acceptance.md).
Arbitrary water/terrain fitting and live-world preservation remain pending.

The two single-treehouse fixtures also include a continuous straight timber
approach to the elevated entrance; their support/access and compiler edit-locality
checks are documented in [`style-treehouse-acceptance.md`](style-treehouse-acceptance.md).
This does not establish a spiral stair or a multi-tree city.

A separate dwarven underground program now links two dry-stone excavation
fixtures while retaining the above-ground hall examples. Its synthetic stone
replay and live-world limits are recorded in
[`style-dwarven-underground-acceptance.md`](style-dwarven-underground-acceptance.md).

| Phase | State | Delivered | Remaining |
| --- | --- | --- | --- |
| P0 | Implemented | Catalog-driven planner defaults and generic markers; shared material aliases; twelve palette roles | Preserve regression coverage as assets grow |
| P1 | Complete: local asset/execution acceptance | Declarative ornament contracts; scoped style priority; 20 genomes, 45 culture cards, 16 typologies; golden, negative, diversity and parity gates | Live sampling and visual/historical fidelity are outside this acceptance |
| P2 | Complete: offline foundation baseline | Four roof generators, floating skeleton/primitive, 11 D/E packs, wall patterns, luminous material policy, 17 visual candidates. See `style-p2-acceptance.md` | Live-world/visual acceptance and more elaborate fantasy compositions remain outside this baseline |
| P3 | Complete: startup JSON baseline | Additive pack format/validation, Java/Python loaders, Settings directory check/save, example pack and contribution workflow. See `style-p3-acceptance.md` | In-game click/visual acceptance; optional hot reload, archives and distribution services |

Bundled counts at P1 acceptance:

| Asset | Current | P1 target |
| --- | ---: | ---: |
| Genomes | 20 | 20 |
| Culture cards | 45 | 45+ |
| Structural typologies | 16 (10 native, 6 compositional) | 16 |

The manifest covers Tang timber, Renaissance, Art Deco, Brutalism, industrial
brick halls, Japanese timber, Scandinavian timber, Mediterranean stucco, and
Georgian brick, Prairie house, midcentury house, Alpine chalet, neoclassical civic,
and Modern International. Fourteen style entries own 28 primary plans, seven
program cards own 14 further plans, and three authored diversity alternatives
bring the manifest to 45 plans. A separate mixed garden/chapel golden is also tested.
The generic compositional packs register six shared
compositional typologies: timber gable house, masonry palazzo, industrial brick
hall, concrete civic block, stucco tile house, and flat-roof pavilion. These use
`compositional_only` routing and the existing component compiler, with no native
interpreter claim. All manifest fixtures exercise their actual geometry and region
clipping. Typology growth requires executable routing and geometry validation;
the new compositions are not new native generators.

Offline native tests invoke pure builders because runtime typology routing
requires a live ServerWorld. CI success establishes the covered contracts and
outputs; it does not establish historical accuracy, visual quality, live-world
routing, or completion of later phases.

Final P1 validation: 340 Python tests, 67 CI golden scenarios, four CI diversity
scenarios (existing stadium plus A/B/C style families), 58 Java parity/adapter
tests, and 45 culture-card validations passed. See `style-p1-acceptance.md` for
the scope and reproduction commands. P2 offline baseline acceptance is recorded
in `style-p2-acceptance.md`; P3 startup baseline acceptance is recorded in
`style-p3-acceptance.md`.
