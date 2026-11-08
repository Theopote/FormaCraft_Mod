# Tulou specialist assets

This follow-up adds a dedicated earthen palette, bilingual culture card,
proportion card, and two native-blueprint examples (radius 10 / two floors and
radius 14 / three floors). The profile now selects packed-mud walls and timber
floors instead of the unrelated East Asian timber palette. Generic Tulou aliases
are classified as a building type rather than a named landmark.

The examples reuse the existing TulouBlueprintCompiler and TulouGenerator;
they do not introduce a new native generator or expand the 25-style manifest.
The authoring command is `python -m eval.expand_tulou_assets` from python_backend.

Offline checks cover bilingual retrieval/refusal, classification, schema,
few-shot equality, palette references, native earthen walls, tiled roofs,
annular floors at each storey, open courtyard columns, and shared Patch/Build
clipping. The native central well and lantern occupy the first three blocks
above courtyard ground; the sky above them remains open.

Python validation passed 32 related tests. CI golden replay passed 107 HARD
scenarios with 274 SOFT warnings. Six diversity scenarios passed. The final
Java run passed 109 constraint/geometry tests and four isolated user-pack tests.
Native Java validation uses the pure blueprint
compiler/generator and does not establish the live ServerWorld adapter route.

## Live evidence still pending

On 2026-10-23 the development client started and a Minecraft single-player
window was present. Window capture failed with `FrameArrived timed out` and,
after refreshing the window inventory, `window capture timed out`. Backend
auto-start also reported that its Python executable was unavailable. No build,
edit, walking test or screenshot evidence was obtained. No dedicated acceptance
world was created by this run. The running game was left open.

Historical fidelity, live entrance/circulation, actual placement and local edits
remain unverified. Jiangnan, Romanesque and Tudor specialist assets were
subsequently added in the [regional follow-up](style-regional-specialists-acceptance.md);
their live/historical acceptance also remains pending.
