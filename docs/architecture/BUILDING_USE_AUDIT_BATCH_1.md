# Building use plan audit — batch 1

Building contracts now include a nonblocking building_use_audit alongside use
bindings. Each uniquely bound main building is inspected separately for hosted
ENTRANCE components and supported stair declarations on STRUCTURE components.
Single-floor buildings do not require inter-floor circulation. Missing/unknown
floor counts and duplicate host IDs are explicitly distinguished.

This is a plan-component inventory, not a geometric clearance validator. Inline
mass entrances may exist without an ENTRANCE component. Declared stairs may not
connect all floors. No capability_gap is added by this audit, and no doors, stairs,
partitions or furniture are generated as a side effect. Public-hall interior
clearance and final-block traversability remain not_assessed.

Regression cases verify host isolation, missing circulation, ambiguous IDs,
idempotence and absence of a geometry success claim. Backend restart loads the
change; no new JAR is needed. Next implementation should consume final Java block
geometry and stair endpoints to validate access per floor and open-space clearance.
