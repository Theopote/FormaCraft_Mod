# P3 startup style-pack acceptance

P3's first implementation supports user-authored JSON directories, additive
asset discovery and validation, Java/Python consumption, a Settings directory
check/save control, a read-only backend status endpoint, and a contribution
workflow. It deliberately activates at process startup rather than hot-reloading
part of a live generation pipeline.

## Runtime path

The manifest declares a pack ID and asset files. Each process validates a pack
against a trial asset snapshot, committing it only if every file/reference passes.
Invalid siblings do not remove accepted packs or bundled assets. Existing Java
identity/genome/profile/palette/typology registries read through `UserStylePacks`.
Python identity, profile, palette, culture/few-shot, proportion, typology,
ornament and visual-case loaders use the corresponding startup snapshot.

New IDs are prefixed with the pack ID. Native interpreter registration, builtin
ID/alias replacement, path traversal and outside symlinks are rejected. Limits
bound the root entries, file count and JSON size. Cross-references and declared
material syntax are checked; real game material availability and geometry still
require actual compilation.

Settings checks and persists the directory without changing an already active
snapshot. The persisted directory is readable before client settings initialize.
An auto-launched local backend receives an absolute pack directory. A remote
backend needs its own copy and directory configuration; there is no filesystem
sharing or pack upload implied by a successful client check.

`GET /style-packs` reports backend acceptance/rejection. The validator CLI reports
the same static discovery result without activating assets. See
`user-style-packs.md` and `user-style-pack-v1.schema.json` for installation and
format details.

## Reproducible example

`docs/examples/style-packs/lake` is an eleven-file pack with all supported layers:
twelve-role genome, profile, palette, bilingual identity, compositional typology,
culture/proportion cards, two plan examples, a scoped ornament declaration and
visual feature groups. It introduces `lake_hall` and leaves bundled IDs intact.

The Python integration test starts a fresh backend interpreter with the pack
enabled and checks alias classification, culture retrieval, few-shot/proportion
lookup, planner defaults and the ornament contract. Java tests run in a separate
JVM with the same sample enabled, check actual registries, compile both plan
sizes and compare Patch/Build clipping results.

Negative cases cover invalid schema, duplicate files/IDs, builtin collisions,
missing references, malformed/oversized JSON, native interpreter claims, alias
collisions, out-of-root references, unknown identity targets and malformed
visual feature groups. Failed discovery cannot replace an active snapshot.

## Validation and limits

On 2026-10-22 the seven-step CI passed: 355 Python tests, 101 bundled golden
scenarios passing HARD checks, six diversity scenarios, negative repair/diversity
checks, Java parity and twelve shape schemas. The user-pack task is a dependency
of `constraintParityTest`; its four runtime tests run separately from the 94
bundled Java constraint tests. Golden output retains 254 SOFT warnings.

The final affected Python and isolated Java user-pack suites were rerun after
validation/startup configuration refinements. Java compilation includes the
Settings controls; actual in-game clicks and visual rendering were not exercised.

P3 is complete for this startup-only, additive JSON baseline. Hot reload, archive
installation, automatic multiplayer distribution, and community publication
services are not implemented. These are extensions rather than implied behavior
of the v1 directory loader.
