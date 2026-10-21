# Raised timber fixed-site acceptance

The `Stilt_Timber` and `Water_Platform` examples now include explicit access
components. These are fixed-site voxel compositions, not automatic terrain or
water-level fitting algorithms.

## Authored coordinates

All heights are relative to the plan anchor. The dwelling starts at y=5; four
one-block timber pile columns occupy y=0..4 and meet the dwelling floor at y=5.
The synthetic reference bed is y=-1 and water may occupy y=0..3. Under the
dwelling, all columns outside the four piles must remain untouched below y=5,
including air/removal patch operations. The examples do not promise to reach
the bed in deeper water or on uneven terrain.

The entrance access is on the negative-z side, matching the existing component
generator's SOUTH-facing doorway convention. A three-block-wide route joins
the entrance:

| Example | Access | Reference landing |
| --- | --- | --- |
| Stilt timber, widths 13/17/21 | Five ascending block treads and a raised landing | Ground tread at y=0, z=-8 |
| Stilt timber, width 17 | Additional supported porch canopy | Canopy at y=9 above the landing |
| Water platform, widths 13/21 | Level walkway with two timber piles | Bank must meet y=5 at z=-8 |

`terrain_strategy=PRESERVE` retains authored elevations. Per-column ADAPTIVE
shifts are inappropriate for this rigid example without a separate fitting
step. PRESERVE does not itself prevent other world/executor terrain work.

## Offline checks

`ConstraintParityTest.raisedTimberExamplesKeepSupportsClearanceAndAuthoredAccess`
compiles all five fixtures and checks pile continuity/contact, raw writes below
the floor, continuous entrance treads and two air cells above the center route.
Broken-tread and blocked-headroom controls ensure the clearance check rejects
invalid examples. The same compiled operations exercise Patch/Build clipping.
A direct terrain postprocessor check verifies PRESERVE returns the patches
without consulting a terrain sampler.

`test_p2_style_assets` checks schema validity, authoring-helper idempotence and
equality between fixture and few-shot plans. The helper in
`eval/expand_p2_assets.py` retains these access components when rebuilding assets.
Existing style golden and diversity gates also apply.

Reproduce with `gradle constraintParityTest --offline` from the repository root.
From `python_backend`, run:

```text
python -m unittest tests.test_p2_style_assets tests.test_style_p1_acceptance
python -m eval.golden_eval --scenarios --ci-only
python -m eval.diversity_eval --scenarios --gate
```

Golden HARD checks use the same policy as `ci_gate`: SOFT findings are reported
separately. The standalone golden `--gate` additionally fails on any SOFT finding.

Validation on 2026-10-22: ten focused Python tests passed; 103 CI golden
scenarios passed HARD checks with 262 SOFT warnings; six diversity scenarios
passed their existing gates. Stilt family mean pairwise distance is 0.199
(threshold 0.12). The final Java run passed 101 constraint/geometry tests and
four isolated user-pack tests, including all five raised-site fixture cases.

## Remaining product checks

Live-world acceptance must check actual bed contact, water updates, site/bank
height selection, preview-to-build alignment, automatic landing/support passes,
collision/headroom behavior and edits. Patch replay into empty space does not
establish water simulation or preservation by every world executor. Arbitrary
water levels, slopes and bathymetry require additional site fitting.
