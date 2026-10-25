# Huizhou skywell compounds — 2026-10-25

Two fixed-site examples (width 17 and 21) combine four separately hosted
white-wall/dark-tile wings around a 7x7 paved skywell. Each wing has a hard-gable
roof and horse-head wall parameters. The front passage crosses the front hall;
three additional portals connect the other wings to the skywell.

The dedicated huizhou_tianjing culture/proportion cards link both few-shots and
CI scenarios. They supplement the earlier rectangular huizhou_vernacular
examples outside the 28-style manifest. They are authored voxel interpretations,
not a claim of historical reconstruction or automatic terrain/drainage fitting.

Rebuild from python_backend with `python -m eval.expand_huizhou_tianjing`.
Python checks cover bilingual retrieval/refusal, schema, few-shot equality and
CI ownership. Java checks replay the complete compiler and require an open
skywell, paved floor, two-cell clearance and ground-level flood-fill reachability
from the outside entrance to the skywell and all three other wings. The same
patches exercise both region clippers.

Final validation: 15 related Python tests passed; the earlier evaluator/CI
regression run passed 20 tests. Java passed 123 constraint/geometry tests plus
four isolated user-pack tests. Golden replay passed 115 HARD scenarios; six
diversity scenarios passed. No live-world acceptance is implied.

The first reachability run rejected the front hall's sealed inner wall and
outward-facing side portals. The authored fixtures now specify separate front
and skywell-facing doorways on the front hall and inward-facing wing portals;
the complete final-block flood fill passes without loosening its assertions.

Follow-up: each compound now contains four explicitly positioned inward
spruce-fence lattice windows in the front/rear halls, two blocks high. Narrow
side-wing corner posts remain intact; the initial side-corner windows were
removed after the locality test exposed their placement on timber posts. Automatic windows remain
suppressed; the host ratio permits the explicit facade components. The initial
ratio=0 conflict was rejected by the compiler and fixed in the authored plan,
without weakening the explicit-design conflict guard.

Geometry checks require all eight planned window cells to contain the specified
lattice material. Removing the facade components and explicitly disabling
windows must restore white wall cells at those locations and preserve every
other block. The open-sky and four-wing reachability checks still apply.
Drainage, historical fidelity, visual identity and game-world edits remain
pending. No new live-game evidence was obtained in this follow-up.
