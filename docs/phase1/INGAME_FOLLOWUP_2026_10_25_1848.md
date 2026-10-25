# Follow-up to 18:33–18:48 live feedback

The player session demonstrated that the earlier fixes did not close acceptance.
Five returned plans are archived under `src/test/resources/regressions/live-20261025-*.json`.

Changes in this follow-up:

- Quality auto-repair observes explicit refusal of extra support columns. It
  must not turn the ten-fence plan into 25 blocks by adding 15 supports. Normal
  residual-floating checks still apply; refusing supports does not certify a
  floating placement as valid.
- Incremental component PATCH accepts bounded, axis-aligned flat glass boxes
  (at most 64 cells, each dimension at most eight). Masses, arbitrary primitives,
  non-glass materials and boolean operations remain outside this exemption.
- Explicit open frames suppress inferred doors/windows. One-layer masses with
  `wall_type=none` normalize to plates rather than growing into enclosed houses.
- A narrowly identified reversed straight exterior flight can swap horizontal
  endpoints when its low end adjoins its hosted entrance and its high end points
  away. Subsequent circulation collision checks remain mandatory.

Still open: the actual shore-stair plan needs final hosted entrance/landing
replay and game walking evidence. The Huizhou courtyard/south-wing overwrite
and fortress entrance opt-out conflict remain rejected, not fixed or waived.
Successful preview/build, local edits and Undo still require live retesting.

Subsequent follow-up: `COURTYARD_SPACE` now honors an explicitly authored
`anchor_mode=center` while retaining the legacy min-corner default when absent.
The logged 16×7 court therefore occupies z=-3..3 rather than z=0..6, avoiding
the erroneous paving at south-wing z=5. This is a generator-coordinate fix;
the full compound and live placement still require acceptance.

Hosted entrance opt-out contradictions now enter the backend's existing
bounded validation-repair loop before Java compilation. If they remain
unresolved, the original design is returned as a capability gap rather than
replaced by a generic profile fallback. Six focused Python tests passed,
including mocked successful repair and unresolved-conflict preservation.

## Latest offline result

The shore-cabin replay now preserves a valid explicitly anchored Z-face door
associated with an exterior stair, including the door-frame span required by
the entrance generator. The reversed flight rises toward that doorway rather
than away from it. The logged plan compiles with circulation checks enabled.
The Chinese request marker `高脚` now selects stilt landing, whose rigid
placement branch retains authored elevation (`dy=0`) rather than lowering the
raised cabin as an ordinary house.

Validation completed in an isolated Gradle output directory:

- 30 targeted Java tests passed, including both logged shore-cabin plans.
- 133 constraint-parity and user-style-pack tests passed.
- Two additional complete logged-plan replays passed: Huizhou and pavilions.
- Six focused Python validation/repair tests passed; consistency tests are
  registered in `ci_gate.py`.

The exterior-collision test class now initializes Minecraft registries itself,
removing its dependence on another test running first. Earlier order-dependent
registry failures are not counted as passing runs.

These results supersede the earlier offline-open notes above. They do not
certify live water updates, actual walking, appearance, preview/build agreement
or Undo. Restart both the Java client and Python backend before repeating the
original player prompts. Fortress repair remains bounded: if the model does
not resolve the contradiction, it must still return a capability gap.
