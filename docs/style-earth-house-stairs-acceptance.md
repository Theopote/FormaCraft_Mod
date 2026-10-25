# Earthen house upper-storey access — 2026-10-25

The four width-13/width-21 Desert_Adobe and Prairie_Earth examples now use an
explicit hosted STAIR_SYSTEM, three cells wide, from ground floor y=0 to the
upper floor y=4. The run starts at local z=3, rises to z=7 and continues across
a two-cell upper landing. It uses registered stone-brick stair states and the
existing executor's tread/support/clearance constraints.

Body height increases from 8 to 9 and the flat roof follows from y=7 to y=8.
Both storeys retain floor_height=4. This leaves the stair and landing's authored
three-cell clearance below the roof instead of carving into it. Paired two-high
guards border the upper stairwell/landing. Original entry, approach and four
small windows remain.

Full-compiler tests require a supported path from the external approach to an
upper-storey floor cell, actual three-wide stair material, floor opening at the
step transition and headroom below the roof. The path model uses occupied cells,
cardinal movement, at most one block of height change, two clear standing cells
and additional clearance at upward transitions. This is not a live VoxelShape
collision test.

Removing the stair must restore the upper floor at its opening and make the
upper storey unreachable. A union of before/after coordinates must remain
unchanged outside the declared stair envelope, including its support course.
The earlier window-disable locality checks still run. No new blanket overwrite
permission or routing change was added for the stair; host-bound joins use the
existing structural/circulation contract.

The helper add_earth_house_openings rebuilds the combined program and few-shots
idempotently. The reference site remains dry, level and PRESERVE. Live walking,
fall protection, arbitrary terrain fitting, visual review and actual world-local
edits remain pending. No live screenshots were collected.

Final validation passed in .gradle/earth-openings-validation-output: 129 Java
geometry/constraint tests and four isolated user-pack tests. Sixteen related
Python tests, 115 CI golden HARD scenarios and six diversity scenarios passed.
The warning inventory remains 222 occurrences; this follow-up adds circulation
and edit-locality evidence without waiving warning checks.
