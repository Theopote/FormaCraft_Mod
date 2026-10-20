# Patch / Build constraint parity

`./gradlew constraintParityTest` runs the Java behavior gate independently of
the full test suite. The normal Gradle `test` task also discovers these tests.
The backend `python -m eval.ci_gate` runs the focused Java task after assembly
repair's negative case and before shape fixtures. `--quick` skips Python unit
tests only; it still runs the Java gate. A missing Gradle runtime, a timeout or
a failed Java task blocks the gate. Both Java suites must also produce JUnit
reports with passing, non-skipped tests; a successful but empty task is not enough.

The task runs offline. Prepare the Gradle/Minecraft dependencies with a normal
build first. `FORMACRAFT_GRADLE` may name an existing local Gradle executable;
otherwise the gate uses the repository wrapper. No executable override is
required in CI after the Java build step.

## Shared region contract

The shared inventory is `MutationRegionPolicy.Constraint`: protected zones,
inclusive bounds, and outline clipping. The same enum values drive the shared
policy's executed checks. Region filters declare their implemented kinds;
`ConstraintParityTest` compares the default Patch pipeline's inventory with
Build's shared inventory, then compares actual accepted patch lists.

Behavior checks cover translated coordinates, inclusive bounds, reversed
selection corners, circle and polygon footprints, outline height limits,
malformed outlines, protected zones and combined constraints. Independent
expected counts prevent two implementations sharing the same wrong rule from
passing merely because they agree. Three style plans are compiled and clipped
through both paths; accepted records, including materials and block states,
must match exactly. This tests constraint preservation of compiled style plans,
not equivalence of every style generator or a game's visual appearance.

## Scope and intentional differences

- Symmetry expands geometry before region filtering. It is not a boolean
  permission rule. Mirrored cells are tested against the same hard regions.
- Server Patch's `restrictToSelection` mode uses normalized selection bounds
  and suppresses the outline. Outline mode does not impose an inactive tool
  selection. Tests compare the corresponding effective Build region.
- Build's brush fallback and path corridor require request inputs that the
  current server Patch filter signature does not carry. Their Build behavior
  is locked separately; this gate does not claim those transport paths are
  equivalent. Brush bounds apply when no complete selection is present.

Add a shared region kind and a behavior fixture together when extending the
policy. Implement its Patch filter and declare its kind as well; deleting a
filter must fail both inventory and behavior tests. New style families can
extend the style-plan replay cases without changing the region policy.
