# Building use intent — batch 1

Adds conservative Chinese/English recognition of residential, shop and public hall.
Research-to-plan prompts now express use-specific spatial guidance independently
of style and structural typology, with explicit user requirements taking priority.
The old mandatory decorative component checklist is replaced with optional details
appropriate to the requested use and style.

Final building contracts record building_use_intent at request scope. Mixed uses
are marked requires_binding; no building owner is inferred from component order.
Reports explicitly use intent_only and geometry_verified=false.

This batch does not add functional geometry generators, furniture, automatic room
partitions, general multilingual parsing or precise per-building use assignment.
Detection uses a small conservative phrase set; negations and quoted references
are not semantic intent resolution. These must not drive automatic geometry.
Prompt guidance currently applies to the research-to-plan path; final intent
reporting applies wherever the building contract is invoked.

Offline tests compare the same style across three uses, multiple styles for a shop,
English word boundaries, mixed scopes, idempotence and hollow-interior priorities.
Next: bind requested uses to building IDs and inspect actual circulation/open spaces
before claiming functional geometry acceptance.
