# P2 offline baseline acceptance

P2's foundational execution scope is implemented: roof vocabulary, an executable
floating skeleton, D/E authored packs, wall vocabulary, luminous materials, and
visual candidates. P3 user-pack discovery/loading and Settings integration remain
unimplemented at this P2 acceptance. The subsequent P3 startup implementation is
recorded in `style-p3-acceptance.md`. This acceptance does not certify historical
or visual fidelity.

## Delivered packs

| Family | Authored packs |
| --- | --- |
| D | Desert adobe, prairie earth, Arctic igloo, stilt timber, water platform |
| E | Xianxia island, dwarven hall, elven treehouse, cyberpunk, steampunk, dark fortress |

Each pack links a twelve-role genome, canonical bilingual identity, profile,
palette, culture card, proportion card, two plan examples and CI goldens. Existing
Cyberpunk and Steampunk identities are reused. Culture/proportion IDs are bound
in existing compositional typologies rather than claiming new native interpreters.

The corpus now contains 31 genomes, 56 culture cards and 16 typologies. The shared
manifest covers 25 styles, 50 primary plans, 14 program variants and five authored
diversity alternatives. The new D/E packs own 22 primary plans plus two diversity
alternatives. The visual catalog contains 17 candidate cases.

The authored plans add raised posts and platforms, a circular snow enclosure and
dome, a floating island supporting a hall, a tree trunk supporting a deck, a stone
barrel vault with luminous brazier approximations, rooftop pods, a separate
chimney and copper cog, and stone spires. They are initial voxel compositions;
water terrain simulation, spiral tree cities and populated island cities are not
implemented by these fixtures.

## Execution contracts

`StyleWallPattern` executes `timber_frame` with rails, posts and diagonals, and
`neon_bands` on exterior solid wall cells. `smooth` retains the base wall. Existing
door/window decisions precede pattern application. Explicit trim/window block
parameters now reach the corresponding material roles in MassMainGenerator.

Neon bands require `emissive_enabled=true`. `emissive_block` defaults to sea
lantern and must resolve to a registered block with nonzero luminance; a colored
non-luminous block is rejected. Disabling emission removes the light bands.
The declarative emissive ornament contract supports Chinese/English positive and
negative requests on a unique Cyberpunk primary host. Component-local styles win;
ambiguous multi-host or directional requests report unresolved scope.

The generic ornament loader now accepts `style_ids` targets as an alternative to
typology targets, retaining existing typology behavior. Bare 赛博朋克 is a generic
archetype alias; explicit named megablock/tower preset aliases remain available.

See `style-p2-roofs.md` for four new roof kinds and `style-p2-floating-vision.md`
for the floating mass and evidence-preserving visual candidate path.

## Validation

On 2026-10-21 all seven CI steps passed: 350 Python unit tests, 101 golden
scenarios passing HARD checks, six diversity scenarios (stadium plus A–E),
duplicate/repair negative checks, Java constraint parity and 12 shape schemas.
Golden evaluation reports 254 SOFT warnings; outputs are not warning-free.

After the final metadata changes, 27 affected Python tests passed. The final Java
constraint suite also passed, including a full compiler check that emission can
be switched off and that the side copper cog survives compilation. Shared style
plan replay validates actual geometry, registered materials and Patch/Build
clipping equivalence for the additional plans.

Reproduce with `python -m eval.ci_gate` from `python_backend` using the configured
offline Gradle executable. `eval/expand_p2_assets.py` deterministically rebuilds
the authored D/E assets; it is an authoring utility, not a runtime plugin loader.

Remaining product acceptance includes live vision/model samples, world terrain
placement and visual review. These are not implied by offline CI success.
