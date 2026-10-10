# Gothic game route feedback

The failed game plan selected gothic_cathedral_hall in proportion_hints but emitted
MASS_MAIN nave_main at width 13 plus aisle windows referencing missing aisle hosts.
The generic width contract therefore compared total width 25 with the nave width.
The native builder was never reached, so its prior geometry tests could not catch
this routing failure.

Building contract preparation now repairs an explicitly selected generic single
Gothic church to one native STRUCTURE before mass/host validation. Width/depth,
explicit nave wall height and entrance direction are passed as native parameters;
optional-feature constraints follow the existing Gothic contract. Repeated execution
is stable. Matching native width/depth/facing requirements report parameter-level
verification only. Generated aisle/roof/stair components are replaced because the
native type owns its composition; multi-slot layouts are reduced to the chosen slot.

This conservative repair excludes named landmarks, multi-building declarations,
selected unsupported variants/material phrases and conflicting/out-of-range/even
footprint dimensions. It is not a general arbitrary Gothic-plan conversion. Existing
native multiple hosts are not repaired. Future work should replace these conservative
text guards with explicit route capability negotiation for all user requirements.

Tests reproduce the failed shape and unknown hosts, assert a single native route,
correct dimensions/facing/disabled options, idempotence, and excluded variants.
Backend restart required. The previous JAR already contains native geometry/options;
this feedback fix does not change Java code. A real game retest remains necessary.
