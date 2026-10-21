# P2 roof execution

Four additional explicit `roof_type` values are implemented: `gambrel`,
`barrel_vault`, `onion_dome`, and `steep_thatch`. Chinese aliases and English
phrases are accepted by the explicit building contract and evidence compiler.
Hosted style enrichment accepts these defaults; evidence verification remains
required before research features supply defaults. Explicit requests take priority.

Gambrel uses a steep lower and shallow upper slope. Barrel vault uses a circular
cross section along the longer footprint axis. Onion dome generates a hollow
bulging shell with a narrow neck and closed tip. Steep thatch uses a linear slope
and rejects a rise/run below 1.2. The material remains explicit: `steep_thatch`
does not silently replace the requested material with hay.

Specialty roofs require a footprint of at least 5 by 5 and height of at least 3.
Height includes the base and tip layers. Footprint masking and overhang use the
existing roof pipeline. Negative requests do not enable a roof. Incompatible
specialty roofs on the same scope remain unresolved.

`python_backend/eval/fixtures/p2_roofs.json` owns eight authored plans, two sizes
per roof. They are also CI golden scenarios. `P2RoofGeometryTest` tests actual
generator shapes, hollow interiors, invalid shallow geometry, aliases, full plan
compilation, explicit roof material and Patch/Build region clipping parity. It is
included in `constraintParityTest`. Python contract tests are in the CI unit list.

Validation on 2026-10-21: all seven CI steps passed, including 343 Python unit
tests, 75 golden scenarios passing HARD checks, four diversity scenarios and the
Java constraint suite (including all four P2 roof tests). Golden output contains
142 SOFT warnings; passing HARD checks does not imply warning-free visual output.

This is the roof substage of P2. Fantasy skeletons, D/E style assets, wall-pattern
vocabulary, emissive strategies and vision-case integration remain outstanding.
Offline execution checks do not establish visual fidelity or live-world behavior.
