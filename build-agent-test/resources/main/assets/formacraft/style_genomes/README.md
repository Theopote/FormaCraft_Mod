# Genome palette roles

The twelve palette roles are `wall`, `roof`, `floor`, `window`, `foundation`,
`trim`, `pillar`, `cap`, `door`, `roof_trim`, `stairs`, and `roof_slab`.
Values are registered Minecraft block IDs. `stairs` and `roof_slab` describe
the pieces of a house roof; `roof_trim` colors roof edging independently of
wall trim. Use door, stair, and slab blocks for the corresponding roles.

`GenomeStyleProfile` preserves these roles. Catalog profiles expose the same
names under `defaults.materials`, accepting either a block ID or a list whose
first entry supplies the default. Captured component material `semantic_map`
values may reference all twelve roles; replacement preserves block shape and
orientation.

House materials resolve in this order: explicit spec material, profile role,
genome role, legacy default. Missing, malformed, and unregistered IDs fall
through to the next candidate. An explicit roof material also determines its
stair and slab family. Configured roof stair/slab roles survive subsequent
semantic palette selection. An omitted `roof_trim` falls back to resolved
wall trim. Older genomes can omit the four new roles.

Awning and ground-cover roles require their own geometry consumers and are
not part of this schema yet.
