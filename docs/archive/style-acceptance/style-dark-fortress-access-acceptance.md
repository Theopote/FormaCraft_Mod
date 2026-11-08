# Dark fortress ground-level tower access — 2026-10-25

Subsequent [upper-tower follow-up](style-dark-fortress-upper-acceptance.md)
enlarges the towers and adds three stair flights, turning landings and a top
platform. This document records the preceding ground-level baseline.

The width-13 and width-21 examples retain their hall, roof, spikes and two
hollow circular blackstone towers. Five explicit ENTRANCE components now
connect the exterior to the hall and the hall to both tower interiors. A short
external approach and tower-side threshold paving support the walking cells.
The fixed dry level reference site uses terrain_strategy=PRESERVE.

Threshold paving is confined to the tower side of the contact; the existing
hall foundation remains blackstone. An initial broader paving rectangle was
rejected by the patch conflict guard and corrected without adding overwrite
permissions or weakening that guard.

The complete compiler replay requires tower floors, clear ground-level portals,
intact outer shells and walls above the openings. Ground-plane flood fill uses
solid support and two empty cells above it, starting on the external approach
and reaching both tower cores through the hall.

A negative/local-edit replay removes only the left tower portal: its room must
become unreachable while the right room remains reachable. Every block outside
the removed portal's declared envelope must retain its material. Shared
manifest tests continue to cover material resolution and Patch/Build clipping.

This is offline ground-level access evidence. It does not certify upper-tower
stairs, upper storeys, collision shapes, live terrain fitting, historical or
visual fidelity, or actual world editing. No live screenshots were collected.

Rebuild these examples with add_dark_fortress_towers in
python_backend/eval/expand_p2_assets.py; its main authoring command also uses the
same helper. The helper preserves existing components and is idempotent.

Validation passed: 16 related Python tests, 125 Java geometry/constraint tests
and four isolated user-pack tests; 115 CI golden HARD scenarios and six
diversity scenarios passed. The warning inventory dropped from 238 to 234
occurrences because the two fixtures now express their actual entrances.
