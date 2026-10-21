# Single treehouse access acceptance

The two `p2_elven_treehouse` examples demonstrate a single timber dwelling on
a cylindrical trunk. Their city-related style aliases are retrieval vocabulary,
not evidence of a generated tree city, spiral stair or canopy bridge network.

## Composition

The trunk occupies local y=0..12 and contacts the dwelling floor at y=13.
A three-block-wide wooden run rises from z=-16, floor y=0 to z=-4, floor y=12.
A landing at z=-3..-1 joins the existing negative-z entrance and interior floor.
An explicit hosted entrance declares a three-block-wide opening; the small
dwelling's inferred default entrance was narrower than the approach.
Two-course treads share vertical faces with adjacent treads; the landing joins
the dwelling. These are full wooden block treads, not a new stair generator.
The existing voxel primitives compile the authored composition.

`terrain_strategy=PRESERVE` retains the relative heights. The fixture assumes
an appropriate fixed site at its anchor; it does not select a natural tree,
solve arbitrary terrain or establish structural realism at this span.

## Evidence

`ConstraintParityTest.treehouseTrunkAccessAndAccessRemovalRemainConnectedAndLocal`
checks compiled trunk/floor contact, every cell on the three-wide entrance route,
two cells of air above each tread, and shared-face tread connections. Negative
controls inject an obstructed head cell and remove a tread. Removing all authored
access components and recompiling must preserve all dwelling and trunk blocks;
changes are restricted to the external negative-z approach. Patch and Build
region clipping are compared on the same compiled operations.

`test_p2_style_assets` checks schema validity, equality with linked few-shots and
idempotent rebuilding by `add_treehouse_access`. The main authoring utility uses
that helper, so regenerating D/E assets retains the access geometry.

Run `gradle constraintParityTest --offline` at the repository root and, from
`python_backend`, run:

```text
python -m unittest tests.test_p2_style_assets tests.test_style_p1_acceptance
python -m eval.golden_eval --scenarios --ci-only
python -m eval.diversity_eval --scenarios --gate
```

Validation on 2026-10-22: 11 focused Python tests passed, with four affected
asset tests rerun after final entrance assertions. All 103 CI goldens passed
HARD checks with 258 SOFT warnings. Six diversity scenarios passed. The final
Java run passed 103 constraint/geometry tests plus four isolated user-pack tests.
The three-wide entrance clearance assertions remain enabled for both sizes.

## Remaining acceptance

Live walking/collision behavior, fall protection, site fitting, preview/build
agreement and the user-facing edit chain remain unverified. This offline removal
test demonstrates compilation locality, not completion of world editing.
Spiral access, multiple trees, continuous canopy bridges and populated tree-city
layouts require dedicated compositions and acceptance.
