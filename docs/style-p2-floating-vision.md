# P2 floating mass and visual case integration

## Floating mass

`SkeletonType.FLOATING_MASS` has an explicit contract and a registered
`FloatingMassGenerator`. The island is a solid elliptical volume widening toward
a level top platform. It has no ground supports and does not sample terrain.
The existing executable skeleton JSON parser accepts it:

```json
{"type":"FLOATING_MASS","params":{"width":17,"depth":17,"height":9,"elevation":12,"block":"minecraft:stone"}}
```

Width/depth must be integers in 5–65, height in 3–33, elevation in 1–256, measured
relative to the generation origin. An insufficient operation budget rejects the
whole island instead of returning truncated geometry.

The same voxel predicate is exposed through the existing component compiler as
`PRIMITIVE` with `params.kind="floating_mass"` (alias `floating_island`). Set the
component's relative Y to its desired elevation. `hollow=true` keeps a shell and
closed platform. Rotation and CSG remain available through ShapeLibrary.
Use `layout.skeleton_type="COMPOUND"` for this LlmPlan path: the new common
skeleton type is not advertised as a new LlmPlan layout interpreter.

`primitive_floating_mass.json` is a bundled few-shot example and is checked by
the shape-fixture CI step. Two authored golden plans use widths 13 and 21.
`FloatingMassTest` covers JSON parsing/registry execution, parameter and budget
rejection, connected volume, hollow platform, identical skeleton/component
geometry, and Patch/Build clipping parity.

This is a single geometric island, not yet a populated xianxia island city.

## Visual cases

`style_visual_cases/catalog_v1.json` adds searchable feature groups to six
existing plan examples: Gothic cathedral, Tang timber, industrial brick,
Alpine chalet, Art Deco, and Brutalism. Cases contain bilingual terms and an
existing plan fixture reference. Existing visual-case plan files are preserved.

`merge_visual_into_profile` retrieves candidates from structured massing,
materials, form observations and distinctive elements. At least two groups and
analysis confidence of 0.6 are required. Repeated words count once per group;
explicit negative observations do not count. Names, URLs, model style labels
and low-confidence caption fallbacks cannot by themselves supply matches.

Candidates are advisory research notes with overlap scores, matched groups and
example references. Ties remain multiple candidates. This path does not replace
an existing identity, mint source evidence, or apply geometry defaults. Existing
vision and reference blueprint paths remain authoritative for their own data.

Offline tests establish deterministic feature matching, not image-recognition
accuracy. No live vision model or screenshot quality assessment is claimed.

## Remaining P2 scope

Validation on 2026-10-21: all seven CI steps passed, including 348 Python unit
tests, 77 golden scenarios passing HARD checks, four diversity scenarios,
Java constraint parity (all four floating-mass tests passed), and 12 shape
fixture schemas. Golden output still reports 154 SOFT warnings.

D/E style packs, luminous material policy and wall vocabulary were subsequently
delivered; see `style-p2-acceptance.md` for final offline baseline acceptance.
P3 user-pack loading and Settings integration were subsequently implemented as
the startup baseline documented in `style-p3-acceptance.md`.
