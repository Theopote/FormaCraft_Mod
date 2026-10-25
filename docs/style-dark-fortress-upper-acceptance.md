# Dark fortress upper-tower circulation — 2026-10-25

Both width-13 and width-21 examples enlarge their towers from diameter 5 to 9
and height 14 to 16. The radius-2 tower interior could not accommodate the
authored stair runs and turning platforms. Ground-level hall/tower portals
remain at the shared contact faces and retain supported thresholds.

Each tower now contains three one-cell-wide straight stair flights (0→4, 4→8,
8→12), two horizontal turning landings and a top platform at local y=12. This
uses the existing STAIR_SYSTEM executor and registered stone-brick stair block
states, not the decorative TOWER stairs feature. Explicit clearance holes above
the final approach keep its headroom open. The deck has boundary guards with an
arrival opening and an outward stop; full fall-safety review remains pending.

Complete-compiler tests require registered stair material/facing, clear approach
holes, supported top floors and a path from the exterior approach to both top
platforms. The test path uses a conservative occupied-cell model: supported
floor surfaces, two-cell headroom, cardinal movement and at most one block of
height change. The stair executor also retains its own tread/support/clearance
constraints. This is not a live Minecraft VoxelShape collision or gameplay test.

Initial compilation rejected turning platforms that replaced preceding final
stair blocks. Their footprints now start beside those treads, retaining the
original stair states without overwrite permission. The final approach opening
also extends one cell farther back to preserve headroom while stepping up;
the path/clearance assertions were kept unchanged.

The missing-middle-flight control must make the left top platform unreachable
while retaining right top access. The earlier ground-level door-close control,
locality assertions, material checks and shared Patch/Build clipping remain in
the suite. No permission widening or unrestricted air primitives were added.

The fixed dry level reference site still uses PRESERVE. Live walking, stair
corner feel, fluid/terrain updates, collision and complete fall protection
remain pending. No new screenshots or actual world-edit evidence were obtained.

Final validation passed: 16 related Python tests, 125 Java geometry/constraint
tests and four isolated user-pack tests. All 115 CI golden HARD scenarios and
six diversity scenarios passed. The warning inventory remains 234 occurrences;
this geometry follow-up does not waive any remaining warning or live review.
