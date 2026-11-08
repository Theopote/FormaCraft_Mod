# Underground dwarven hall fixed-site acceptance

The original two above-ground dwarven hall examples remain available. The new
`dwarven_underground_hall` program variant owns two additional fixtures, widths
13 and 21, linked through its culture card and the existing compositional
typology. It is not a new native interpreter.

## Site and excavation

The reference surface is local y=0, hall floor y=-20, vault spring y=-11 and
vault apex y=-6. A three-wide descending approach runs from z=-23, floor y=0
to z=-4, floor y=-19; a landing joins an explicit three-wide entrance at the
hall floor. The one-storey room has no intermediate floor blocking its height.
The fixtures use PRESERVE and assume dry stone, with several blocks of cover
over the vault. They do not discover a suitable site or adapt to arbitrary caves.

The hollow mass emits explicit interior clearing. Additional air primitives
excavate the approach and the space below the quantized barrel-vault lining.
Vault strips have an explicit `host_id` and `allow_patch_overwrite=true` because
they intentionally open the host ceiling; unrelated overwrite checks remain
active. The strips stay below neighboring roof samples so slope risers and the
vault lining survive. CSG subtraction alone would omit generated blocks rather
than clear pre-existing terrain.

## Offline evidence

`ConstraintParityTest.undergroundDwarvenHallExcavatesSeededRockWithinItsBounds`
replays the actual compiled operations into a synthetic volume filled with stone
through the surface. It checks the full room volume (except authored luminous
braziers), three-wide descending access and headroom, excavation below the apex,
every generated vault-shell block, natural cover and outside rock. Every write
must stay in the declared envelope. A control without vault excavation must
leave rock in the upper room, preventing empty-world replay from passing
vacuously. Shared Patch/Build clipping also includes the removal/air operations.

Both fixtures are included in standard manifest geometry replay and CI goldens.
Python tests verify bilingual retrieval/refusal, generic-building classification,
schema/proportion references, authoring reproducibility and few-shot equality.

Rebuild with `python -m eval.expand_dwarven_underground` from `python_backend`.
The D/E authoring utility preserves existing program variants; after rebuilding
their base fixtures, rerun this utility to refresh the underground variants.
Run `gradle constraintParityTest --offline` from the root and:

```text
python -m unittest tests.test_p2_style_assets tests.test_style_p1_acceptance tests.test_building_request_classifier tests.test_style_identity
python -m eval.golden_eval --scenarios --ci-only
python -m eval.diversity_eval --scenarios --gate
```

Validation on 2026-10-22: 29 affected Python tests passed. All 105 CI golden
scenarios passed HARD checks with 262 SOFT warnings; six diversity scenarios
passed their existing gates. The final Java run passed 107 constraint/geometry
tests and four isolated user-pack tests. Both underground fixtures participate
in standard manifest replay as well as the seeded-stone excavation assertions.

## Remaining product acceptance

Synthetic stone replay does not emulate live permissions, fluid updates, lighting
propagation, gravity, ores, world height limits or executor skip behavior.
Preview/build agreement, entering and leaving the hall in game, water/lava
handling, protected-site behavior and edits remain unverified for this variant.
This composition is a single fixed-site vaulted hall, not a generated mine or
underground settlement.
