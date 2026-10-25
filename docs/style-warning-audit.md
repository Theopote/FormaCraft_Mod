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

The original 113 scenarios now produce 240 warnings. The two new Huizhou skywell
examples add two window-presence warnings, giving **242 warnings across 115
scenarios**, with no HARD failures in the audit replay. No check was waived,
deleted or changed from failure to skipped/pass to obtain these counts.

| Remaining follow-up | Warning occurrences |
| --- | ---: |
| Route-specific geometry/program evidence required | 133 |
| Composition or intent review required | 108 |
| Missing proportion metadata in a historical captured stadium fixture | 1 |

These categories are triage, not confirmed exemptions or completed fixes. A
native STRUCTURE/ASSEMBLY/PRIMITIVE component's missing top-level entrance or
roof token does not prove the underlying geometry is defective. It also does
not prove access is present. Bind compiler evidence and the intended program
before deciding applicability. Composition warnings may be actual missing
openings, access or defensive geometry. Duplicate entrance checks remain visible.

The captured stadium fixture is retained as historical input; its missing
proportion hints and field paving remain recorded, rather than retroactively
editing the captured output. New skywell examples currently suppress automatic
windows; their two window warnings remain unresolved.

Every warning has scenario, fixture, check, detail, category, unresolved status
and next action in [the machine-readable inventory](style-warning-audit.json).
Regenerate from python_backend:

```text
python -m eval.style_warning_audit --output ../docs/style-warning-audit.json
```

The audit applies the same scenario HARD promotions as CI. It is an inventory,
not a replacement for the stricter golden --gate or Java geometry tests.
