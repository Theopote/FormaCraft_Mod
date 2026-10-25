# Earthen house openings — 2026-10-25

Desert_Adobe and Prairie_Earth each retain their width-13 and width-21 examples,
earth wall palette and flat roof. Four existing plans and their linked few-shots
now include a three-cell-wide, three-cell-high entrance and a supported external
approach. Each storey has two front-facing 1x1 glass-pane windows, at local y=2
and y=6. Automatic host windows/doors are suppressed in favor of these explicit
components; the existing positive window ratio permits authored facades.

This is a fixed dry level-site composition with terrain_strategy=PRESERVE.
The approach only fills space outside the existing building footprint and
requires no overwrite permissions. Regenerate via add_earth_house_openings in
python_backend/eval/expand_p2_assets.py; the main authoring command uses the same
helper and the helper is idempotent.

Full-compiler geometry checks require continuous support and clear walking
cells from the approach through the ground-floor entry into the room. Every
authored window must use the requested glass material and have room space
behind it. Removing the window components and setting window_ratio=0 must
restore the four earth wall cells; a union of before/after coordinates checks
that no other block is added, removed or changed. Existing manifest replays
continue to validate materials and shared Patch/Build clipping.

The second storey's windows do not certify access to that storey. Upper-floor
stairs, arbitrary terrain landing, live collision, visual identity and actual
world-local edits remain pending. No live screenshots were collected.

The initial Java run was blocked before testing by a missing java.util.List
import in the working tree's GenerationAuthority edits. Only the missing import
was added; the pre-existing routing logic changes were preserved.

Concurrent work also wrote/cleaned normal build outputs. Validation therefore
uses a separate Gradle build directory outside build/, at
.gradle/earth-openings-validation-output, rather than deleting or sharing the
normal classes directory. The local init script sets layout.buildDirectory
before evaluation and runs the existing constraintParityTest task; it does not
change test filters or assertions.

Final validation passed in that isolated output directory: 129 Java geometry/
constraint tests and four isolated user-pack tests. Sixteen related Python
tests, all 115 CI golden HARD scenarios and six diversity scenarios passed.
The warning inventory fell from 234 to 222 occurrences; the twelve resolved
occurrences correspond to explicit entrance/entrance-component/window checks
for the four existing fixtures, with no waived evaluator assertions.
