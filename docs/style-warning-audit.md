# Style warning audit — 2026-10-25

The original 113 CI scenarios produced 280 warnings. Updating the Python
validator to recognize Java StyleWallPattern vocabulary (smooth, timber_frame,
neon_bands) and native-only detail levels (aesthetic, refined, ornate) removed
38 evaluator false positives. Unknown values still fail, and facade components
do not inherit native detail vocabulary.

Two actual fixture defects were repaired: igloo MASS_MAIN height 3 was silently
raised to 4 by ComponentPlanCompiler. Both authored examples and few-shots now
declare height 4, with the dome starting at y=3 rather than y=2. The authoring
utility preserves this change. Geometry regression is required for this fix.

The original 113 scenarios initially fell to 240 warnings. Two Huizhou skywell
examples temporarily added two window-presence warnings (242 total). Both now
have explicit inward lattice windows; the dark-fortress fixtures also gained
two real hollow defensive tower shells each.
The tower ground-access follow-up adds explicit exterior/hall/tower portals,
resolving four entrance-presence occurrences.
The earth-house opening follow-up resolves twelve more occurrences across four
existing plans (entrance, duplicate entrance-component, and window presence).
The inventory now contains **222 warnings across 115 scenarios**, with no HARD
failures in the audit replay.
No check was waived or deleted; no failure was marked skipped/passed without a
corresponding validator or fixture correction.

| Remaining follow-up | Warning occurrences |
| --- | ---: |
| Route-specific geometry/program evidence required | 133 |
| Composition or intent review required | 88 |
| Missing proportion metadata in a historical captured stadium fixture | 1 |

These categories are triage, not confirmed exemptions or completed fixes. A
native STRUCTURE/ASSEMBLY/PRIMITIVE component's missing top-level entrance or
roof token does not prove the underlying geometry is defective. It also does
not prove access is present. Bind compiler evidence and the intended program
before deciding applicability. Composition warnings may be actual missing
openings, access or defensive geometry. Duplicate entrance checks remain visible.

The captured stadium fixture is retained as historical input; its missing
proportion hints and field paving remain recorded, rather than retroactively
editing the captured output. Skywell examples suppress automatic exterior
windows but now include explicitly authored inward lattice windows.

The two dark-fortress defensive-shell warnings were addressed by changing the
fixtures and few-shots, not the evaluator: each now has two hollow circular
blackstone TOWER components with continuous walls and a floor. Tower stairs,
live upper-tower access and complete defensive circulation remain pending; the
presence check alone does not certify an operational fortress.

See [ground-level tower access](style-dark-fortress-access-acceptance.md) for the
subsequent exterior-to-hall-to-tower path and portal-close negative control.

The [upper-tower follow-up](style-dark-fortress-upper-acceptance.md) now adds
validated offline stair/top-platform reachability and a missing-middle-flight
negative control. Live collision and fall-protection review remain pending.

[Earthen house openings](style-earth-house-openings-acceptance.md) add explicit
entries and four small windows per example. Ground access and window-disable
locality have dedicated compiler checks; upper-floor access remains pending.

Window/tower follow-up validation: 16 related Python tests, 125 Java geometry
and region-constraint tests, four isolated user-pack tests and 17 adjacent
opening-policy tests passed. In addition to fixture changes, the compiler now
preserves an existing host default-opening suppression flag during partial
delegation; this prevents localized windows from enabling unrelated defaults.
Skywell tests cover actual window materials, window-disable block locality,
open sky and four-wing reachability. Tower tests cover floors, hollow cores and
continuous blackstone shells. Live-world evidence remains pending.

Every warning has scenario, fixture, check, detail, category, unresolved status
and next action in [the machine-readable inventory](style-warning-audit.json).
Regenerate from python_backend:

```text
python -m eval.style_warning_audit --output ../docs/style-warning-audit.json
```

The audit applies the same scenario HARD promotions as CI. It is an inventory,
not a replacement for the stricter golden --gate or Java geometry tests.
