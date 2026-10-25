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
