# Jiangnan, Romanesque and Tudor specialist examples

Authored on 2026-10-23. These are bounded voxel interpretations, not historical
reconstructions or live-world certifications. Rebuild with
`python -m eval.expand_regional_specialists` from python_backend.

Three style bundles own six CI plans (widths 17 and 25), each with a genome,
profile, palette, bilingual identity, culture card, proportion card and linked
few-shot examples. They extend the manifest from 25 to 28 styles and reuse
existing compositional typologies; no new native interpreter is claimed.

| Bundle | Distinguishing geometry | Limits |
| --- | --- | --- |
| jiangnan_quay | White walls, dark tile gable, continuous stone quay and a water-access step | Dry fixed-site quay beside reserved channel space; no fluid simulation, automatic bank fitting or complete water-town network |
| romanesque_hall | Stone hall and supported freestanding semicircular entrance arcade | No arched wall-cut solver, full church program or historical reconstruction |
| tudor_house | Light infill and exposed dark timber framing | No jettied upper storey or historical reconstruction |

Jiangnan water town is now classified as a generic family. Wuzhen and
Zhouzhuang remain named places, covered by a boundary regression test.

The shared manifest tests cover bilingual retrieval/refusal, non-landmark
classification, schema, references, evidence gating, material registration,
compiled geometry and shared Patch/Build clipping. Additional geometry tests
check two-cell walking clearance over the quay, reserved channel space, arcade
crown/piers/passage, arcade-removal locality, and timber-frame disable locality.
These edits are offline plan replays; they do not exercise the in-game editor.

CI golden replay passed 113 HARD scenarios with 280 SOFT warnings. The related
Python run passed 16 tests (including manifest-wide subcases). Java passed 121
constraint/geometry tests and four isolated user-pack tests. The first Java run
caught six window-envelope floor-layout failures; the authored component heights
were corrected before the final successful run.

## Remaining live acceptance

The initial inventory had no repository client. The prior client log ends with
normal world saves and client shutdown. Its backend log contains successful
health/build requests, so the earlier Python-launch warning alone does not
establish a persistent backend failure.

The repository development client was restarted and its 1.21.10 window appeared;
a separate 1.21.11 single-player window was left untouched. The fresh capture
failed with `FrameArrived timed out: timed out waiting on channel`. After
refreshing window selection, activation/capture recovery failed with
`failed to activate captured window`. No gameplay input, world creation,
construction or edit was performed. The development client remains running.
Its log records the backend startup command; readiness was not independently
certified in this retry.

Actual screenshots, walking/fluid behavior, preview/build comparison and
world-local edits remain unverified. Offline checks cannot certify visual
identity or arbitrary sites.
